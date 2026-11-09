package com.example.demo_agent.agent;

public record ExecutedToolCall(String callId, String name, Object output, boolean success,
		String validationFingerprint) {
}
