package com.example.demo_agent.tool;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.delivery.DeliveryAgent;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationType;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Support-agent adapter for the DeliveryAgent contract. */
@Component
public class InvestigateDeliveryTool {

	public static final String NAME = "investigate_delivery";

	private final DeliveryAgent deliveryAgent;

	public InvestigateDeliveryTool(DeliveryAgent deliveryAgent) {
		this.deliveryAgent = deliveryAgent;
	}

	public DeliveryInvestigationResult execute(Arguments arguments, ToolExecutionContext context) {
		if (arguments == null || arguments.orderId == null || arguments.orderId.isBlank()) {
			throw new ToolValidationException("orderId is required.");
		}
		if (arguments.investigationType == null) {
			throw new ToolValidationException("investigationType is required.");
		}
		String parentSpanId = MDC.get("supportSpanId");
		if (parentSpanId == null || parentSpanId.isBlank()) {
			parentSpanId = context.originatingLlmSpanId();
		}
		AgentInvocationContext invocationContext = new AgentInvocationContext(
				context.agentRun().runId(), context.agentRun().sessionId(), context.agentRun().traceId(),
				parentSpanId, Instant.now());
		return deliveryAgent.investigate(new DeliveryInvestigationRequest(
				context.customerId(), arguments.orderId.trim(), arguments.investigationType), invocationContext);
	}

	@JsonClassDescription("Ask the Delivery Agent to investigate one owned order. Returns structured facts; the Support Agent owns customer-facing wording.")
	public static final class Arguments {
		@JsonPropertyDescription("Exact order ID selected from the current customer's owned orders.")
		public String orderId;

		@JsonPropertyDescription("Investigation goal: CURRENT_STATUS, DELAY_EXPLANATION, or DELIVERY_PROBLEM.")
		public DeliveryInvestigationType investigationType;
	}
}
