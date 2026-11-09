package com.example.demo_agent.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class LoggingAgentEventPublisher implements AgentEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(LoggingAgentEventPublisher.class);

	private final ApplicationEventPublisher applicationEvents;

	public LoggingAgentEventPublisher(ApplicationEventPublisher applicationEvents) {
		this.applicationEvents = applicationEvents;
	}

	@Override
	public void publishStarted(AgentRunStartedEvent event) {
		applicationEvents.publishEvent(event);
		AgentRunContext context = event.context();
		log.info("Agent run started runId={} agentVersion={} promptVersion={} customerId={}",
				context.runId(), context.agentVersion(), context.promptVersion(), event.request().customerId());
	}

	@Override
	public void publishCompleted(AgentRunCompletedEvent event) {
		applicationEvents.publishEvent(event);
		log.info("Agent run completed runId={} outcome={} orderId={} llmCalls={} toolCalls={} durationMs={}",
				event.context().runId(), event.response().outcome(), event.response().orderId(),
				event.response().llmCalls(), event.response().toolCalls(), event.durationMs());
	}

	@Override
	public void publishFailed(AgentRunFailedEvent event) {
		applicationEvents.publishEvent(event);
		log.error("Agent run failed runId={} errorType={} errorMessage={} durationMs={}",
				event.context().runId(), event.errorType(), event.errorMessage(), event.durationMs());
	}

	@Override
	public void publishToolCall(ToolCallEvent event) {
		applicationEvents.publishEvent(event);
		log.info("Tool call event status={} runId={} sessionId={} traceId={} spanId={} parentSpanId={} "
					+ "iteration={} callId={} name={} ordinal={} llmCallId={} llmSpanId={} "
					+ "arguments={} success={} errorCode={}",
				event.status(), event.runId(), event.sessionId(), event.traceId(), event.spanId(), event.parentSpanId(),
				event.agentIteration(), event.toolCallId(), event.toolName(), event.toolOrdinal(),
				event.originatingLlmCallId(), event.originatingLlmSpanId(),
				event.sanitizedArguments(), event.success(), event.errorCode());
	}

	@Override
	public void publishLlmCall(LlmCallEvent event) {
		applicationEvents.publishEvent(event);
		log.info("LLM call event status={} runId={} sessionId={} traceId={} spanId={} parentSpanId={} "
					+ "iteration={} llmCallId={} ordinal={} model={} providerCalls={} tools={} success={} errorType={}",
				event.status(), event.runId(), event.sessionId(), event.traceId(), event.spanId(),
				event.parentSpanId(), event.agentIteration(), event.llmCallId(), event.logicalCallOrdinal(),
				event.model(), event.providerCallCount(), event.requestedToolNames(), event.success(), event.errorType());
	}

	@Override
	public void publishResourceCall(ResourceCallEvent event) {
		applicationEvents.publishEvent(event);
		log.info("Resource call event status={} runId={} sessionId={} traceId={} spanId={} parentSpanId={} "
					+ "iteration={} toolCallId={} tool={} type={} operation={} resource={} success={} errorCode={}",
				event.status(), event.runId(), event.sessionId(), event.traceId(), event.spanId(),
				event.parentSpanId(), event.agentIteration(), event.toolCallId(), event.toolName(),
				event.resourceType(), event.operation(), event.resource(), event.success(), event.errorCode());
	}
}
