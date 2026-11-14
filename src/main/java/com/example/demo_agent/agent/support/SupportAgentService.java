package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.DecisionValidationException;
import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.LlmCallFailureException;
import com.example.demo_agent.agent.LlmConfigurationException;
import com.example.demo_agent.agent.LlmSession;
import com.example.demo_agent.agent.LlmTurn;
import com.example.demo_agent.agent.AgentInvocationContext;

import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationType;
import com.example.demo_agent.client.DeliveryAgentApiClient;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SupportAgentService {

	private static final Logger log = LoggerFactory.getLogger(SupportAgentService.class);
	private static final Pattern ORDER_ID = Pattern.compile("\\bORD-[A-Za-z0-9-]+\\b",
			Pattern.CASE_INSENSITIVE);

	private final LlmSessionFactory sessionFactory;
	private final SupportToolExecutor toolExecutor;
	private final AgentDecisionValidator decisionValidator;
	private final ConversationMemoryStore memoryStore;
	private final SupportAgentProperties properties;
	private final DeliveryAgentApiClient deliveryAgentClient;
	private final AgentEventPublisher eventPublisher;
	private final DeterministicToolPathPolicy toolPathPolicy = new DeterministicToolPathPolicy();

	public SupportAgentService(LlmSessionFactory sessionFactory, SupportToolExecutor toolExecutor,
			AgentDecisionValidator decisionValidator, ConversationMemoryStore memoryStore,
			SupportAgentProperties properties) {
		this(sessionFactory, toolExecutor, decisionValidator, memoryStore, properties, null,
				new AgentEventPublisher() { });
	}

	public SupportAgentService(LlmSessionFactory sessionFactory, SupportToolExecutor toolExecutor,
			AgentDecisionValidator decisionValidator, ConversationMemoryStore memoryStore,
			SupportAgentProperties properties, AgentEventPublisher eventPublisher) {
		this(sessionFactory, toolExecutor, decisionValidator, memoryStore, properties, null, eventPublisher);
	}

	@Autowired
	public SupportAgentService(LlmSessionFactory sessionFactory, SupportToolExecutor toolExecutor,
			AgentDecisionValidator decisionValidator, ConversationMemoryStore memoryStore,
			SupportAgentProperties properties, DeliveryAgentApiClient deliveryAgentClient,
			AgentEventPublisher eventPublisher) {
		this.sessionFactory = sessionFactory;
		this.toolExecutor = toolExecutor;
		this.decisionValidator = decisionValidator;
		this.memoryStore = memoryStore;
		this.properties = properties;
		this.deliveryAgentClient = deliveryAgentClient;
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
		DeliveryInvestigationResult deliveryInvestigation = null;
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
					AgentDecision decision = withVerifiedEvidence(
							earlyDecision.get(), observedEvidence, deliveryInvestigation);
					memoryStore.add(context.sessionId(), customerId,
							new ConversationTurn(message, decision.message(), observedEvidence));
					log.info("Deterministic early decision accepted outcome={} llmCalls={} toolCalls={}",
							decision.outcome(), llmCalls, toolCalls);
					return ChatResponse.from(decision, llmCalls, toolCalls);
				}
				session.submitToolResults(results);
				if (deliveryInvestigation == null) {
					deliveryInvestigation = investigateDelivery(message, history, customerId,
							context, iteration, observedEvidence);
					if (deliveryInvestigation != null) {
						session.submitDelegatedAgentResult("delivery-agent", deliveryInvestigation);
					}
				}
				continue;
			}

			try {
				toolPathPolicy.validateComplete(message, history, observedEvidence);
				AgentDecision decision = withVerifiedEvidence(
						decisionValidator.parseAndValidate(turn.proposedDecisionJson(), message,
								history, observedEvidence, deliveryInvestigation),
						observedEvidence, deliveryInvestigation);
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

	private AgentDecision withVerifiedEvidence(AgentDecision decision,
			List<ExecutedToolCall> observedEvidence,
			DeliveryInvestigationResult deliveryInvestigation) {
		List<String> evidence = new ArrayList<>(decision.evidence());
		for (ExecutedToolCall call : observedEvidence) {
			if (!call.success()) {
				continue;
			}
			if (GetCustomerOrdersTool.NAME.equals(call.name()) && call.output() instanceof List<?> values) {
				List<CustomerOrderResponse> orders = values.stream()
						.filter(CustomerOrderResponse.class::isInstance)
						.map(CustomerOrderResponse.class::cast)
						.toList();
				if (orders.isEmpty()) {
					evidence.add("get_customer_orders returned no owned orders.");
				} else {
					String verifiedOrders = orders.stream().map(order -> order.orderId() + " ("
							+ (order.overallStatus() == null ? "status unavailable"
									: order.overallStatus().code()) + ")")
							.collect(java.util.stream.Collectors.joining(", "));
					evidence.add("get_customer_orders verified these owned orders: " + verifiedOrders + ".");
				}
			}
		}
		if (deliveryInvestigation != null) {
			evidence.add("Delivery Agent returned " + deliveryInvestigation.outcome() + " for "
					+ deliveryInvestigation.orderId() + (deliveryInvestigation.statusCode() == null ? "."
					: " with status " + deliveryInvestigation.statusCode() + "."));
			deliveryInvestigation.evidence().forEach(item -> evidence.add(item.fact() + " ("
					+ item.source() + ", observed " + item.observedAt() + ")."));
		}
		return new AgentDecision(decision.message(), decision.outcome(), decision.resolvedIntents(),
				decision.orderId(), evidence.stream().distinct().toList(), decision.recommendations(),
				decision.actionsRequiringApproval());
	}

	private DeliveryInvestigationResult investigateDelivery(String message,
			List<ConversationTurn> history, String customerId, AgentRunContext context,
			int iteration, List<ExecutedToolCall> evidence) {
		if (deliveryAgentClient == null || !requiresDeliveryInvestigation(message, history)) {
			return null;
		}
		Optional<String> orderId = selectedOwnedOrderId(message, history, evidence);
		if (orderId.isEmpty()) {
			return null;
		}
		String parentSpanId = context.rootSpanId();
		AgentInvocationContext invocation = new AgentInvocationContext(context.runId(),
				context.sessionId(), context.traceId(), parentSpanId, Instant.now());
		log.info("Delegating delivery investigation over HTTP orderId={} iteration={}",
				orderId.get(), iteration);
		String previousAgentVersion = MDC.get("agentVersion");
		try {
			MDC.put("agentVersion", context.agentVersion());
			return deliveryAgentClient.investigate(new DeliveryInvestigationRequest(
					customerId, orderId.get(), investigationType(message)), invocation, iteration);
		} finally {
			restoreMdc("agentVersion", previousAgentVersion);
		}
	}

	private boolean requiresDeliveryInvestigation(String message, List<ConversationTurn> history) {
		return toolPathPolicy.expectedPath(message, history)
				.map(DeterministicToolPathPolicy.ExpectedToolPath::useCase)
				.filter(useCase -> java.util.Set.of("DELIVERY_STATUS", "REFUND_ELIGIBILITY",
						"EXCHANGE_ELIGIBILITY", "CONTEXTUAL_REFUND_ELIGIBILITY",
						"CONTEXTUAL_EXCHANGE_ELIGIBILITY").contains(useCase))
				.isPresent();
	}

	private Optional<String> selectedOwnedOrderId(String message, List<ConversationTurn> history,
			List<ExecutedToolCall> evidence) {
		var priorEvidence = history.stream().flatMap(turn -> turn.verifiedEvidence().stream());
		List<CustomerOrderResponse> orders = java.util.stream.Stream.concat(priorEvidence, evidence.stream())
				.filter(call -> GetCustomerOrdersTool.NAME.equals(call.name()) && call.success())
				.map(ExecutedToolCall::output)
				.filter(List.class::isInstance)
				.map(value -> (List<?>) value)
				.flatMap(List::stream)
				.filter(CustomerOrderResponse.class::isInstance)
				.map(CustomerOrderResponse.class::cast)
				.distinct()
				.toList();
		var matcher = ORDER_ID.matcher(message);
		if (matcher.find()) {
			String requested = matcher.group();
			return orders.stream().map(CustomerOrderResponse::orderId)
					.filter(owned -> owned.equalsIgnoreCase(requested)).findFirst();
		}
		for (int index = history.size() - 1; index >= 0; index--) {
			ConversationTurn turn = history.get(index);
			var priorMatcher = ORDER_ID.matcher(turn.userMessage() + " " + turn.assistantMessage());
			if (priorMatcher.find()) {
				String requested = priorMatcher.group();
				return orders.stream().map(CustomerOrderResponse::orderId)
						.filter(owned -> owned.equalsIgnoreCase(requested)).findFirst();
			}
		}
		return orders.size() == 1 ? Optional.of(orders.getFirst().orderId()) : Optional.empty();
	}

	private DeliveryInvestigationType investigationType(String message) {
		String normalized = message.toLowerCase(Locale.ROOT);
		if (mentions(normalized, "late", "delay", "delayed", "why hasn't", "why has not")) {
			return DeliveryInvestigationType.DELAY_EXPLANATION;
		}
		if (mentions(normalized, "missing", "lost", "damaged", "wrong address",
				"delivery problem", "not delivered")) {
			return DeliveryInvestigationType.DELIVERY_PROBLEM;
		}
		return DeliveryInvestigationType.CURRENT_STATUS;
	}

	private boolean mentions(String text, String... terms) {
		for (String term : terms) {
			if (text.contains(term)) {
				return true;
			}
		}
		return false;
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
