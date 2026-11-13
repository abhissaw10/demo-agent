package com.example.demo_agent.tracing;

import java.time.Instant;
import java.util.UUID;

public record ResourceCallEvent(UUID runId, String agentVersion, String sessionId, String traceId, String spanId,
		String parentSpanId, int agentIteration, String toolCallId, String toolName,
		String resourceType, String operation, String resource, Instant startedAt,
		Instant completedAt, Status status, Boolean success, String errorCode) {

	public enum Status {
		STARTED,
		COMPLETED
	}

	public static ResourceCallEvent started(UUID runId, String agentVersion, String sessionId, String traceId,
			String spanId, String parentSpanId, int agentIteration, String toolCallId,
			String toolName, String resourceType, String operation, String resource,
			Instant startedAt) {
		return new ResourceCallEvent(runId, agentVersion, sessionId, traceId, spanId, parentSpanId,
				agentIteration, toolCallId, toolName, resourceType, operation, resource,
				startedAt, null, Status.STARTED, null, null);
	}

	public ResourceCallEvent completed(Instant completedAt, boolean success, String errorCode) {
		return new ResourceCallEvent(runId, agentVersion, sessionId, traceId, spanId, parentSpanId,
				agentIteration, toolCallId, toolName, resourceType, operation, resource,
				startedAt, completedAt, Status.COMPLETED, success, errorCode);
	}
}
