package com.example.demo_agent.tool;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.delivery.DeliveryAgent;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationOutcome;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationType;
import com.example.demo_agent.tracing.AgentRunContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigateDeliveryToolTest {

	@Test
	void convertsSupportToolInputToTheNarrowAgentContract() {
		DeliveryAgent deliveryAgent = mock(DeliveryAgent.class);
		DeliveryInvestigationResult expected = new DeliveryInvestigationResult(
				DeliveryInvestigationOutcome.COMPLETED, "ORD-1002", "IN_TRANSIT", "In transit", List.of());
		when(deliveryAgent.investigate(any(), any())).thenReturn(expected);
		InvestigateDeliveryTool tool = new InvestigateDeliveryTool(deliveryAgent);
		InvestigateDeliveryTool.Arguments arguments = new InvestigateDeliveryTool.Arguments();
		arguments.orderId = " ORD-1002 ";
		arguments.investigationType = DeliveryInvestigationType.CURRENT_STATUS;
		AgentRunContext run = AgentRunContext.start("session-1", "support-v1", "prompt-v1");

		DeliveryInvestigationResult actual;
		MDC.put("supportSpanId", "support-tool-span");
		try {
			actual = tool.execute(arguments,
					new ToolExecutionContext("CUST-1001", run, 2, 1, "llm-1", "span-1"));
		} finally {
			MDC.clear();
		}

		ArgumentCaptor<DeliveryInvestigationRequest> request =
				ArgumentCaptor.forClass(DeliveryInvestigationRequest.class);
		ArgumentCaptor<AgentInvocationContext> invocation =
				ArgumentCaptor.forClass(AgentInvocationContext.class);
		verify(deliveryAgent).investigate(request.capture(), invocation.capture());
		assertThat(request.getValue()).isEqualTo(new DeliveryInvestigationRequest(
				"CUST-1001", "ORD-1002", DeliveryInvestigationType.CURRENT_STATUS));
		assertThat(invocation.getValue().parentRunId()).isEqualTo(run.runId());
		assertThat(invocation.getValue().sessionId()).isEqualTo(run.sessionId());
		assertThat(invocation.getValue().traceId()).isEqualTo(run.traceId());
		assertThat(invocation.getValue().parentSpanId()).isEqualTo("support-tool-span");
		assertThat(actual).isSameAs(expected);
	}
}
