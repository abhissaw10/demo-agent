package com.example.demo_agent.tracing;

import com.example.demo_agent.api.ChatRequest;

import java.time.Instant;

public record AgentRunFailedEvent(AgentRunContext context, ChatRequest request, Instant failedAt,
		long durationMs, String errorType, String errorMessage) {
}
