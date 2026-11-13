package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.tracing.AgentRunContext;

record DeliveryToolExecutionContext(DeliveryInvestigationRequest request, AgentRunContext agentRun,
		int agentIteration, int completedToolCalls, String originatingLlmCallId,
		String originatingLlmSpanId) {
}
