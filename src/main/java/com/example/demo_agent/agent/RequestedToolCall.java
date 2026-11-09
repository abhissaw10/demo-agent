package com.example.demo_agent.agent;

public record RequestedToolCall(String callId, String name, String argumentsJson) {
}
