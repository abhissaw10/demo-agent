package com.example.demo_agent.agent;

import java.util.List;

public interface LlmSession {

	LlmTurn next();

	void submitToolResults(List<ExecutedToolCall> results);

	void submitValidationFailure(String failure);
}
