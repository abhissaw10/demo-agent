package com.example.demo_agent.tracing;

import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;

public record AgentRunContext(UUID runId, String sessionId, String traceId, String rootSpanId,
		Instant startedAt, String agentVersion, String promptVersion) {

	public static AgentRunContext start(String sessionId, String agentVersion, String promptVersion) {
		return new AgentRunContext(UUID.randomUUID(), sessionId,
				valueOrGenerated(MDC.get("supportTraceId"), CorrelationIds::newTraceId),
				valueOrGenerated(MDC.get("supportSpanId"), CorrelationIds::newSpanId),
				Instant.now(), agentVersion, promptVersion);
	}

	private static String valueOrGenerated(String value, java.util.function.Supplier<String> generator) {
		return value == null || value.isBlank() ? generator.get() : value;
	}
}
