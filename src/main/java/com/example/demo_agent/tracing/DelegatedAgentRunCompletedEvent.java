package com.example.demo_agent.tracing;

import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;

import java.time.Instant;
import java.util.UUID;

public record DelegatedAgentRunCompletedEvent(AgentRunContext context, UUID parentRunId,
		String parentSpanId, DeliveryInvestigationRequest request, DeliveryInvestigationResult response,
		int llmCalls, int toolCalls, Instant completedAt, long durationMs) {
}
