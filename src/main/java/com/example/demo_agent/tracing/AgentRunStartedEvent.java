package com.example.demo_agent.tracing;

import com.example.demo_agent.api.ChatRequest;

public record AgentRunStartedEvent(AgentRunContext context, ChatRequest request) {
}
