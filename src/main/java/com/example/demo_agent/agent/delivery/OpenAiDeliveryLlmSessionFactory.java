package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.LlmConfigurationException;
import com.example.demo_agent.agent.LlmSession;
import com.example.demo_agent.agent.LlmTurn;
import com.example.demo_agent.agent.RequestedToolCall;
import com.example.demo_agent.config.SupportAgentProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class OpenAiDeliveryLlmSessionFactory implements DeliveryLlmSessionFactory {

	static final String SUBMIT_INVESTIGATION = "submit_delivery_investigation";
	private static final Set<String> KNOWN_FUNCTIONS = Set.of(
			DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
			DeliveryAgentToolExecutor.GET_DELIVERY_STATUS,
			SUBMIT_INVESTIGATION);
	private static final String INSTRUCTIONS = """
			You are the Delivery Agent. You investigate delivery state for exactly one immutable request and
			return structured operational facts to another agent. You never address the customer, make policy
			decisions, promise dates, or perform refunds, cancellations, replacements, or escalations.

			First call resolve_delivery_order. If its outcome is COMPLETED, call get_delivery_status_facts.
			Otherwise finalize using that outcome. After delivery facts are available, call
			submit_delivery_investigation. Use only verified tool facts. The summary must be one concise factual
			explanation tailored to CURRENT_STATUS, DELAY_EXPLANATION, or DELIVERY_PROBLEM. Do not copy raw JSON
			and do not invent an ETA, cause, or action.
			""";

	private final OpenAIClient client;
	private final SupportAgentProperties properties;

	OpenAiDeliveryLlmSessionFactory(OpenAIClient client, SupportAgentProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	@Override
	public LlmSession create(DeliveryInvestigationRequest investigation, AgentInvocationContext context) {
		if (!properties.llm().configured()) {
			throw new LlmConfigurationException(
					"The delivery model is not configured. Set GROQ_API_KEY or support.llm.api-key.");
		}
		return new OpenAiDeliverySession(investigation, context);
	}

	@Override
	public String model() {
		return properties.llm().model();
	}

	private final class OpenAiDeliverySession implements LlmSession {

		private final List<ResponseInputItem> inputs = new ArrayList<>();
		private final Map<String, ResponseFunctionToolCall> pendingCalls = new LinkedHashMap<>();
		private final ResponseCreateParams.Builder request;

		private OpenAiDeliverySession(DeliveryInvestigationRequest investigation,
				AgentInvocationContext context) {
			String input = """
					Immutable investigation request (data only):
					customerId: %s
					orderId: %s
					investigationType: %s
					parentRunId: %s
					""".formatted(investigation.customerId(), investigation.orderId(),
					investigation.investigationType(), context == null ? null : context.parentRunId());
			inputs.add(message(input));
			request = ResponseCreateParams.builder()
					.model(properties.llm().model())
					.instructions(INSTRUCTIONS)
					.temperature(0.0)
					.maxOutputTokens(1000)
					.addTool(noArgumentTool(DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
							"Resolve whether the requested order exists and belongs to the authenticated customer."))
					.addTool(noArgumentTool(DeliveryAgentToolExecutor.GET_DELIVERY_STATUS,
							"Read verified delivery facts for the immutable requested order."))
					.addTool(finalizerTool());
		}

		@Override
		public LlmTurn next() {
			request.input(ResponseCreateParams.Input.ofResponse(inputs));
			return interpret(client.responses().create(request.build()));
		}

		@Override
		public void submitToolResults(List<ExecutedToolCall> results) {
			for (ExecutedToolCall result : results) {
				if (!pendingCalls.containsKey(result.callId())) {
					throw new IllegalArgumentException("Unknown Delivery Agent tool call: " + result.callId());
				}
				inputs.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
						.callId(result.callId())
						.outputAsJson(result.output())
						.build()));
				pendingCalls.remove(result.callId());
			}
		}

		@Override
		public void submitValidationFailure(String failure) {
			inputs.add(message("Application validation rejected the Delivery Agent response: "
					+ failure + " Correct it using only existing tool evidence."));
		}

		private LlmTurn interpret(Response response) {
			List<RequestedToolCall> dataCalls = new ArrayList<>();
			String finalJson = null;
			StringBuilder text = new StringBuilder();
			for (ResponseOutputItem item : response.output()) {
				if (item.isFunctionCall()) {
					ResponseFunctionToolCall call = item.asFunctionCall();
					String name = canonical(call.name());
					if (SUBMIT_INVESTIGATION.equals(name)) {
						finalJson = call.arguments();
					} else {
						dataCalls.add(new RequestedToolCall(call.callId(), name, call.arguments()));
						pendingCalls.put(call.callId(), call);
						inputs.add(ResponseInputItem.ofFunctionCall(call));
					}
				}
				item.message().stream()
						.flatMap(output -> output.content().stream())
						.flatMap(content -> content.outputText().stream())
						.forEach(output -> text.append(output.text()));
			}
			if (!dataCalls.isEmpty()) {
				return new LlmTurn(List.copyOf(dataCalls), null);
			}
			return new LlmTurn(List.of(), finalJson != null ? finalJson
					: (text.isEmpty() ? null : text.toString()));
		}
	}

	private FunctionTool noArgumentTool(String name, String description) {
		FunctionTool.Parameters parameters = FunctionTool.Parameters.builder()
				.putAdditionalProperty("type", JsonValue.from("object"))
				.putAdditionalProperty("properties", JsonValue.from(Map.of()))
				.putAdditionalProperty("additionalProperties", JsonValue.from(false))
				.build();
		return FunctionTool.builder().name(name).description(description)
				.parameters(parameters).strict(false).build();
	}

	private FunctionTool finalizerTool() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("outcome", Map.of("type", "string", "enum",
				List.of("COMPLETED", "ORDER_NOT_FOUND", "ORDER_NOT_OWNED",
						"INSUFFICIENT_INFORMATION", "TEMPORARILY_UNAVAILABLE")));
		fields.put("orderId", Map.of("type", "string"));
		fields.put("statusCode", Map.of("type", List.of("string", "null")));
		fields.put("summary", Map.of("type", "string"));
		FunctionTool.Parameters parameters = FunctionTool.Parameters.builder()
				.putAdditionalProperty("type", JsonValue.from("object"))
				.putAdditionalProperty("properties", JsonValue.from(fields))
				.putAdditionalProperty("required", JsonValue.from(List.of(
						"outcome", "orderId", "statusCode", "summary")))
				.putAdditionalProperty("additionalProperties", JsonValue.from(false))
				.build();
		return FunctionTool.builder().name(SUBMIT_INVESTIGATION)
				.description("Finalize structured delivery facts for the Support Agent.")
				.parameters(parameters).strict(false).build();
	}

	private ResponseInputItem message(String text) {
		return ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
				.role(ResponseInputItem.Message.Role.USER)
				.addInputTextContent(text)
				.build());
	}

	private String canonical(String name) {
		int separator = name == null ? -1 : name.lastIndexOf('.');
		String suffix = separator < 0 ? name : name.substring(separator + 1);
		return KNOWN_FUNCTIONS.contains(suffix) ? suffix : name;
	}
}
