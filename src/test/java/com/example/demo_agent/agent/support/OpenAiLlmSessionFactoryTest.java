package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.LlmSession;
import com.example.demo_agent.agent.LlmTurn;
import com.example.demo_agent.agent.RequestedToolCall;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenAiLlmSessionFactoryTest {

	@Test
	void constructsAllToolSchemasIncludingZeroArgumentPolicyTools() {
		OpenAiLlmSessionFactory factory = new OpenAiLlmSessionFactory(mock(OpenAIClient.class), properties());

		LlmSession session = factory.create(List.of(), "CUST-1001", "What is the refund policy?");

		assertThat(session).isNotNull();
	}

	@Test
	void treatsSubmitAgentDecisionAsTheFinalDecisionInsteadOfABusinessToolCall() {
		String decisionJson = """
				{"message":"Please choose an order.","outcome":"CLARIFICATION_REQUIRED",
				"resolvedIntents":["delivery status"],"orderId":null,"evidence":[],
				"recommendations":[],"actionsRequiringApproval":[]}
				""";
		LlmTurn turn = OpenAiLlmSessionFactory.interpretOutput(List.of(new RequestedToolCall(
				"decision-1", OpenAiLlmSessionFactory.SUBMIT_AGENT_DECISION, decisionJson)), "");

		assertThat(turn.toolCalls()).isEmpty();
		assertThat(turn.proposedDecisionJson()).isEqualTo(decisionJson);
	}

	@Test
	void acceptsProviderPrefixedSubmitAgentDecisionAsTheFinalDecision() {
		String decisionJson = """
				{"message":"Please choose an order.","outcome":"CLARIFICATION_REQUIRED",
				"resolvedIntents":["delivery status"],"orderId":null,"evidence":[],
				"recommendations":[],"actionsRequiringApproval":[]}
				""";
		LlmTurn turn = OpenAiLlmSessionFactory.interpretOutput(List.of(new RequestedToolCall(
				"decision-1", "functions.submit_agent_decision", decisionJson)), "");

		assertThat(turn.toolCalls()).isEmpty();
		assertThat(turn.proposedDecisionJson()).isEqualTo(decisionJson);
	}

	@Test
	void parsesGroqRateLimitRetryDelayWithSafetyMargin() {
		assertThat(OpenAiLlmSessionFactory.rateLimitDelayMillis(
				"Please try again in 562.5ms.")).isEqualTo(813);
		assertThat(OpenAiLlmSessionFactory.rateLimitDelayMillis(
				"Please try again in 1.5s.")).isEqualTo(1_750);
		assertThat(OpenAiLlmSessionFactory.rateLimitDelayMillis("rate limited")).isEqualTo(1_000);
	}

	@Test
	void continuesToReturnDataFunctionCallsToTheToolExecutor() {
		RequestedToolCall dataCall = new RequestedToolCall(
				"call-1", "get_customer_orders", "{\"customerId\":\"CUST-1001\"}");
		LlmTurn turn = OpenAiLlmSessionFactory.interpretOutput(List.of(dataCall), "");

		assertThat(turn.proposedDecisionJson()).isNull();
		assertThat(turn.toolCalls()).containsExactly(dataCall);
	}

	@Test
	void includesPriorVerifiedOrderDateInTheNextModelContext() {
		LocalDateTime placedAt = LocalDateTime.of(2026, 10, 18, 8, 0);
		CustomerOrderResponse order = new CustomerOrderResponse("CUST-1003", "ORD-1005", placedAt,
				new OrderStatusResponse("LOST_IN_TRANSIT", "Lost in transit", "Lost",
						placedAt.plusDays(3), true));
		ConversationTurn turn = new ConversationTurn("Where is ORD-1005?",
				"ORD-1005 is lost in transit.", List.of(new ExecutedToolCall(
						"orders", GetCustomerOrdersTool.NAME, List.of(order), true, null)));

		String context = OpenAiLlmSessionFactory.verifiedHistoryContext(List.of(turn));

		assertThat(context).contains("get_customer_orders", "ORD-1005",
				"\"orderStartDate\":\"2026-10-18T08:00:00\"");
	}

	@Test
	void ignoresAPrematureFinalDecisionWhenTheSameTurnStillRequestsData() {
		RequestedToolCall dataCall = new RequestedToolCall(
				"call-1", "get_refund_policy", "{}");
		RequestedToolCall prematureDecision = new RequestedToolCall(
				"decision-1", OpenAiLlmSessionFactory.SUBMIT_AGENT_DECISION, "{\"message\":\"premature\"}");

		LlmTurn turn = OpenAiLlmSessionFactory.interpretOutput(List.of(dataCall, prematureDecision), "");

		assertThat(turn.proposedDecisionJson()).isNull();
		assertThat(turn.toolCalls()).containsExactly(dataCall);
	}

	@Test
	void recognizesRejectedFinalDecisionArgumentsForTheNormalRequestRetry() {
		assertThat(OpenAiLlmSessionFactory.isRejectedFinalDecisionArguments(
				"400: Failed to parse tool call arguments as JSON")).isTrue();
		assertThat(OpenAiLlmSessionFactory.isRejectedFinalDecisionArguments(
				"400: Tool call validation failed: parameters for tool submit_agent_decision "
						+ "did not match schema: missing properties: 'actionsRequiringApproval'")).isTrue();
		assertThat(OpenAiLlmSessionFactory.isRejectedFinalDecisionArguments(
				"400: Tool call validation failed: parameters for tool get_refund_policy did not match schema"))
				.isFalse();
		assertThat(OpenAiLlmSessionFactory.isRejectedFinalDecisionArguments(
				"400: attempted to call an unknown tool")).isFalse();
	}

	@Test
	void recognizesRetryableProviderGenerationFailures() {
		assertThat(OpenAiLlmSessionFactory.isRetryableGenerationFailure(
				"400: Failed to generate JSON. See 'failed_generation' for details.")).isTrue();
		assertThat(OpenAiLlmSessionFactory.isRetryableGenerationFailure(
				"400: Failed to validate JSON. Please adjust your prompt.")).isTrue();
		assertThat(OpenAiLlmSessionFactory.isRetryableGenerationFailure(
				"400: Tool choice does not match the requested function.")).isFalse();
	}

	private SupportAgentProperties properties() {
		return new SupportAgentProperties(
				new SupportAgentProperties.OrderApi("http://localhost:8080", Duration.ofSeconds(1), Duration.ofSeconds(1)),
				new SupportAgentProperties.ChatApi("http://localhost:8080",
						Duration.ofSeconds(1), Duration.ofSeconds(30)),
				new SupportAgentProperties.Llm("test-key", "https://api.groq.com/openai/v1",
						"openai/gpt-oss-20b", Duration.ofSeconds(1), 0),
				new SupportAgentProperties.Agent(5, 8, 2, 6));
	}
}
