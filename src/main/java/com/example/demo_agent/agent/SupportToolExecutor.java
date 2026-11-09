package com.example.demo_agent.agent;

import com.example.demo_agent.client.DownstreamApiException;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetDeliveryStatusTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import com.example.demo_agent.tool.ToolExecutionContext;
import com.example.demo_agent.tool.ToolValidationException;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.ToolCallEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
public class SupportToolExecutor {

	private static final Logger log = LoggerFactory.getLogger(SupportToolExecutor.class);
	private static final Set<String> ALLOW_LIST = Set.of(
			GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME,
			GetRefundPolicyTool.NAME, GetExchangePolicyTool.NAME);

	private final GetCustomerOrdersTool customerOrders;
	private final GetDeliveryStatusTool deliveryStatus;
	private final GetRefundPolicyTool refundPolicy;
	private final GetExchangePolicyTool exchangePolicy;
	private final AgentEventPublisher eventPublisher;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	public SupportToolExecutor(GetCustomerOrdersTool customerOrders, GetDeliveryStatusTool deliveryStatus,
			GetRefundPolicyTool refundPolicy, GetExchangePolicyTool exchangePolicy) {
		this(customerOrders, deliveryStatus, refundPolicy, exchangePolicy, new AgentEventPublisher() { });
	}

	@Autowired
	public SupportToolExecutor(GetCustomerOrdersTool customerOrders, GetDeliveryStatusTool deliveryStatus,
			GetRefundPolicyTool refundPolicy, GetExchangePolicyTool exchangePolicy,
			AgentEventPublisher eventPublisher) {
		this.customerOrders = customerOrders;
		this.deliveryStatus = deliveryStatus;
		this.refundPolicy = refundPolicy;
		this.exchangePolicy = exchangePolicy;
		this.eventPublisher = eventPublisher;
	}

	public List<ExecutedToolCall> execute(List<RequestedToolCall> calls, ToolExecutionContext context) {
		boolean independent = calls.size() > 1
				&& calls.stream().noneMatch(call -> GetDeliveryStatusTool.NAME.equals(call.name()));
		if (!independent) {
			return java.util.stream.IntStream.range(0, calls.size())
					.mapToObj(index -> executeOne(calls.get(index), context,
							context.completedToolCalls() + index + 1))
					.toList();
		}
		Map<String, String> loggingContext = MDC.getCopyOfContextMap();
		List<CompletableFuture<ExecutedToolCall>> futures = java.util.stream.IntStream.range(0, calls.size())
				.mapToObj(index -> CompletableFuture.supplyAsync(() -> executeWithLoggingContext(
						calls.get(index), context, context.completedToolCalls() + index + 1, loggingContext)))
				.toList();
		return futures.stream().map(CompletableFuture::join).toList();
	}

	private ExecutedToolCall executeWithLoggingContext(RequestedToolCall call, ToolExecutionContext context,
			int toolOrdinal, Map<String, String> loggingContext) {
		if (loggingContext != null) {
			MDC.setContextMap(loggingContext);
		}
		try {
			return executeOne(call, context, toolOrdinal);
		} finally {
			MDC.clear();
		}
	}

	private ExecutedToolCall executeOne(RequestedToolCall call, ToolExecutionContext context, int toolOrdinal) {
		long started = System.nanoTime();
		String childSpanId = CorrelationIds.newSpanId();
		ToolCallEvent event = ToolCallEvent.started(context.agentRun(), childSpanId,
				context.agentIteration(), call.callId(), call.name(), toolOrdinal,
				sanitizeArguments(call.argumentsJson()), context.originatingLlmCallId(),
				context.originatingLlmSpanId(), Instant.now());
		Map<String, String> previousMdc = captureMdc("supportSpanId", "supportTraceId", "sessionId",
				"supportRunId", "supportAgentIteration", "supportToolCallId", "supportToolName");
		MDC.put("supportSpanId", childSpanId);
		MDC.put("supportTraceId", context.agentRun().traceId());
		MDC.put("sessionId", context.agentRun().sessionId());
		MDC.put("supportRunId", context.agentRun().runId().toString());
		MDC.put("supportAgentIteration", Integer.toString(context.agentIteration()));
		MDC.put("supportToolCallId", call.callId());
		MDC.put("supportToolName", call.name());
		try {
			eventPublisher.publishToolCall(event);
			if (!ALLOW_LIST.contains(call.name())) {
				throw new ToolValidationException("Tool is not allow-listed: " + call.name());
			}
			Object output = switch (call.name()) {
				case GetCustomerOrdersTool.NAME -> customerOrders.execute(
						parse(call, GetCustomerOrdersTool.Arguments.class), context);
				case GetDeliveryStatusTool.NAME -> deliveryStatus.execute(
						parse(call, GetDeliveryStatusTool.Arguments.class), context);
				case GetRefundPolicyTool.NAME -> refundPolicy.execute(
						parse(call, GetRefundPolicyTool.Arguments.class), context);
				case GetExchangePolicyTool.NAME -> exchangePolicy.execute(
						parse(call, GetExchangePolicyTool.Arguments.class), context);
				default -> throw new ToolValidationException("Tool is not allow-listed: " + call.name());
			};
			log.info("Tool completed name={} durationMs={}", call.name(), elapsedMillis(started));
			ExecutedToolCall result = new ExecutedToolCall(call.callId(), call.name(), output, true, null);
			publishCompleted(event, true, null);
			return result;
		} catch (ToolValidationException | JsonProcessingException exception) {
			String fingerprint = call.name() + ":" + normalize(call.argumentsJson()) + ":" + exception.getMessage();
			log.warn("Tool validation failed name={} reason={}", call.name(), exception.getMessage());
			ExecutedToolCall result = new ExecutedToolCall(call.callId(), call.name(),
					error("VALIDATION_ERROR", exception.getMessage(), false), false, fingerprint);
			publishCompleted(event, false, "VALIDATION_ERROR");
			return result;
		} catch (DownstreamApiException exception) {
			log.warn("Tool dependency failed name={} code={} retryable={} durationMs={}",
					call.name(), exception.getCode(), exception.isRetryable(), elapsedMillis(started));
			ExecutedToolCall result = new ExecutedToolCall(call.callId(), call.name(),
					error(exception.getCode(), exception.getMessage(), exception.isRetryable()), false, null);
			publishCompleted(event, false, exception.getCode());
			return result;
		} catch (RuntimeException exception) {
			log.error("Tool failed name={} durationMs={}", call.name(), elapsedMillis(started), exception);
			ExecutedToolCall result = new ExecutedToolCall(call.callId(), call.name(),
					error("TOOL_FAILURE", "The tool failed safely.", true), false, null);
			publishCompleted(event, false, "TOOL_FAILURE");
			return result;
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

	private void publishCompleted(ToolCallEvent event, boolean success, String errorCode) {
		eventPublisher.publishToolCall(event.completed(Instant.now(), success, errorCode));
	}

	private <T> T parse(RequestedToolCall call, Class<T> type) throws JsonProcessingException {
		String json = call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson();
		return objectMapper.readValue(json, type);
	}

	private Map<String, Object> error(String code, String message, boolean retryable) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", code);
		error.put("message", message);
		error.put("retryable", retryable);
		return error;
	}

	private String normalize(String value) {
		return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
	}

	private String sanitizeArguments(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return "{}";
		}
		try {
			Map<String, Object> arguments = objectMapper.readValue(argumentsJson,
					new TypeReference<Map<String, Object>>() { });
			Map<String, Object> sanitized = new java.util.TreeMap<>();
			arguments.forEach((key, value) -> sanitized.put(key,
					Set.of("customerId", "orderId").contains(key) ? value : "[REDACTED]"));
			return objectMapper.writeValueAsString(sanitized);
		} catch (JsonProcessingException exception) {
			return "[INVALID_JSON]";
		}
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000;
	}
}
