package com.example.demo_agent.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * Correlation metadata passed across an in-process agent boundary.
 *
 * <p>This deliberately contains no support-agent conversation or model state.</p>
 */
public record AgentInvocationContext(
		UUID parentRunId,
		String sessionId,
		String traceId,
		String parentSpanId,
		Instant invokedAt) {
}
