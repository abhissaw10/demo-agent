package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.ExecutedToolCall;

import java.util.List;

public record ConversationTurn(String userMessage, String assistantMessage,
		List<ExecutedToolCall> verifiedEvidence) {

	public ConversationTurn {
		verifiedEvidence = verifiedEvidence == null ? List.of() : List.copyOf(verifiedEvidence);
	}

	public ConversationTurn(String userMessage, String assistantMessage) {
		this(userMessage, assistantMessage, List.of());
	}
}
