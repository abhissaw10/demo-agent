package com.example.demo_agent.tracing;

import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;

import java.time.Instant;

public record AgentRunCompletedEvent(AgentRunContext context, ChatRequest request, ChatResponse response,
		Instant completedAt, long durationMs) {
}
