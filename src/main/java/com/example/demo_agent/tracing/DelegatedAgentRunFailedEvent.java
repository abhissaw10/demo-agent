package com.example.demo_agent.tracing;

import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;

import java.time.Instant;
import java.util.UUID;

public record DelegatedAgentRunFailedEvent(AgentRunContext context, UUID parentRunId,
		String parentSpanId, DeliveryInvestigationRequest request, Instant failedAt,
		long durationMs, String errorType, String errorMessage) {
}
