package com.example.demo_agent.tool;

import com.example.demo_agent.tracing.AgentRunContext;

public record ToolExecutionContext(String customerId, AgentRunContext agentRun, int agentIteration,
		int completedToolCalls, String originatingLlmCallId, String originatingLlmSpanId) {

	public ToolExecutionContext(String customerId, AgentRunContext agentRun, int agentIteration,
			int completedToolCalls) {
		this(customerId, agentRun, agentIteration, completedToolCalls, null, null);
	}

	public ToolExecutionContext(String customerId) {
		this(customerId, AgentRunContext.start("standalone", "standalone", "standalone"),
				1, 0, null, null);
	}
}
