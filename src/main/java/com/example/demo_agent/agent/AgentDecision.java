package com.example.demo_agent.agent;

import java.util.List;

public record AgentDecision(String message, String outcome, List<String> resolvedIntents, String orderId,
		List<String> evidence, List<String> recommendations, List<String> actionsRequiringApproval) {
}
