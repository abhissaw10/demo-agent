package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.RequestedToolCall;
import com.example.demo_agent.api.DeliveryUpdateResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.client.DownstreamApiException;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.ToolCallEvent;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes only the resource tools owned by the Delivery Agent. */
@Component
class DeliveryAgentToolExecutor {

	static final String RESOLVE_ORDER_ACCESS = "resolve_delivery_order";
	static final String GET_DELIVERY_STATUS = "get_delivery_status_facts";

	private final DeliveryAgentTools tools;
	private final AgentEventPublisher eventPublisher;

	DeliveryAgentToolExecutor(DeliveryAgentTools tools) {
		this(tools, new AgentEventPublisher() { });
	}

	@Autowired
	DeliveryAgentToolExecutor(DeliveryAgentTools tools, AgentEventPublisher eventPublisher) {
		this.tools = tools;
		this.eventPublisher = eventPublisher;
	}

	List<ExecutedToolCall> execute(List<RequestedToolCall> calls, DeliveryToolExecutionContext context) {
		return java.util.stream.IntStream.range(0, calls.size())
				.mapToObj(index -> executeOne(calls.get(index), context,
						context.completedToolCalls() + index + 1))
				.toList();
	}

	private ExecutedToolCall executeOne(RequestedToolCall call, DeliveryToolExecutionContext context,
			int toolOrdinal) {
		String childSpanId = CorrelationIds.newSpanId();
		ToolCallEvent event = ToolCallEvent.started(context.agentRun(), childSpanId,
				context.agentIteration(), call.callId(), call.name(), toolOrdinal, "{}",
				context.originatingLlmCallId(), context.originatingLlmSpanId(), Instant.now());
		Map<String, String> previousMdc = captureMdc("supportSpanId", "supportTraceId", "sessionId",
				"supportRunId", "supportAgentIteration", "supportToolCallId", "supportToolName",
				"agentVersion");
		MDC.put("supportSpanId", childSpanId);
		MDC.put("supportTraceId", context.agentRun().traceId());
		MDC.put("sessionId", context.agentRun().sessionId());
		MDC.put("supportRunId", context.agentRun().runId().toString());
		MDC.put("supportAgentIteration", Integer.toString(context.agentIteration()));
		MDC.put("supportToolCallId", call.callId());
		MDC.put("supportToolName", call.name());
		MDC.put("agentVersion", context.agentRun().agentVersion());
		try {
			eventPublisher.publishToolCall(event);
			Object output = switch (call.name()) {
				case RESOLVE_ORDER_ACCESS -> resolveOrderAccess(context.request());
				case GET_DELIVERY_STATUS -> getDeliveryStatus(context.request().orderId());
				default -> throw new IllegalArgumentException("Unknown Delivery Agent tool: " + call.name());
			};
			ExecutedToolCall result = new ExecutedToolCall(call.callId(), call.name(), output, true, null);
			eventPublisher.publishToolCall(event.completed(Instant.now(), true, null));
			return result;
		} catch (RuntimeException exception) {
			eventPublisher.publishToolCall(event.completed(Instant.now(), false,
					exception.getClass().getSimpleName()));
			throw exception;
		} finally {
			restoreMdc(previousMdc);
		}
	}

	private Map<String, String> captureMdc(String... keys) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : keys) {
			values.put(key, MDC.get(key));
		}
		return values;
	}

	private void restoreMdc(Map<String, String> values) {
		values.forEach((key, value) -> {
			if (value == null) {
				MDC.remove(key);
			} else {
				MDC.put(key, value);
			}
		});
	}

	private OrderAccessFacts resolveOrderAccess(DeliveryInvestigationRequest request) {
		try {
			boolean owned = tools.getCustomerOrders(request.customerId()).stream()
					.anyMatch(order -> request.orderId().equals(order.orderId()));
			if (owned) {
				return new OrderAccessFacts(DeliveryInvestigationOutcome.COMPLETED, request.orderId(),
						"The order belongs to the authenticated customer.");
			}
			return resolveMissingOwnership(request.orderId());
		} catch (DownstreamApiException exception) {
			if ("CUSTOMER_ORDERS_NOT_FOUND".equals(exception.getCode())) {
				return resolveMissingOwnership(request.orderId());
			}
			return dependencyFailure(request.orderId(), exception);
		}
	}

	private OrderAccessFacts resolveMissingOwnership(String orderId) {
		try {
			tools.getDeliveryStatus(orderId);
			return new OrderAccessFacts(DeliveryInvestigationOutcome.ORDER_NOT_OWNED, orderId,
					"The order is not owned by the authenticated customer.");
		} catch (DownstreamApiException exception) {
			if ("ORDER_NOT_FOUND".equals(exception.getCode())) {
				return new OrderAccessFacts(DeliveryInvestigationOutcome.ORDER_NOT_FOUND, orderId,
						"The order could not be found.");
			}
			return dependencyFailure(orderId, exception);
		}
	}

	private OrderAccessFacts dependencyFailure(String orderId, DownstreamApiException exception) {
		DeliveryInvestigationOutcome outcome = "ORDER_STATUS_NOT_FOUND".equals(exception.getCode())
				? DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION
				: DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE;
		String summary = outcome == DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION
				? "No delivery updates are available for this order."
				: "Delivery information is temporarily unavailable.";
		return new OrderAccessFacts(outcome, orderId, summary);
	}

	private DeliveryStatusFacts getDeliveryStatus(String orderId) {
		try {
			OrderDeliveryStatusResponse status = tools.getDeliveryStatus(orderId);
			if (status.currentStatus() == null) {
				return new DeliveryStatusFacts(DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION,
						orderId, null, null, null, null, null, false,
						"No current delivery checkpoint is available.");
			}
			DeliveryUpdateResponse current = status.currentStatus();
			Instant observedAt = current.updatedAt() == null
					? null : current.updatedAt().toInstant(ZoneOffset.UTC);
			return new DeliveryStatusFacts(DeliveryInvestigationOutcome.COMPLETED, orderId,
					current.statusCode(), current.status(), current.description(), current.details(),
					observedAt, current.terminal(), null);
		} catch (DownstreamApiException exception) {
			DeliveryInvestigationOutcome outcome = switch (exception.getCode()) {
				case "ORDER_NOT_FOUND" -> DeliveryInvestigationOutcome.ORDER_NOT_FOUND;
				case "ORDER_STATUS_NOT_FOUND" -> DeliveryInvestigationOutcome.INSUFFICIENT_INFORMATION;
				default -> DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE;
			};
			String summary = switch (outcome) {
				case ORDER_NOT_FOUND -> "The order could not be found.";
				case INSUFFICIENT_INFORMATION -> "No delivery updates are available for this order.";
				default -> "Delivery information is temporarily unavailable.";
			};
			return new DeliveryStatusFacts(outcome, orderId, null, null, null, null,
					null, false, summary);
		}
	}

	record OrderAccessFacts(DeliveryInvestigationOutcome outcome, String orderId, String summary) {
	}

	record DeliveryStatusFacts(DeliveryInvestigationOutcome outcome, String orderId,
			String statusCode, String status, String description, String details,
			Instant observedAt, boolean terminal, String failureSummary) {
	}
}
