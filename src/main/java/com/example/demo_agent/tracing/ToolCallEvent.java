package com.example.demo_agent.tracing;

import java.time.Instant;
import java.util.UUID;

public record ToolCallEvent(UUID runId, String sessionId, String traceId, String spanId,
		String parentSpanId, int agentIteration, String toolCallId, String toolName,
		int toolOrdinal, String originatingLlmCallId, String originatingLlmSpanId,
		String sanitizedArguments, Instant startedAt, Instant completedAt, Status status,
		Boolean success, String errorCode) {

	public enum Status {
		STARTED,
		COMPLETED
	}

	public static ToolCallEvent started(AgentRunContext context, String spanId, int agentIteration,
			String toolCallId, String toolName, int toolOrdinal, String sanitizedArguments,
			String originatingLlmCallId, String originatingLlmSpanId, Instant startedAt) {
		return new ToolCallEvent(context.runId(), context.sessionId(), context.traceId(), spanId,
				context.rootSpanId(), agentIteration, toolCallId, toolName, toolOrdinal,
				originatingLlmCallId, originatingLlmSpanId, sanitizedArguments, startedAt,
				null, Status.STARTED, null, null);
	}

	public ToolCallEvent completed(Instant completedAt, boolean success, String errorCode) {
		return new ToolCallEvent(runId, sessionId, traceId, spanId, parentSpanId, agentIteration,
				toolCallId, toolName, toolOrdinal, originatingLlmCallId, originatingLlmSpanId,
				sanitizedArguments, startedAt, completedAt, Status.COMPLETED, success, errorCode);
	}
}
