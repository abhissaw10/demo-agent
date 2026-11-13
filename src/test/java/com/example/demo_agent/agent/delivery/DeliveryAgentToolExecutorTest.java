package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.RequestedToolCall;
import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.DeliveryUpdateResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.client.DownstreamApiException;
import com.example.demo_agent.tracing.AgentRunContext;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.ToolCallEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryAgentToolExecutorTest {

	private static final DeliveryInvestigationRequest REQUEST = new DeliveryInvestigationRequest(
			"CUST-1001", "ORD-1002", DeliveryInvestigationType.CURRENT_STATUS);
	private final DeliveryAgentTools tools = mock(DeliveryAgentTools.class);
	private final DeliveryAgentToolExecutor executor = new DeliveryAgentToolExecutor(tools);

	@Test
	void deliveryToolsUseOnlyTheImmutableInvestigationRequest() {
		LocalDateTime time = LocalDateTime.of(2026, 10, 21, 12, 0);
		when(tools.getCustomerOrders("CUST-1001")).thenReturn(List.of(new CustomerOrderResponse(
				"CUST-1001", "ORD-1002", time,
				new OrderStatusResponse("ORDER_PLACED", "Placed", "Accepted", time, false))));
		DeliveryUpdateResponse update = new DeliveryUpdateResponse("IN_TRANSIT", "In transit",
				"Moving", "Checkpoint", time, true, false);
		when(tools.getDeliveryStatus("ORD-1002"))
				.thenReturn(new OrderDeliveryStatusResponse("ORD-1002", update, List.of(update)));

		List<ExecutedToolCall> access = executor.execute(List.of(new RequestedToolCall(
				"access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS, "{}")), context(1, 0));
		List<ExecutedToolCall> status = executor.execute(List.of(new RequestedToolCall(
				"status", DeliveryAgentToolExecutor.GET_DELIVERY_STATUS, "{}")), context(2, 1));

		assertThat(access.getFirst().output()).isInstanceOfSatisfying(
				DeliveryAgentToolExecutor.OrderAccessFacts.class,
				facts -> assertThat(facts.outcome()).isEqualTo(DeliveryInvestigationOutcome.COMPLETED));
		assertThat(status.getFirst().output()).isInstanceOfSatisfying(
				DeliveryAgentToolExecutor.DeliveryStatusFacts.class,
				facts -> assertThat(facts.statusCode()).isEqualTo("IN_TRANSIT"));
	}

	@Test
	void mapsAResourceFailureToStructuredFactsForTheLlm() {
		when(tools.getCustomerOrders("CUST-1001")).thenThrow(new DownstreamApiException(
				"DEPENDENCY_UNAVAILABLE", "down", false, true, null));

		ExecutedToolCall result = executor.execute(List.of(new RequestedToolCall(
				"access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS, "{}")), context(1, 0)).getFirst();

		assertThat(result.output()).isInstanceOfSatisfying(
				DeliveryAgentToolExecutor.OrderAccessFacts.class,
				facts -> assertThat(facts.outcome())
						.isEqualTo(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE));
	}

	@Test
	void publishesPairedToolEventsAndRestoresTheCallingSpan() {
		LocalDateTime time = LocalDateTime.of(2026, 10, 21, 12, 0);
		when(tools.getCustomerOrders("CUST-1001")).thenReturn(List.of(new CustomerOrderResponse(
				"CUST-1001", "ORD-1002", time,
				new OrderStatusResponse("ORDER_PLACED", "Placed", "Accepted", time, false))));
		CapturingPublisher publisher = new CapturingPublisher();
		DeliveryAgentToolExecutor tracedExecutor = new DeliveryAgentToolExecutor(tools, publisher);
		MDC.put("supportSpanId", "delivery-root");
		try {
			tracedExecutor.execute(List.of(new RequestedToolCall(
					"access", DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS, "{}")), context(2, 3));

			assertThat(MDC.get("supportSpanId")).isEqualTo("delivery-root");
		} finally {
			MDC.clear();
		}
		assertThat(publisher.events).hasSize(2);
		assertThat(publisher.events).extracting(ToolCallEvent::status)
				.containsExactly(ToolCallEvent.Status.STARTED, ToolCallEvent.Status.COMPLETED);
		assertThat(publisher.events).allSatisfy(event -> {
			assertThat(event.agentVersion()).isEqualTo("delivery-agent-v1");
			assertThat(event.sessionId()).isEqualTo("session-1");
			assertThat(event.traceId()).isEqualTo("trace-1");
			assertThat(event.parentSpanId()).isEqualTo("delivery-root");
			assertThat(event.agentIteration()).isEqualTo(2);
			assertThat(event.toolOrdinal()).isEqualTo(4);
			assertThat(event.originatingLlmCallId()).isEqualTo("llm-call-2");
			assertThat(event.originatingLlmSpanId()).isEqualTo("llm-span-2");
		});
		assertThat(publisher.events.getLast().success()).isTrue();
	}

	private DeliveryToolExecutionContext context(int iteration, int completedToolCalls) {
		AgentRunContext run = new AgentRunContext(UUID.randomUUID(), "session-1", "trace-1",
				"delivery-root", Instant.now(), "delivery-agent-v1", "delivery-prompt-v1");
		return new DeliveryToolExecutionContext(REQUEST, run, iteration, completedToolCalls,
				"llm-call-" + iteration, "llm-span-" + iteration);
	}

	private static final class CapturingPublisher implements AgentEventPublisher {
		private final List<ToolCallEvent> events = new ArrayList<>();

		@Override
		public void publishToolCall(ToolCallEvent event) {
			events.add(event);
		}
	}
}
