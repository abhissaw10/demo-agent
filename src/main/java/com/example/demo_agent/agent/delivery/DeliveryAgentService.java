package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.DecisionValidationException;
import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.LlmCallFailureException;
import com.example.demo_agent.agent.LlmConfigurationException;
import com.example.demo_agent.agent.LlmSession;
import com.example.demo_agent.agent.LlmTurn;
import com.example.demo_agent.agent.delivery.DeliveryAgentToolExecutor.OrderAccessFacts;
import com.example.demo_agent.config.DeliveryAgentProperties;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.AgentRunContext;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.DelegatedAgentRunCompletedEvent;
import com.example.demo_agent.tracing.DelegatedAgentRunFailedEvent;
import com.example.demo_agent.tracing.DelegatedAgentRunStartedEvent;
import com.example.demo_agent.tracing.LlmCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryAgentService implements DeliveryAgent {

	private static final Logger log = LoggerFactory.getLogger(DeliveryAgentService.class);
	private static final String AGENT_VERSION = "delivery-agent-v1";
	private static final String PROMPT_VERSION = "delivery-investigation-v1";

	private final DeliveryLlmSessionFactory sessionFactory;
	private final DeliveryAgentToolExecutor toolExecutor;
	private final DeliveryDecisionValidator decisionValidator;
	private final DeliveryAgentProperties properties;
	private final AgentEventPublisher eventPublisher;

	DeliveryAgentService(DeliveryLlmSessionFactory sessionFactory,
			DeliveryAgentToolExecutor toolExecutor,
			DeliveryDecisionValidator decisionValidator,
			DeliveryAgentProperties properties) {
		this(sessionFactory, toolExecutor, decisionValidator, properties, new AgentEventPublisher() { });
	}

	@Autowired
	DeliveryAgentService(DeliveryLlmSessionFactory sessionFactory,
			DeliveryAgentToolExecutor toolExecutor,
			DeliveryDecisionValidator decisionValidator,
			DeliveryAgentProperties properties,
			AgentEventPublisher eventPublisher) {
		this.sessionFactory = sessionFactory;
		this.toolExecutor = toolExecutor;
		this.decisionValidator = decisionValidator;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
	}

	@Override
	public DeliveryInvestigationResult investigate(DeliveryInvestigationRequest request,
			AgentInvocationContext invocation) {
		DeliveryInvestigationRequest observedRequest = request == null
				? new DeliveryInvestigationRequest(null, null, null) : request;
		AgentRunContext run = startRun(invocation);
		UUID parentRunId = invocation == null ? null : invocation.parentRunId();
		String parentSpanId = firstPresent(invocation == null ? null : invocation.parentSpanId(),
				MDC.get("supportSpanId"), null);
		eventPublisher.publishDelegatedStarted(new DelegatedAgentRunStartedEvent(
				run, parentRunId, parentSpanId, observedRequest));

		Map<String, String> previousMdc = captureMdc("supportSpanId", "supportTraceId", "sessionId",
				"supportRunId", "supportAgentIteration", "supportToolCallId", "supportToolName",
				"agentVersion");
		setRunMdc(run);
		try {
			DeliveryAgentRunResult result = runInvestigation(request, invocation, run);
			Instant completedAt = Instant.now();
			eventPublisher.publishDelegatedCompleted(new DelegatedAgentRunCompletedEvent(
					run, parentRunId, parentSpanId, observedRequest, result.result(),
					result.llmCalls(), result.toolCalls(), completedAt,
					durationMs(run.startedAt(), completedAt)));
			return result.result();
		} catch (RuntimeException exception) {
			Instant failedAt = Instant.now();
			eventPublisher.publishDelegatedFailed(new DelegatedAgentRunFailedEvent(
					run, parentRunId, parentSpanId, observedRequest, failedAt,
					durationMs(run.startedAt(), failedAt), rootCauseType(exception), exception.getMessage()));
			throw exception;
		} finally {
			restoreMdc(previousMdc);
		}
	}

	private DeliveryAgentRunResult runInvestigation(DeliveryInvestigationRequest request,
			AgentInvocationContext invocation, AgentRunContext run) {
		if (request == null || blank(request.customerId()) || blank(request.orderId())
				|| request.investigationType() == null) {
			return completed(failure(DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION,
					request == null ? null : request.orderId(),
					"Customer, order, and investigation type are required."), 0, 0);
		}
		DeliveryInvestigationRequest normalized = new DeliveryInvestigationRequest(
				request.customerId().trim(), request.orderId().trim(), request.investigationType());

		final LlmSession session;
		try {
			session = sessionFactory.create(normalized, invocation);
		} catch (LlmConfigurationException exception) {
			log.warn("Delivery Agent model is not configured");
			return completed(unavailable(normalized.orderId()), 0, 0);
		} catch (RuntimeException exception) {
			log.error("Delivery Agent session initialization failed", exception);
			return completed(unavailable(normalized.orderId()), 0, 0);
		}

		List<ExecutedToolCall> evidence = new ArrayList<>();
		int llmCalls = 0;
		int logicalLlmCalls = 0;
		int toolCalls = 0;
		int validationRetries = 0;
		for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
			final LlmTurn turn;
			String llmCallId = UUID.randomUUID().toString();
			String llmSpanId = CorrelationIds.newSpanId();
			LlmCallEvent llmEvent = LlmCallEvent.started(run, llmSpanId, iteration,
					llmCallId, ++logicalLlmCalls, sessionFactory.model(), Instant.now());
			String parentSpanId = MDC.get("supportSpanId");
			try {
				eventPublisher.publishLlmCall(llmEvent);
				MDC.put("supportSpanId", llmSpanId);
				turn = session.next();
				llmCalls += turn.modelCalls();
				eventPublisher.publishLlmCall(llmEvent.completed(Instant.now(), turn));
			} catch (RuntimeException exception) {
				int failedProviderCalls = exception instanceof LlmCallFailureException failure
						? failure.providerCallCount() : 1;
				eventPublisher.publishLlmCall(llmEvent.failed(Instant.now(), failedProviderCalls,
						rootCauseType(exception)));
				log.warn("Delivery Agent LLM call failed iteration={} orderId={}",
						iteration, normalized.orderId(), exception);
				return completed(unavailable(normalized.orderId()),
						llmCalls + failedProviderCalls, toolCalls);
			} finally {
				restoreMdcValue("supportSpanId", parentSpanId);
			}

			if (turn.hasToolCalls()) {
				try {
					validateToolPath(turn, evidence);
				} catch (DecisionValidationException exception) {
					if (++validationRetries > properties.maxValidationRetries()) {
						return completed(failure(DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION,
								normalized.orderId(),
								"The Delivery Agent could not follow its required tool path."),
								llmCalls, toolCalls);
					}
					session.submitValidationFailure(exception.getMessage());
					continue;
				}
				if (toolCalls + turn.toolCalls().size() > properties.maxToolCalls()) {
					return completed(failure(DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION,
							normalized.orderId(), "The Delivery Agent exceeded its tool-call budget."),
							llmCalls, toolCalls);
				}
				int toolCallsBeforeExecution = toolCalls;
				try {
					List<ExecutedToolCall> results = toolExecutor.execute(turn.toolCalls(),
							new DeliveryToolExecutionContext(normalized, run, iteration, toolCalls,
									llmCallId, llmSpanId));
					toolCalls += results.size();
					evidence.addAll(results);
					session.submitToolResults(results);
				} catch (RuntimeException exception) {
					toolCalls = Math.max(toolCalls,
							toolCallsBeforeExecution + turn.toolCalls().size());
					log.error("Delivery Agent tool execution failed orderId={}", normalized.orderId(), exception);
					return completed(unavailable(normalized.orderId()), llmCalls, toolCalls);
				}
				continue;
			}

			try {
				if (nextRequiredTool(evidence).isPresent()) {
					throw new DecisionValidationException(
							"The required Delivery Agent tool path is not complete.");
				}
				DeliveryInvestigationResult result = decisionValidator.parseAndValidate(
						turn.proposedDecisionJson(), normalized, evidence);
				log.info("Delivery Agent completed outcome={} orderId={} iterations={} llmCalls={} toolCalls={}",
						result.outcome(), result.orderId(), iteration, llmCalls, toolCalls);
				return completed(result, llmCalls, toolCalls);
			} catch (DecisionValidationException exception) {
				if (++validationRetries > properties.maxValidationRetries()) {
					return completed(failure(DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION,
							normalized.orderId(), "The Delivery Agent could not produce a validated result."),
							llmCalls, toolCalls);
				}
				session.submitValidationFailure(exception.getMessage());
			}
		}
		return completed(failure(DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION,
				normalized.orderId(), "The Delivery Agent reached its iteration limit."),
				llmCalls, toolCalls);
	}

	private AgentRunContext startRun(AgentInvocationContext invocation) {
		String sessionId = firstPresent(invocation == null ? null : invocation.sessionId(),
				MDC.get("sessionId"), UUID.randomUUID().toString());
		String traceId = firstPresent(invocation == null ? null : invocation.traceId(),
				MDC.get("supportTraceId"), CorrelationIds.newTraceId());
		return new AgentRunContext(UUID.randomUUID(), sessionId, traceId, CorrelationIds.newSpanId(),
				Instant.now(), AGENT_VERSION, PROMPT_VERSION);
	}

	private void setRunMdc(AgentRunContext run) {
		MDC.put("supportSpanId", run.rootSpanId());
		MDC.put("supportTraceId", run.traceId());
		MDC.put("sessionId", run.sessionId());
		MDC.put("supportRunId", run.runId().toString());
		MDC.put("agentVersion", run.agentVersion());
		MDC.remove("supportAgentIteration");
		MDC.remove("supportToolCallId");
		MDC.remove("supportToolName");
	}

	private Map<String, String> captureMdc(String... keys) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : keys) {
			values.put(key, MDC.get(key));
		}
		return values;
	}

	private void restoreMdc(Map<String, String> values) {
		values.forEach(this::restoreMdcValue);
	}

	private void restoreMdcValue(String key, String value) {
		if (value == null) {
			MDC.remove(key);
		} else {
			MDC.put(key, value);
		}
	}

	private void validateToolPath(LlmTurn turn, List<ExecutedToolCall> evidence) {
		Optional<String> required = nextRequiredTool(evidence);
		List<String> proposed = turn.toolCalls().stream().map(call -> call.name()).toList();
		if (required.isEmpty() || proposed.size() != 1 || !required.get().equals(proposed.getFirst())) {
			throw new DecisionValidationException("The Delivery Agent must call exactly "
					+ required.orElse("no more tools") + "; proposed " + proposed + ".");
		}
	}

	private Optional<String> nextRequiredTool(List<ExecutedToolCall> evidence) {
		OrderAccessFacts access = evidence.stream()
				.filter(call -> DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS.equals(call.name()))
				.map(ExecutedToolCall::output)
				.filter(OrderAccessFacts.class::isInstance)
				.map(OrderAccessFacts.class::cast)
				.findFirst()
				.orElse(null);
		if (access == null) {
			return Optional.of(DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS);
		}
		if (access.outcome() != DeliveryInvestigationOutcome.COMPLETED) {
			return Optional.empty();
		}
		boolean hasStatus = evidence.stream()
				.anyMatch(call -> DeliveryAgentToolExecutor.GET_DELIVERY_STATUS.equals(call.name()));
		return hasStatus ? Optional.empty() : Optional.of(DeliveryAgentToolExecutor.GET_DELIVERY_STATUS);
	}

	private DeliveryAgentRunResult completed(DeliveryInvestigationResult result, int llmCalls, int toolCalls) {
		return new DeliveryAgentRunResult(result, llmCalls, toolCalls);
	}

	private DeliveryInvestigationResult unavailable(String orderId) {
		return failure(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE, orderId,
				"Delivery information is temporarily unavailable.");
	}

	private DeliveryInvestigationResult failure(DeliveryInvestigationOutcome outcome,
			String orderId, String summary) {
		return new DeliveryInvestigationResult(outcome, orderId, null, summary, List.of());
	}

	private long durationMs(Instant startedAt, Instant completedAt) {
		return Duration.between(startedAt, completedAt).toMillis();
	}

	private String rootCauseType(RuntimeException exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName();
	}

	private String firstPresent(String first, String second, String fallback) {
		if (!blank(first)) {
			return first;
		}
		return blank(second) ? fallback : second;
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private record DeliveryAgentRunResult(DeliveryInvestigationResult result,
			int llmCalls, int toolCalls) {
	}
}
