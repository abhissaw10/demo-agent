package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.RequestedToolCall;

import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import com.example.demo_agent.tool.ToolExecutionContext;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.AgentRunContext;
import com.example.demo_agent.tracing.ToolCallEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportToolExecutorTracingTest {

	@Test
	void restoresTheParentSpanAfterASequentialToolCall() {
		GetRefundPolicyTool refundPolicy = mock(GetRefundPolicyTool.class);
		when(refundPolicy.execute(any(), any()))
				.thenReturn(new GetRefundPolicyTool.PolicyToolResult("refund", "refund policy"));
		SupportToolExecutor executor = new SupportToolExecutor(mock(GetCustomerOrdersTool.class),
				refundPolicy, mock(GetExchangePolicyTool.class),
				new CapturingPublisher());
		AgentRunContext run = new AgentRunContext(UUID.randomUUID(), "session-1", "trace-1",
				"root-span", Instant.now(), "test-agent", "test-prompt");
		MDC.put("supportSpanId", "root-span");
		try {
			executor.execute(List.of(new RequestedToolCall(
					"refund-call", GetRefundPolicyTool.NAME, "{}")),
					new ToolExecutionContext("CUST-1001", run, 1, 0));

			assertThat(MDC.get("supportSpanId")).isEqualTo("root-span");
		} finally {
			MDC.remove("supportSpanId");
		}
	}

	@Test
	void givesEveryToolCallAUniqueChildSpanAndPairedLifecycleEvents() {
		GetCustomerOrdersTool customerOrders = mock(GetCustomerOrdersTool.class);
		GetRefundPolicyTool refundPolicy = mock(GetRefundPolicyTool.class);
		GetExchangePolicyTool exchangePolicy = mock(GetExchangePolicyTool.class);
		List<String> spansObservedInsideTools = new CopyOnWriteArrayList<>();
		when(refundPolicy.execute(any(), any())).thenAnswer(invocation -> {
			spansObservedInsideTools.add(MDC.get("supportSpanId"));
			return new GetRefundPolicyTool.PolicyToolResult("refund", "refund policy");
		});
		when(exchangePolicy.execute(any(), any())).thenAnswer(invocation -> {
			spansObservedInsideTools.add(MDC.get("supportSpanId"));
			return new GetExchangePolicyTool.PolicyToolResult("exchange", "exchange policy");
		});
		CapturingPublisher publisher = new CapturingPublisher();
		SupportToolExecutor executor = new SupportToolExecutor(customerOrders,
				refundPolicy, exchangePolicy, publisher);
		AgentRunContext run = new AgentRunContext(UUID.randomUUID(), "session-1", "trace-1",
				"root-span", Instant.now(), "test-agent", "test-prompt");

		List<ExecutedToolCall> results = executor.execute(List.of(
				new RequestedToolCall("refund-call", GetRefundPolicyTool.NAME, "{}"),
				new RequestedToolCall("exchange-call", GetExchangePolicyTool.NAME, "{}")),
				new ToolExecutionContext("CUST-1001", run, 2, 3,
						"llm-call-2", "llm-span-2"));

		assertThat(results).allMatch(ExecutedToolCall::success);
		assertThat(publisher.events).hasSize(4);
		assertThat(publisher.events).allSatisfy(event -> {
			assertThat(event.sessionId()).isEqualTo("session-1");
			assertThat(event.traceId()).isEqualTo("trace-1");
			assertThat(event.parentSpanId()).isEqualTo("root-span");
			assertThat(event.agentIteration()).isEqualTo(2);
			assertThat(event.spanId()).matches("[0-9a-f]{16}");
			assertThat(event.originatingLlmCallId()).isEqualTo("llm-call-2");
			assertThat(event.originatingLlmSpanId()).isEqualTo("llm-span-2");
		});
		assertThat(publisher.events.stream().map(ToolCallEvent::spanId).distinct()).hasSize(2);
		assertThat(publisher.events.stream().map(ToolCallEvent::toolOrdinal).distinct())
				.containsExactlyInAnyOrder(4, 5);
		assertThat(spansObservedInsideTools).containsExactlyInAnyOrderElementsOf(
				publisher.events.stream().map(ToolCallEvent::spanId).distinct().toList());

		for (String callId : List.of("refund-call", "exchange-call")) {
			List<ToolCallEvent> callEvents = publisher.events.stream()
					.filter(event -> event.toolCallId().equals(callId)).toList();
			assertThat(callEvents).extracting(ToolCallEvent::status)
					.containsExactly(ToolCallEvent.Status.STARTED, ToolCallEvent.Status.COMPLETED);
			assertThat(callEvents).extracting(ToolCallEvent::spanId).containsOnly(callEvents.getFirst().spanId());
			assertThat(callEvents.getLast().success()).isTrue();
			assertThat(callEvents.getLast().completedAt()).isAfterOrEqualTo(callEvents.getFirst().startedAt());
		}
	}

	private static final class CapturingPublisher implements AgentEventPublisher {
		private final List<ToolCallEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void publishToolCall(ToolCallEvent event) {
			events.add(event);
		}
	}
}
