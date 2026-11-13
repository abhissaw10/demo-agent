package com.example.demo_agent.tracing;

import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;

import java.util.UUID;

/** Starts an agent run nested below a tool call in another agent. */
public record DelegatedAgentRunStartedEvent(AgentRunContext context, UUID parentRunId,
		String parentSpanId, DeliveryInvestigationRequest request) {
}
