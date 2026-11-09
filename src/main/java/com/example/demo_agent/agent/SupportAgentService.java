package com.example.demo_agent.agent;

import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.tool.ToolExecutionContext;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.AgentRunCompletedEvent;
import com.example.demo_agent.tracing.AgentRunContext;
import com.example.demo_agent.tracing.AgentRunFailedEvent;
import com.example.demo_agent.tracing.AgentRunStartedEvent;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.LlmCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SupportAgentService {

	private static final Logger log = LoggerFactory.getLogger(SupportAgentService.class);

	private final LlmSessionFactory sessionFactory;
	private final SupportToolExecutor toolExecutor;
	private final AgentDecisionValidator decisionValidator;
	private final ConversationMemoryStore memoryStore;
	private final SupportAgentProperties properties;
	private final AgentEventPublisher eventPublisher;
	private final DeterministicToolPathPolicy toolPathPolicy = new DeterministicToolPathPolicy();

	public SupportAgentService(LlmSessionFactory sessionFactory, SupportToolExecutor toolExecutor,
			AgentDecisionValidator decisionValidator, ConversationMemoryStore memoryStore,
			SupportAgentProperties properties) {
		this(sessionFactory, toolExecutor, decisionValidator, memoryStore, properties,
				new AgentEventPublisher() { });
	}

	@Autowired
	public SupportAgentService(LlmSessionFactory sessionFactory, SupportToolExecutor toolExecutor,
			AgentDecisionValidator decisionValidator, ConversationMemoryStore memoryStore,
			SupportAgentProperties properties, AgentEventPublisher eventPublisher) {
		this.sessionFactory = sessionFactory;
		this.toolExecutor = toolExecutor;
		this.decisionValidator = decisionValidator;
		this.memoryStore = memoryStore;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
	}

	public ChatResponse chat(ChatRequest request, String sessionId) {
		AgentRunContext context = AgentRunContext.start(sessionId, "stage-2", "case-manager-v1");
		eventPublisher.publishStarted(new AgentRunStartedEvent(context, request));
		try {
			ChatResponse response = runChat(request, context);
			Instant completedAt = Instant.now();
			eventPublisher.publishCompleted(new AgentRunCompletedEvent(context, request, response,
					completedAt, durationMs(context.startedAt(), completedAt)));
			return response;
		} catch (RuntimeException exception) {
			Instant failedAt = Instant.now();
			eventPublisher.publishFailed(new AgentRunFailedEvent(context, request, failedAt,
					durationMs(context.startedAt(), failedAt), exception.getClass().getSimpleName(),
					exception.getMessage()));
			throw exception;
		}
	}

	private ChatResponse runChat(ChatRequest request, AgentRunContext context) {
		String customerId = request.customerId().trim();
		String message = request.message().trim();
		List<ConversationTurn> history = memoryStore.get(context.sessionId(), customerId);
		int llmCalls = 0;
		int logicalLlmCalls = 0;
		int toolCalls = 0;
		int validationRetries = 0;
		List<ExecutedToolCall> observedEvidence = new ArrayList<>();
		Map<String, Integer> repeatedFailures = new HashMap<>();

		final LlmSession session;
		try {
			session = sessionFactory.create(history, customerId, message);
		} catch (LlmConfigurationException exception) {
			log.warn("Support agent is not configured");
			return ChatResponse.safeFailure(exception.getMessage(), 0, 0);
		} catch (RuntimeException exception) {
			log.error("Support model session initialization failed safely", exception);
			return ChatResponse.safeFailure(
					"I’m temporarily unable to initialize the support model. Please try again shortly.", 0, 0);
		}

		for (int iteration = 1; iteration <= properties.agent().maxIterations(); iteration++) {
			final LlmTurn turn;
			String llmCallId = UUID.randomUUID().toString();
			String llmSpanId = CorrelationIds.newSpanId();
			LlmCallEvent llmEvent = LlmCallEvent.started(context, llmSpanId, iteration,
					llmCallId, ++logicalLlmCalls, properties.llm().model(), Instant.now());
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
				log.warn("LLM call failed safely iteration={} llmCalls={}",
						iteration, llmCalls + failedProviderCalls, exception);
				return ChatResponse.safeFailure(
						"I’m temporarily unable to reach the support model. Please try again shortly.",
						llmCalls + failedProviderCalls, toolCalls);
			} finally {
				restoreMdc("supportSpanId", parentSpanId);
			}

			if (turn.hasToolCalls()) {
				try {
				toolPathPolicy.validateProposed(message, history, observedEvidence, turn.toolCalls());
				} catch (DecisionValidationException exception) {
					validationRetries++;
					log.warn("Tool path rejected retry={} reason={}", validationRetries, exception.getMessage());
					if (validationRetries > properties.agent().maxValidationRetries()) {
						return ChatResponse.safeFailure(
								"I couldn’t follow the required tool sequence safely. Please try again.",
								llmCalls, toolCalls);
					}
					session.submitValidationFailure(exception.getMessage());
					continue;
				}
				if (toolCalls + turn.toolCalls().size() > properties.agent().maxToolCalls()) {
					return ChatResponse.safeFailure("The request exceeded the safe tool-call budget. Please narrow it down.",
							llmCalls, toolCalls);
				}
				List<ExecutedToolCall> results = toolExecutor.execute(turn.toolCalls(),
						new ToolExecutionContext(customerId, context, iteration, toolCalls,
								llmCallId, llmSpanId));
				toolCalls += results.size();
				observedEvidence.addAll(results);
				for (ExecutedToolCall result : results) {
					if (result.validationFingerprint() != null) {
						int repeats = repeatedFailures.merge(result.validationFingerprint(), 1, Integer::sum);
						if (repeats >= 2) {
							return ChatResponse.safeFailure(
									"The model repeated an invalid tool request, so I stopped safely. Please rephrase the request.",
									llmCalls, toolCalls);
						}
					}
				}
				java.util.Optional<AgentDecision> earlyDecision =
						toolPathPolicy.earlyDecision(message, history, observedEvidence);
				if (earlyDecision.isPresent()) {
					AgentDecision decision = earlyDecision.get();
					memoryStore.add(context.sessionId(), customerId,
							new ConversationTurn(message, decision.message(), observedEvidence));
					log.info("Deterministic early decision accepted outcome={} llmCalls={} toolCalls={}",
							decision.outcome(), llmCalls, toolCalls);
					return ChatResponse.from(decision, llmCalls, toolCalls);
				}
				session.submitToolResults(results);
				continue;
			}

			try {
				toolPathPolicy.validateComplete(message, history, observedEvidence);
				AgentDecision decision = decisionValidator.parseAndValidate(turn.proposedDecisionJson(), message,
						history, observedEvidence);
				memoryStore.add(context.sessionId(), customerId,
						new ConversationTurn(message, decision.message(), observedEvidence));
				log.info("Agent decision accepted outcome={} llmCalls={} toolCalls={}",
						decision.outcome(), llmCalls, toolCalls);
				return ChatResponse.from(decision, llmCalls, toolCalls);
			} catch (DecisionValidationException exception) {
				validationRetries++;
				log.warn("Agent decision rejected retry={} reason={}", validationRetries, exception.getMessage());
				if (validationRetries > properties.agent().maxValidationRetries()) {
					return ChatResponse.safeFailure(
							"I couldn’t produce a safely validated answer. A human support agent should review this request.",
							llmCalls, toolCalls);
				}
				session.submitValidationFailure(exception.getMessage());
			}
		}
		return ChatResponse.safeFailure("The request reached the safe iteration limit. Please narrow it down.",
				llmCalls, toolCalls);
	}

	private long durationMs(Instant startedAt, Instant completedAt) {
		return Duration.between(startedAt, completedAt).toMillis();
	}

	private void restoreMdc(String key, String value) {
		if (value == null) {
			MDC.remove(key);
		} else {
			MDC.put(key, value);
		}
	}

	private String rootCauseType(RuntimeException exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName();
	}
}
