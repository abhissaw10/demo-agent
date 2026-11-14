package com.example.demo_agent.client;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.AgentInvocationHeaders;
import com.example.demo_agent.agent.delivery.DeliveryAgentEndpoints;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationOutcome;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.config.DeliveryAgentApiProperties;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.CorrelationFilter;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.ResourceCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;

/** HTTP boundary from the Support application to the separately deployed Delivery Agent. */
@Component
public class DeliveryAgentApiClient {

	private static final Logger log = LoggerFactory.getLogger(DeliveryAgentApiClient.class);

	private final RestClient restClient;
	private final AgentEventPublisher eventPublisher;
	private final String endpoint;

	public DeliveryAgentApiClient(RestClient.Builder builder, DeliveryAgentApiProperties properties,
			AgentEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
		this.endpoint = properties.baseUrl() + DeliveryAgentEndpoints.INVESTIGATIONS;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = builder.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	public DeliveryInvestigationResult investigate(DeliveryInvestigationRequest request,
			AgentInvocationContext context) {
		return investigate(request, context, 0);
	}

	public DeliveryInvestigationResult investigate(DeliveryInvestigationRequest request,
			AgentInvocationContext context, int agentIteration) {
		String parentSpanId = context.parentSpanId();
		String resourceSpanId = CorrelationIds.newSpanId();
		ResourceCallEvent event = ResourceCallEvent.started(context.parentRunId(),
				MDC.get("agentVersion"), context.sessionId(), context.traceId(), resourceSpanId,
				parentSpanId, agentIteration, null, null, "HTTP", "INVOKE_DELIVERY_AGENT",
				DeliveryAgentEndpoints.INVESTIGATIONS, Instant.now());
		try {
			eventPublisher.publishResourceCall(event);
			MDC.put("supportSpanId", resourceSpanId);
			DeliveryInvestigationResult response = restClient.post()
					.uri(DeliveryAgentEndpoints.INVESTIGATIONS)
					.header(CorrelationFilter.TRACE_ID_HEADER, context.traceId())
					.header(CorrelationFilter.SPAN_ID_HEADER, resourceSpanId)
					.header(CorrelationFilter.SESSION_ID_HEADER, context.sessionId())
					.header(AgentInvocationHeaders.PARENT_RUN_ID, context.parentRunId().toString())
					.body(request)
					.retrieve()
					.body(DeliveryInvestigationResult.class);
			validate(response, request);
			eventPublisher.publishResourceCall(event.completed(Instant.now(), true, null));
			return response;
		} catch (UnexpectedDeliveryAgentResponseException exception) {
			log.warn("Delivery Agent returned an invalid response endpoint={} orderId={} reason={}",
					endpoint, request.orderId(), exception.getMessage());
			eventPublisher.publishResourceCall(event.completed(
					Instant.now(), false, "INVALID_DELIVERY_AGENT_RESPONSE"));
			return unavailable(request.orderId());
		} catch (RestClientResponseException exception) {
			log.warn("Delivery Agent HTTP request failed endpoint={} orderId={} status={}",
					endpoint, request.orderId(), exception.getStatusCode().value());
			eventPublisher.publishResourceCall(event.completed(
					Instant.now(), false, "DELIVERY_AGENT_HTTP_" + exception.getStatusCode().value()));
			return unavailable(request.orderId());
		} catch (RestClientException exception) {
			if (hasCause(exception, HttpMessageConversionException.class)) {
				log.warn("Delivery Agent response could not be decoded endpoint={} orderId={} errorType={}",
						endpoint, request.orderId(), exception.getClass().getSimpleName());
				eventPublisher.publishResourceCall(event.completed(
						Instant.now(), false, "INVALID_DELIVERY_AGENT_RESPONSE"));
				return unavailable(request.orderId());
			}
			log.warn("Delivery Agent network request failed endpoint={} orderId={} errorType={} reason={}",
					endpoint, request.orderId(), exception.getClass().getSimpleName(),
					exception.getMessage());
			eventPublisher.publishResourceCall(event.completed(
					Instant.now(), false, "DELIVERY_AGENT_UNAVAILABLE"));
			return unavailable(request.orderId());
		} finally {
			restoreSpan(parentSpanId);
		}
	}

	private void validate(DeliveryInvestigationResult response, DeliveryInvestigationRequest request) {
		if (response == null || response.outcome() == null || response.orderId() == null
				|| !request.orderId().equals(response.orderId()) || response.summary() == null
				|| response.summary().isBlank() || response.evidence() == null) {
			throw new UnexpectedDeliveryAgentResponseException(
					"The response was missing required fields or referred to a different order.");
		}
	}

	private DeliveryInvestigationResult unavailable(String orderId) {
		return new DeliveryInvestigationResult(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE,
				orderId, null, "Delivery information is temporarily unavailable.", List.of());
	}

	private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
		Throwable current = failure;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private void restoreSpan(String spanId) {
		if (spanId == null) {
			MDC.remove("supportSpanId");
		} else {
			MDC.put("supportSpanId", spanId);
		}
	}

	private static final class UnexpectedDeliveryAgentResponseException extends RuntimeException {
		private UnexpectedDeliveryAgentResponseException(String message) {
			super(message);
		}
	}
}
