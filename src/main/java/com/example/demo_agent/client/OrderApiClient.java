package com.example.demo_agent.client;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.exception.ApiErrorResponse;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.CorrelationFilter;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.ResourceCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

@Component
public class OrderApiClient {

	private static final Logger log = LoggerFactory.getLogger(OrderApiClient.class);

	private final RestClient restClient;
	private final AgentEventPublisher eventPublisher;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	public OrderApiClient(RestClient.Builder builder, SupportAgentProperties properties,
			AgentEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
		SupportAgentProperties.OrderApi settings = properties.orderApi();
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(settings.connectTimeout());
		requestFactory.setReadTimeout(settings.readTimeout());
		this.restClient = builder.baseUrl(settings.baseUrl())
				.requestFactory(requestFactory)
				.requestInterceptor((request, body, execution) -> {
					setCorrelationHeader(request.getHeaders(), CorrelationFilter.TRACE_ID_HEADER,
							MDC.get("supportTraceId"));
					setCorrelationHeader(request.getHeaders(), CorrelationFilter.SPAN_ID_HEADER,
							MDC.get("supportSpanId"));
					setCorrelationHeader(request.getHeaders(), CorrelationFilter.SESSION_ID_HEADER,
							MDC.get("sessionId"));
					return execution.execute(request, body);
				})
				.build();
	}

	private void setCorrelationHeader(org.springframework.http.HttpHeaders headers, String name, String value) {
		if (value != null && !value.isBlank()) {
			headers.set(name, value);
		}
	}

	public List<CustomerOrderResponse> getCustomerOrders(String customerId) {
		return exchange("GET_CUSTOMER_ORDERS", "/customers/{customerId}/orders", "customer orders",
				() -> restClient.get()
				.uri("/customers/{customerId}/orders", customerId)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw statusFailure("customer orders", response);
				})
				.body(new ParameterizedTypeReference<>() {}));
	}

	public OrderDeliveryStatusResponse getDeliveryStatus(String orderId) {
		return exchange("GET_DELIVERY_STATUS", "/orders/{orderId}/delivery-status", "delivery status",
				() -> restClient.get()
				.uri("/orders/{orderId}/delivery-status", orderId)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw statusFailure("delivery status", response);
				})
				.body(OrderDeliveryStatusResponse.class));
	}

	public String getRefundPolicy() {
		return getPolicy("refund");
	}

	public String getExchangePolicy() {
		return getPolicy("exchange");
	}

	private String getPolicy(String name) {
		return exchange("GET_" + name.toUpperCase(java.util.Locale.ROOT) + "_POLICY",
				"/policies/" + name, name + " policy", () -> restClient.get()
				.uri("/policies/{name}", name)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw statusFailure(name + " policy", response);
				})
				.body(String.class));
	}

	private <T> T exchange(String operation, String resource, String displayName, ApiCall<T> call) {
		String parentSpanId = MDC.get("supportSpanId");
		String resourceSpanId = CorrelationIds.newSpanId();
		ResourceCallEvent event = ResourceCallEvent.started(runId(), MDC.get("agentVersion"), MDC.get("sessionId"),
				MDC.get("supportTraceId"), resourceSpanId, parentSpanId, agentIteration(),
				MDC.get("supportToolCallId"), MDC.get("supportToolName"), "HTTP", operation,
				resource, Instant.now());
		try {
			eventPublisher.publishResourceCall(event);
			MDC.put("supportSpanId", resourceSpanId);
			T value = call.execute();
			if (value == null) {
				throw new DownstreamApiException("INVALID_DOWNSTREAM_RESPONSE",
						"The downstream " + displayName + " response was empty.", false, true, null);
			}
			eventPublisher.publishResourceCall(event.completed(Instant.now(), true, null));
			return value;
		} catch (DownstreamApiException exception) {
			eventPublisher.publishResourceCall(event.completed(Instant.now(), false, exception.getCode()));
			throw exception;
		} catch (RestClientException exception) {
			log.warn("Downstream operation failed operation={}", displayName, exception);
			DownstreamApiException failure = new DownstreamApiException("DEPENDENCY_UNAVAILABLE",
					"The downstream " + displayName + " service is temporarily unavailable.",
					false, true, exception);
			eventPublisher.publishResourceCall(event.completed(Instant.now(), false, failure.getCode()));
			throw failure;
		} finally {
			if (parentSpanId == null) {
				MDC.remove("supportSpanId");
			} else {
				MDC.put("supportSpanId", parentSpanId);
			}
		}
	}

	private UUID runId() {
		String value = MDC.get("supportRunId");
		try {
			return value == null ? null : UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private int agentIteration() {
		String value = MDC.get("supportAgentIteration");
		try {
			return value == null ? 0 : Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private DownstreamApiException statusFailure(String operation, ClientHttpResponse response) {
		final HttpStatusCode status;
		try {
			status = response.getStatusCode();
		} catch (IOException exception) {
			return new DownstreamApiException("DEPENDENCY_UNAVAILABLE",
					"The downstream " + operation + " service returned an unreadable status.",
					false, true, exception);
		}
		boolean rateLimited = status.value() == 429;
		boolean retryable = rateLimited || status.is5xxServerError();
		String fallbackMessage = rateLimited
				? "The downstream " + operation + " service is rate limited."
				: "The downstream " + operation + " service returned HTTP " + status.value() + ".";
		String fallbackCode = rateLimited ? "RATE_LIMITED"
				: (status.is5xxServerError() ? "DEPENDENCY_UNAVAILABLE" : "DOWNSTREAM_REJECTED");
		try {
			ApiErrorResponse error = objectMapper.readValue(response.getBody(), ApiErrorResponse.class);
			String code = error.code() == null || error.code().isBlank() ? fallbackCode : error.code();
			String message = error.message() == null || error.message().isBlank()
					? fallbackMessage : error.message();
			return new DownstreamApiException(code, message, rateLimited, retryable, null);
		} catch (IOException | RuntimeException exception) {
			return new DownstreamApiException(fallbackCode, fallbackMessage,
					rateLimited, retryable, exception);
		}
	}

	@FunctionalInterface
	private interface ApiCall<T> {
		T execute();
	}
}
