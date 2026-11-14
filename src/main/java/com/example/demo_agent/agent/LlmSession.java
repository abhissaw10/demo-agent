package com.example.demo_agent.agent;

import java.util.List;

public interface LlmSession {

	LlmTurn next();

	void submitToolResults(List<ExecutedToolCall> results);

	default void submitDelegatedAgentResult(String agentName, Object result) {
		throw new UnsupportedOperationException("This LLM session does not accept delegated agent results.");
	}

	void submitValidationFailure(String failure);
}
