package com.example.demo_agent.tracing;

import com.example.demo_agent.agent.LlmTurn;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LlmCallEvent(UUID runId, String agentVersion, String sessionId, String traceId, String spanId,
		String parentSpanId, int agentIteration, String llmCallId, int logicalCallOrdinal,
		String model, Instant startedAt, Instant completedAt, Status status,
		int providerCallCount, List<String> requestedToolCallIds,
		List<String> requestedToolNames, Boolean success, String errorType) {

	public enum Status {
		STARTED,
		COMPLETED
	}

	public static LlmCallEvent started(AgentRunContext context, String spanId, int agentIteration,
			String llmCallId, int logicalCallOrdinal, String model, Instant startedAt) {
		return new LlmCallEvent(context.runId(), context.agentVersion(), context.sessionId(), context.traceId(), spanId,
				context.rootSpanId(), agentIteration, llmCallId, logicalCallOrdinal, model,
				startedAt, null, Status.STARTED, 0, List.of(), List.of(), null, null);
	}

	public LlmCallEvent completed(Instant completedAt, LlmTurn turn) {
		return new LlmCallEvent(runId, agentVersion, sessionId, traceId, spanId, parentSpanId,
				agentIteration, llmCallId, logicalCallOrdinal, model, startedAt, completedAt,
				Status.COMPLETED, turn.modelCalls(),
				turn.toolCalls().stream().map(call -> call.callId()).toList(),
				turn.toolCalls().stream().map(call -> call.name()).toList(), true, null);
	}

	public LlmCallEvent failed(Instant completedAt, int providerCallCount, String errorType) {
		return new LlmCallEvent(runId, agentVersion, sessionId, traceId, spanId, parentSpanId,
				agentIteration, llmCallId, logicalCallOrdinal, model, startedAt, completedAt,
				Status.COMPLETED, providerCallCount, List.of(), List.of(), false, errorType);
	}
}
