package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.LlmConfigurationException;
import com.example.demo_agent.agent.LlmSession;
import com.example.demo_agent.agent.LlmTurn;
import com.example.demo_agent.agent.RequestedToolCall;

import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationOutcome;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.client.DeliveryAgentApiClient;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.AgentRunCompletedEvent;
import com.example.demo_agent.tracing.AgentRunFailedEvent;
import com.example.demo_agent.tracing.AgentRunStartedEvent;
import com.example.demo_agent.tracing.LlmCallEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportAgentServiceTest {

	@Test
	void runsToolLoopAndReturnsValidatedDecision() {
		RequestedToolCall requested = new RequestedToolCall("call-1", GetCustomerOrdersTool.NAME,
				"{\"customerId\":\"CUST-1001\"}");
		FakeSession session = new FakeSession(
				new LlmTurn(List.of(requested), null),
				new LlmTurn(List.of(), """
						{"message":"You have two verified orders: ORD-1001 and ORD-1002.","outcome":"ANSWERED",
						"resolvedIntents":["list orders"],"orderId":null,
						"evidence":["The order service returned ORD-1001 and ORD-1002."],
						"recommendations":[],"actionsRequiringApproval":[]}
						"""));
		LlmSessionFactory factory = (history, customerId, message) -> session;
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		List<CustomerOrderResponse> orders = List.of(order("ORD-1001"), order("ORD-1002"));
		when(executor.execute(any(), any())).thenReturn(List.of(
				new ExecutedToolCall("call-1", GetCustomerOrdersTool.NAME, orders, true, null)));

		SupportAgentService service = service(factory, executor);
		ChatResponse response = service.chat(new ChatRequest("Show my orders", "CUST-1001"), "session-1");

		assertThat(response.outcome()).isEqualTo("ANSWERED");
		assertThat(response.llmCalls()).isEqualTo(2);
		assertThat(response.toolCalls()).isEqualTo(1);
		assertThat(response.evidence()).contains(
				"get_customer_orders verified these owned orders: "
						+ "ORD-1001 (ORDER_PLACED), ORD-1002 (ORDER_PLACED).");
		assertThat(session.submittedResults).hasSize(1);
	}

	@Test
	void stopsAfterRepeatedIdenticalToolValidationFailure() {
		RequestedToolCall first = new RequestedToolCall("call-1", GetCustomerOrdersTool.NAME, "{}");
		RequestedToolCall second = new RequestedToolCall("call-2", GetCustomerOrdersTool.NAME, "{}");
		FakeSession session = new FakeSession(new LlmTurn(List.of(first), null), new LlmTurn(List.of(second), null));
		LlmSessionFactory factory = (history, customerId, message) -> session;
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		when(executor.execute(any(), any()))
				.thenReturn(List.of(new ExecutedToolCall("call-1", GetCustomerOrdersTool.NAME,
						"invalid", false, "same-failure")))
				.thenReturn(List.of(new ExecutedToolCall("call-2", GetCustomerOrdersTool.NAME,
						"invalid", false, "same-failure")));

		ChatResponse response = service(factory, executor)
				.chat(new ChatRequest("Show my orders", "CUST-1001"), "session-1");

		assertThat(response.outcome()).isEqualTo("SAFE_FAILURE");
		assertThat(response.message()).contains("repeated an invalid tool request");
		assertThat(response.llmCalls()).isEqualTo(2);
		assertThat(response.toolCalls()).isEqualTo(2);
	}

	@Test
	void failsSafelyWhenToolSchemaInitializationFails() {
		LlmSessionFactory factory = (history, customerId, message) -> {
			throw new IllegalArgumentException("invalid tool schema");
		};

		ChatResponse response = service(factory, mock(SupportToolExecutor.class))
				.chat(new ChatRequest("What is the refund policy?", "CUST-1001"), "session-1");

		assertThat(response.outcome()).isEqualTo("SAFE_FAILURE");
		assertThat(response.message()).contains("initialize the support model");
		assertThat(response.llmCalls()).isZero();
		assertThat(response.toolCalls()).isZero();
	}

	@Test
	void suppliesAcceptedConversationHistoryToTheNextChatTurn() {
		List<List<ConversationTurn>> observedHistories = new ArrayList<>();
		RequestedToolCall refundCall = new RequestedToolCall("call-refund", GetRefundPolicyTool.NAME, "{}");
		LlmSessionFactory factory = (history, customerId, message) -> {
			observedHistories.add(List.copyOf(history));
			if (history.isEmpty()) {
				return new FakeSession(new LlmTurn(List.of(), """
						{"message":"I’ll use ORD-1002 for this chat.","outcome":"ANSWERED",
						"resolvedIntents":["select order"],"orderId":"ORD-1002",
						"evidence":["The customer named ORD-1002."],"recommendations":[],"actionsRequiringApproval":[]}
						"""));
			}
			return new FakeSession(new LlmTurn(List.of(refundCall), null), new LlmTurn(List.of(), """
					{"message":"No. ORD-1002 is still within the policy SLA, so it is not yet eligible for cancellation and a refund.",
					"outcome":"ANSWERED","resolvedIntents":["refund eligibility"],"orderId":"ORD-1002",
					"evidence":["The policy SLA has not passed."],"recommendations":["Wait for the SLA to pass."],
					"actionsRequiringApproval":["Cancellation and refund"]}
					"""));
		};
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		when(executor.execute(any(), any())).thenReturn(List.of(new ExecutedToolCall(
				"call-refund", GetRefundPolicyTool.NAME, "verified refund policy", true, null)));
		SupportAgentService service = service(factory, executor);

		service.chat(new ChatRequest("Use order ORD-1002", "CUST-1001"), "session-1");
		ChatResponse followUp = service.chat(
				new ChatRequest("Can I cancel it and get a refund?", "CUST-1001"), "session-1");

		assertThat(observedHistories).hasSize(2);
		assertThat(observedHistories.get(1)).containsExactly(new ConversationTurn(
				"Use order ORD-1002", "I’ll use ORD-1002 for this chat."));
		assertThat(followUp.message()).startsWith("No.");
	}

	@Test
	void delegatesToDeliveryOverHttpWithoutAModelFunctionCall() {
		List<List<ConversationTurn>> observedHistories = new ArrayList<>();
		RequestedToolCall ownership = new RequestedToolCall("call-orders", GetCustomerOrdersTool.NAME,
				"{\"customerId\":\"CUST-1001\"}");
		LlmSessionFactory factory = (history, customerId, message) -> {
			observedHistories.add(List.copyOf(history));
			if (history.isEmpty()) {
				return new FakeSession(new LlmTurn(List.of(ownership), null),
						new LlmTurn(List.of(), """
						{"message":"ORD-1001 is being prepared.","outcome":"ANSWERED",
						"resolvedIntents":["delivery status"],"orderId":"ORD-1001",
						"evidence":["The Delivery Investigator verified the status."],
						"recommendations":[],"actionsRequiringApproval":[]}
						"""));
			}
			return new FakeSession(new LlmTurn(List.of(), """
					{"message":"We discussed ORD-1001.","outcome":"ANSWERED",
					"resolvedIntents":["context recall"],"orderId":"ORD-1001",
					"evidence":["ORD-1001 appears in the verified conversation context."],
					"recommendations":[],"actionsRequiringApproval":[]}
					"""));
		};
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		when(executor.execute(any(), any())).thenReturn(List.of(new ExecutedToolCall(
				"call-orders", GetCustomerOrdersTool.NAME, List.of(order("ORD-1001")), true, null)));
		DeliveryAgentApiClient deliveryClient = mock(DeliveryAgentApiClient.class);
		DeliveryInvestigationResult deliveryResult = new DeliveryInvestigationResult(
				DeliveryInvestigationOutcome.COMPLETED, "ORD-1001", "PREPARING",
				"The order is being prepared.", List.of());
		when(deliveryClient.investigate(any(), any(), anyInt())).thenReturn(deliveryResult);
		SupportAgentService service = service(factory, executor, deliveryClient);

		ChatResponse first = service.chat(
				new ChatRequest("Where is ORD-1001?", "CUST-1001"), "session-evidence");
		ChatResponse followUp = service.chat(new ChatRequest(
				"Which order did we just discuss?", "CUST-1001"), "session-evidence");

		assertThat(first.toolCalls()).isEqualTo(1);
		assertThat(first.evidence()).contains("Delivery Agent returned COMPLETED for ORD-1001 with status PREPARING.");
		assertThat(followUp.toolCalls()).isZero();
		assertThat(observedHistories.get(1)).singleElement().satisfies(turn ->
				assertThat(turn.verifiedEvidence()).extracting(ExecutedToolCall::name)
						.containsExactly(GetCustomerOrdersTool.NAME));
		verify(executor, times(1)).execute(any(), any());
		verify(deliveryClient, times(1)).investigate(any(DeliveryInvestigationRequest.class), any(), anyInt());
	}

	@Test
	void contextualRefundUsesSeededOrderDateAndDelegatesWithoutAskingTheCustomer() {
		RequestedToolCall ownership = new RequestedToolCall("call-orders", GetCustomerOrdersTool.NAME,
				"{\"customerId\":\"CUST-1003\"}");
		RequestedToolCall refundPolicy = new RequestedToolCall("call-refund",
				GetRefundPolicyTool.NAME, "{}");
		int[] turn = {0};
		LlmSessionFactory factory = (history, customerId, message) -> {
			if (turn[0]++ == 0) {
				return new FakeSession(new LlmTurn(List.of(ownership), null), new LlmTurn(List.of(), """
						{"message":"ORD-1005 is lost in transit.","outcome":"ANSWERED",
						"resolvedIntents":["delivery status"],"orderId":"ORD-1005",
						"evidence":["The Delivery Agent verified the status."],
						"recommendations":[],"actionsRequiringApproval":[]}
						"""));
			}
			return new FakeSession(new LlmTurn(List.of(refundPolicy), null), new LlmTurn(List.of(), """
					{"message":"ORD-1005 was placed on 18 October and is eligible to apply for cancellation and a refund; I cannot execute those actions.",
					"outcome":"ANSWERED","resolvedIntents":["refund eligibility"],"orderId":"ORD-1005",
					"evidence":["The verified order date and refund policy satisfy the eligibility rule."],
					"recommendations":["Submit the request for human approval."],
					"actionsRequiringApproval":["Cancellation and refund"]}
					"""));
		};
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		LocalDateTime placedAt = LocalDateTime.of(2026, 10, 18, 8, 0);
		CustomerOrderResponse lostOrder = new CustomerOrderResponse("CUST-1003", "ORD-1005", placedAt,
				new OrderStatusResponse("LOST_IN_TRANSIT", "Lost in transit", "Lost",
						placedAt.plusDays(3), true));
		when(executor.execute(any(), any()))
				.thenReturn(List.of(new ExecutedToolCall("call-orders", GetCustomerOrdersTool.NAME,
						List.of(lostOrder), true, null)))
				.thenReturn(List.of(new ExecutedToolCall("call-refund", GetRefundPolicyTool.NAME,
						"Orders over 14 days without delivery may apply for cancellation and refund.", true, null)));
		DeliveryAgentApiClient deliveryClient = mock(DeliveryAgentApiClient.class);
		when(deliveryClient.investigate(any(), any(), anyInt())).thenReturn(
				new DeliveryInvestigationResult(DeliveryInvestigationOutcome.COMPLETED,
						"ORD-1005", "LOST_IN_TRANSIT", "The shipment is lost.", List.of()));
		SupportAgentService service = service(factory, executor, deliveryClient);

		service.chat(new ChatRequest("What is the status of ORD-1005?", "CUST-1003"), "session-refund");
		ChatResponse response = service.chat(new ChatRequest(
				"Cancel the order and issue me a refund", "CUST-1003"), "session-refund");

		assertThat(response.outcome()).isEqualTo("ANSWERED");
		assertThat(response.message()).contains("18 October").doesNotContain("when");
		assertThat(response.toolCalls()).isEqualTo(1);
		verify(deliveryClient, times(2)).investigate(any(DeliveryInvestigationRequest.class), any(), anyInt());
	}

	@Test
	void publishesPairedRunEventsForAHandledSafeFailure() {
		LlmSessionFactory factory = (history, customerId, message) -> {
			throw new LlmConfigurationException("not configured");
		};
		CapturingPublisher publisher = new CapturingPublisher();
		SupportAgentProperties properties = properties();
		SupportAgentService service = new SupportAgentService(factory, mock(SupportToolExecutor.class),
				new AgentDecisionValidator(), new ConversationMemoryStore(properties), properties, publisher);

		ChatResponse response = service.chat(
				new ChatRequest("Where is ORD-1001?", "CUST-1001"), "session-1");

		assertThat(response.outcome()).isEqualTo("SAFE_FAILURE");
		assertThat(publisher.started).hasSize(1);
		assertThat(publisher.completed).hasSize(1);
		assertThat(publisher.failed).isEmpty();
		assertThat(publisher.completed.getFirst().context().runId())
				.isEqualTo(publisher.started.getFirst().context().runId());
		assertThat(publisher.completed.getFirst().response()).isEqualTo(response);
	}

	@Test
	void publishesPairedLlmEventsWithTheActualProviderCallCount() {
		FakeSession session = new FakeSession(new LlmTurn(List.of(), """
				{"message":"The request could not be completed safely.","outcome":"SAFE_FAILURE",
				"resolvedIntents":[],"orderId":null,"evidence":[],"recommendations":[],
				"actionsRequiringApproval":[]}
				""", 2));
		LlmSessionFactory factory = (history, customerId, message) -> session;
		CapturingPublisher publisher = new CapturingPublisher();
		SupportAgentProperties properties = properties();
		SupportAgentService service = new SupportAgentService(factory, mock(SupportToolExecutor.class),
				new AgentDecisionValidator(), new ConversationMemoryStore(properties), properties, publisher);

		ChatResponse response = service.chat(
				new ChatRequest("Help with my account", "CUST-1001"), "session-llm-events");

		assertThat(response.llmCalls()).isEqualTo(2);
		assertThat(publisher.llmCalls).hasSize(2);
		assertThat(publisher.llmCalls).extracting(LlmCallEvent::status)
				.containsExactly(LlmCallEvent.Status.STARTED, LlmCallEvent.Status.COMPLETED);
		LlmCallEvent started = publisher.llmCalls.getFirst();
		LlmCallEvent completed = publisher.llmCalls.getLast();
		assertThat(completed.llmCallId()).isEqualTo(started.llmCallId());
		assertThat(completed.spanId()).isEqualTo(started.spanId());
		assertThat(completed.parentSpanId()).isEqualTo(publisher.started.getFirst().context().rootSpanId());
		assertThat(completed.providerCallCount()).isEqualTo(2);
		assertThat(completed.success()).isTrue();
	}

	@Test
	void noOrdersPreventsEveryToolAfterCustomerOrders() {
		RequestedToolCall ownership = new RequestedToolCall("call-orders", GetCustomerOrdersTool.NAME,
				"{\"customerId\":\"CUST-404\"}");
		FakeSession session = new FakeSession(new LlmTurn(List.of(ownership), null));
		LlmSessionFactory factory = (history, customerId, message) -> session;
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		when(executor.execute(any(), any())).thenReturn(List.of(new ExecutedToolCall(
				"call-orders", GetCustomerOrdersTool.NAME, List.of(), true, null)));

		ChatResponse response = service(factory, executor)
				.chat(new ChatRequest("Where is ORD-404?", "CUST-404"), "session-1");

		assertThat(response.outcome()).isEqualTo("ANSWERED");
		assertThat(response.llmCalls()).isEqualTo(1);
		assertThat(response.toolCalls()).isEqualTo(1);
		verify(executor, times(1)).execute(any(), any());
	}

	@Test
	void onlyFulfilledOrdersPreventsRefundPolicyTool() {
		RequestedToolCall ownership = new RequestedToolCall("call-orders", GetCustomerOrdersTool.NAME,
				"{\"customerId\":\"CUST-1001\"}");
		RequestedToolCall unnecessaryRefund = new RequestedToolCall("call-refund", GetRefundPolicyTool.NAME, "{}");
		FakeSession session = new FakeSession(
				new LlmTurn(List.of(ownership), null),
				new LlmTurn(List.of(unnecessaryRefund), null),
				new LlmTurn(List.of(), """
						{"message":"There are no unfulfilled orders requiring a refund-policy check.",
						"outcome":"CLARIFICATION_REQUIRED","resolvedIntents":["refund policy"],
						"orderId":null,"evidence":[],"recommendations":[],"actionsRequiringApproval":[]}
						"""));
		LlmSessionFactory factory = (history, customerId, message) -> session;
		SupportToolExecutor executor = mock(SupportToolExecutor.class);
		when(executor.execute(any(), any())).thenReturn(List.of(new ExecutedToolCall(
				"call-orders", GetCustomerOrdersTool.NAME, List.of(deliveredOrder("ORD-1001")), true, null)));

		ChatResponse response = service(factory, executor)
				.chat(new ChatRequest("What is the refund policy?", "CUST-1001"), "session-1");

		assertThat(response.outcome()).isEqualTo("ANSWERED");
		assertThat(response.llmCalls()).isEqualTo(1);
		assertThat(response.toolCalls()).isEqualTo(1);
		verify(executor, times(1)).execute(any(), any());
	}

	private SupportAgentService service(LlmSessionFactory factory, SupportToolExecutor executor) {
		return service(factory, executor, mock(DeliveryAgentApiClient.class));
	}

	private SupportAgentService service(LlmSessionFactory factory, SupportToolExecutor executor,
			DeliveryAgentApiClient deliveryAgentClient) {
		SupportAgentProperties properties = properties();
		return new SupportAgentService(factory, executor, new AgentDecisionValidator(),
				new ConversationMemoryStore(properties), properties, deliveryAgentClient,
				new AgentEventPublisher() { });
	}

	private SupportAgentProperties properties() {
		return new SupportAgentProperties(
				new SupportAgentProperties.OrderApi("http://localhost:8080", Duration.ofSeconds(1), Duration.ofSeconds(1)),
				new SupportAgentProperties.ChatApi("http://localhost:8080",
						Duration.ofSeconds(1), Duration.ofSeconds(30)),
				new SupportAgentProperties.Llm("test", "http://localhost", "test-model", Duration.ofSeconds(1), 0),
				new SupportAgentProperties.Agent(5, 8, 2, 6));
	}

	private CustomerOrderResponse order(String id) {
		LocalDateTime date = LocalDateTime.of(2026, 10, 25, 9, 30);
		return new CustomerOrderResponse("CUST-1001", id, date,
				new OrderStatusResponse("ORDER_PLACED", "Order placed", "Accepted", date, false));
	}

	private CustomerOrderResponse deliveredOrder(String id) {
		LocalDateTime date = LocalDateTime.of(2026, 10, 25, 9, 30);
		return new CustomerOrderResponse("CUST-1001", id, date,
				new OrderStatusResponse("DELIVERED", "Delivered", "Delivered", date.plusDays(3), true));
	}

	private static final class FakeSession implements LlmSession {
		private final Queue<LlmTurn> turns;
		private final Queue<ExecutedToolCall> submittedResults = new ArrayDeque<>();
		private final List<Object> delegatedResults = new ArrayList<>();
		private final List<String> validationFailures = new ArrayList<>();

		private FakeSession(LlmTurn... turns) {
			this.turns = new ArrayDeque<>(List.of(turns));
		}

		@Override
		public LlmTurn next() {
			return turns.remove();
		}

		@Override
		public void submitToolResults(List<ExecutedToolCall> results) {
			submittedResults.addAll(results);
		}

		@Override
		public void submitDelegatedAgentResult(String agentName, Object result) {
			delegatedResults.add(result);
		}

		@Override
		public void submitValidationFailure(String failure) {
			validationFailures.add(failure);
		}
	}

	private static final class CapturingPublisher implements AgentEventPublisher {
		private final List<AgentRunStartedEvent> started = new ArrayList<>();
		private final List<AgentRunCompletedEvent> completed = new ArrayList<>();
		private final List<AgentRunFailedEvent> failed = new ArrayList<>();
		private final List<LlmCallEvent> llmCalls = new ArrayList<>();

		@Override
		public void publishStarted(AgentRunStartedEvent event) {
			started.add(event);
		}

		@Override
		public void publishCompleted(AgentRunCompletedEvent event) {
			completed.add(event);
		}

		@Override
		public void publishFailed(AgentRunFailedEvent event) {
			failed.add(event);
		}

		@Override
		public void publishLlmCall(LlmCallEvent event) {
			llmCalls.add(event);
		}
	}
}
