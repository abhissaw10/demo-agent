package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.LlmConfigurationException;
import com.example.demo_agent.agent.LlmSession;
import com.example.demo_agent.agent.LlmTurn;
import com.example.demo_agent.agent.RequestedToolCall;
import com.example.demo_agent.agent.delivery.DeliveryAgentToolExecutor.DeliveryStatusFacts;
import com.example.demo_agent.agent.delivery.DeliveryAgentToolExecutor.OrderAccessFacts;
import com.example.demo_agent.config.DeliveryAgentProperties;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.DelegatedAgentRunCompletedEvent;
import com.example.demo_agent.tracing.DelegatedAgentRunStartedEvent;
import com.example.demo_agent.tracing.LlmCallEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryAgentServiceTest {

	private static final DeliveryInvestigationRequest REQUEST = new DeliveryInvestigationRequest(
			"CUST-1001", "ORD-1002", DeliveryInvestigationType.DELAY_EXPLANATION);
	private final DeliveryAgentToolExecutor toolExecutor = mock(DeliveryAgentToolExecutor.class);

	@Test
	void runsItsOwnLlmAndToolLoopAndReturnsVerifiedStructuredFacts() {
		Instant observedAt = Instant.parse("2026-10-21T06:30:00Z");
		FakeSession session = new FakeSession(
				toolTurn("access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS),
				toolTurn("status", DeliveryAgentToolExecutor.GET_DELIVERY_STATUS),
				new LlmTurn(List.of(), """
						{"outcome":"COMPLETED","orderId":"ORD-1002",
						"statusCode":"AWAITING_INVENTORY_REFRESH",
						"summary":"The merchant is reconciling inventory before fulfillment can continue."}
						"""));
		when(toolExecutor.execute(any(), any()))
				.thenReturn(List.of(new ExecutedToolCall("access",
						DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
						new OrderAccessFacts(DeliveryInvestigationOutcome.COMPLETED,
								"ORD-1002", "Owned"), true, null)))
				.thenReturn(List.of(new ExecutedToolCall("status",
						DeliveryAgentToolExecutor.GET_DELIVERY_STATUS,
						new DeliveryStatusFacts(DeliveryInvestigationOutcome.COMPLETED,
								"ORD-1002", "AWAITING_INVENTORY_REFRESH", "Awaiting inventory refresh",
								"Waiting", "Inventory reconciliation", observedAt, false, null), true, null)));

		DeliveryInvestigationResult result = service((request, context) -> session)
				.investigate(REQUEST, context());

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.COMPLETED);
		assertThat(result.statusCode()).isEqualTo("AWAITING_INVENTORY_REFRESH");
		assertThat(result.summary()).contains("reconciling inventory");
		assertThat(result.evidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.fact()).isEqualTo("Latest status is AWAITING_INVENTORY_REFRESH");
			assertThat(evidence.source()).isEqualTo("ORDER_FULFILLMENT_API");
			assertThat(evidence.observedAt()).isEqualTo(observedAt);
		});
		assertThat(session.submittedResults).hasSize(2);
		verify(toolExecutor, times(2)).execute(any(), any());
	}

	@Test
	void stopsAfterAccessResolutionWhenOrderIsNotOwned() {
		FakeSession session = new FakeSession(
				toolTurn("access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS),
				new LlmTurn(List.of(), """
						{"outcome":"ORDER_NOT_OWNED","orderId":"ORD-1002",
						"statusCode":null,"summary":"Not owned"}
						"""));
		when(toolExecutor.execute(any(), any())).thenReturn(List.of(new ExecutedToolCall("access",
				DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
				new OrderAccessFacts(DeliveryInvestigationOutcome.ORDER_NOT_OWNED,
						"ORD-1002", "The order is not owned by the authenticated customer."), true, null)));

		DeliveryInvestigationResult result = service((request, context) -> session)
				.investigate(REQUEST, context());

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.ORDER_NOT_OWNED);
		assertThat(result.evidence()).isEmpty();
		verify(toolExecutor, times(1)).execute(any(), any());
	}

	@Test
	void rejectsAnOutOfOrderDeliveryToolBeforeExecution() {
		FakeSession session = new FakeSession(
				toolTurn("wrong", DeliveryAgentToolExecutor.GET_DELIVERY_STATUS),
				toolTurn("access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS),
				toolTurn("status", DeliveryAgentToolExecutor.GET_DELIVERY_STATUS),
				new LlmTurn(List.of(), """
						{"outcome":"COMPLETED","orderId":"ORD-1002",
						"statusCode":"IN_TRANSIT","summary":"The shipment is in transit."}
						"""));
		when(toolExecutor.execute(any(), any()))
				.thenReturn(List.of(new ExecutedToolCall("access",
						DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
						new OrderAccessFacts(DeliveryInvestigationOutcome.COMPLETED,
								"ORD-1002", "Owned"), true, null)))
				.thenReturn(List.of(new ExecutedToolCall("status",
						DeliveryAgentToolExecutor.GET_DELIVERY_STATUS,
						new DeliveryStatusFacts(DeliveryInvestigationOutcome.COMPLETED,
								"ORD-1002", "IN_TRANSIT", "In transit", "Moving", "Checkpoint",
								Instant.now(), false, null), true, null)));

		DeliveryInvestigationResult result = service((request, context) -> session)
				.investigate(REQUEST, context());

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.COMPLETED);
		assertThat(session.validationFailures).singleElement().asString()
				.contains(DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS);
		verify(toolExecutor, times(2)).execute(any(), any());
	}

	@Test
	void failsSafelyWhenTheDeliveryModelIsNotConfigured() {
		DeliveryInvestigationResult result = service((request, context) -> {
			throw new LlmConfigurationException("delivery model unavailable");
		}).investigate(REQUEST, context());

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE);
		assertThat(result.summary()).contains("temporarily unavailable");
	}

	@Test
	void publishesNestedRunAndPairedLlmEventsWithInheritedCorrelationIds() {
		Instant observedAt = Instant.parse("2026-10-21T06:30:00Z");
		FakeSession session = new FakeSession(
				toolTurn("access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS),
				toolTurn("status", DeliveryAgentToolExecutor.GET_DELIVERY_STATUS),
				new LlmTurn(List.of(), """
						{"outcome":"COMPLETED","orderId":"ORD-1002","statusCode":"IN_TRANSIT",
						"summary":"The shipment is in transit."}
						"""));
		when(toolExecutor.execute(any(), any()))
				.thenReturn(List.of(new ExecutedToolCall("access",
						DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
						new OrderAccessFacts(DeliveryInvestigationOutcome.COMPLETED,
								"ORD-1002", "Owned"), true, null)))
				.thenReturn(List.of(new ExecutedToolCall("status",
						DeliveryAgentToolExecutor.GET_DELIVERY_STATUS,
						new DeliveryStatusFacts(DeliveryInvestigationOutcome.COMPLETED,
								"ORD-1002", "IN_TRANSIT", "In transit", "Moving", "Checkpoint",
								observedAt, false, null), true, null)));
		CapturingPublisher publisher = new CapturingPublisher();
		UUID parentRunId = UUID.randomUUID();
		AgentInvocationContext invocation = new AgentInvocationContext(
				parentRunId, "session-1", "trace-1", "support-tool-span", Instant.now());
		DeliveryAgentService service = new DeliveryAgentService((request, context) -> session,
				toolExecutor, new DeliveryDecisionValidator(), new DeliveryAgentProperties(5, 2, 2),
				publisher);

		DeliveryInvestigationResult result = service.investigate(REQUEST, invocation);

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.COMPLETED);
		assertThat(publisher.started).singleElement().satisfies(event -> {
			assertThat(event.parentRunId()).isEqualTo(parentRunId);
			assertThat(event.parentSpanId()).isEqualTo("support-tool-span");
			assertThat(event.context().sessionId()).isEqualTo("session-1");
			assertThat(event.context().traceId()).isEqualTo("trace-1");
			assertThat(event.context().agentVersion()).isEqualTo("delivery-agent-v1");
			assertThat(event.context().rootSpanId()).isNotEqualTo(event.parentSpanId());
		});
		assertThat(publisher.completed).singleElement().satisfies(event -> {
			assertThat(event.context().runId()).isEqualTo(publisher.started.getFirst().context().runId());
			assertThat(event.llmCalls()).isEqualTo(3);
			assertThat(event.toolCalls()).isEqualTo(2);
		});
		assertThat(publisher.llmCalls).hasSize(6);
		assertThat(publisher.llmCalls).allSatisfy(event -> {
			assertThat(event.agentVersion()).isEqualTo("delivery-agent-v1");
			assertThat(event.sessionId()).isEqualTo("session-1");
			assertThat(event.traceId()).isEqualTo("trace-1");
			assertThat(event.parentSpanId()).isEqualTo(publisher.started.getFirst().context().rootSpanId());
		});
		assertThat(publisher.llmCalls.stream()
				.filter(event -> event.status() == LlmCallEvent.Status.COMPLETED)
				.map(LlmCallEvent::logicalCallOrdinal)).containsExactly(1, 2, 3);
	}

	private DeliveryAgentService service(DeliveryLlmSessionFactory factory) {
		return new DeliveryAgentService(factory, toolExecutor, new DeliveryDecisionValidator(),
				new DeliveryAgentProperties(5, 2, 2));
	}

	private LlmTurn toolTurn(String id, String name) {
		return new LlmTurn(List.of(new RequestedToolCall(id, name, "{}")), null);
	}

	private AgentInvocationContext context() {
		return new AgentInvocationContext(UUID.randomUUID(), "session-1", "trace-1", "span-1", Instant.now());
	}

	private static final class FakeSession implements LlmSession {
		private final Queue<LlmTurn> turns = new ArrayDeque<>();
		private final List<List<ExecutedToolCall>> submittedResults = new ArrayList<>();
		private final List<String> validationFailures = new ArrayList<>();

		private FakeSession(LlmTurn... turns) {
			this.turns.addAll(List.of(turns));
		}

		@Override
		public LlmTurn next() {
			return turns.remove();
		}

		@Override
		public void submitToolResults(List<ExecutedToolCall> results) {
			submittedResults.add(List.copyOf(results));
		}

		@Override
		public void submitValidationFailure(String failure) {
			validationFailures.add(failure);
		}
	}

	private static final class CapturingPublisher implements AgentEventPublisher {
		private final List<DelegatedAgentRunStartedEvent> started = new ArrayList<>();
		private final List<DelegatedAgentRunCompletedEvent> completed = new ArrayList<>();
		private final List<LlmCallEvent> llmCalls = new ArrayList<>();

		@Override
		public void publishDelegatedStarted(DelegatedAgentRunStartedEvent event) {
			started.add(event);
		}

		@Override
		public void publishDelegatedCompleted(DelegatedAgentRunCompletedEvent event) {
			completed.add(event);
		}

		@Override
		public void publishLlmCall(LlmCallEvent event) {
			llmCalls.add(event);
		}
	}
}
