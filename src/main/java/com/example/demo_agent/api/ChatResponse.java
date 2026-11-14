package com.example.demo_agent.api;

import com.example.demo_agent.agent.support.AgentDecision;

import java.util.List;

public record ChatResponse(String message, String outcome, List<String> resolvedIntents, String orderId,
		List<String> evidence, List<String> recommendations, List<String> actionsRequiringApproval,
		int llmCalls, int toolCalls) {

	public static ChatResponse from(AgentDecision decision, int llmCalls, int toolCalls) {
		return new ChatResponse(decision.message(), decision.outcome(), decision.resolvedIntents(),
				decision.orderId(), decision.evidence(), decision.recommendations(),
				decision.actionsRequiringApproval(), llmCalls, toolCalls);
	}

	public static ChatResponse safeFailure(String message, int llmCalls, int toolCalls) {
		return new ChatResponse(message, "SAFE_FAILURE", List.of(), null, List.of(), List.of(), List.of(),
				llmCalls, toolCalls);
	}
}
