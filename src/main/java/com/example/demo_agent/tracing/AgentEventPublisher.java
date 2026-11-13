package com.example.demo_agent.tracing;

public interface AgentEventPublisher {
    default void publishStarted(AgentRunStartedEvent event) {
    }

    default void publishCompleted(AgentRunCompletedEvent event) {
    }

    default void publishFailed(AgentRunFailedEvent event) {
    }

    default void publishDelegatedStarted(DelegatedAgentRunStartedEvent event) {
    }

    default void publishDelegatedCompleted(DelegatedAgentRunCompletedEvent event) {
    }

    default void publishDelegatedFailed(DelegatedAgentRunFailedEvent event) {
    }

    default void publishToolCall(ToolCallEvent event) {
    }

    default void publishLlmCall(LlmCallEvent event) {
    }

    default void publishResourceCall(ResourceCallEvent event) {
    }
}
