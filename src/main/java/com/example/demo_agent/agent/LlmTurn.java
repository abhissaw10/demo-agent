package com.example.demo_agent.agent;

import java.util.List;

public record LlmTurn(List<RequestedToolCall> toolCalls, String proposedDecisionJson, int modelCalls) {

	public LlmTurn(List<RequestedToolCall> toolCalls, String proposedDecisionJson) {
		this(toolCalls, proposedDecisionJson, 1);
	}

	public boolean hasToolCalls() {
		return toolCalls != null && !toolCalls.isEmpty();
	}

	public LlmTurn withModelCalls(int calls) {
		return new LlmTurn(toolCalls, proposedDecisionJson, calls);
	}
}
