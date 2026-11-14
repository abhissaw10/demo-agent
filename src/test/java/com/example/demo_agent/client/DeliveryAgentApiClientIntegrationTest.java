package com.example.demo_agent.client;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.AgentInvocationHeaders;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationOutcome;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationType;
import com.example.demo_agent.config.DeliveryAgentApiProperties;
import com.example.demo_agent.tracing.AgentEventPublisher;
import com.example.demo_agent.tracing.CorrelationFilter;
import com.example.demo_agent.tracing.ResourceCallEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryAgentApiClientIntegrationTest {

	private static final DeliveryInvestigationRequest REQUEST = new DeliveryInvestigationRequest(
			"CUST-1001", "ORD-1002", DeliveryInvestigationType.CURRENT_STATUS);

	private final AtomicInteger responseStatus = new AtomicInteger();
	private final AtomicReference<String> responseBody = new AtomicReference<>();
	private final AtomicReference<String> receivedTraceId = new AtomicReference<>();
	private final AtomicReference<String> receivedSpanId = new AtomicReference<>();
	private final AtomicReference<String> receivedSessionId = new AtomicReference<>();
	private final AtomicReference<String> receivedParentRunId = new AtomicReference<>();
	private HttpServer server;

	@BeforeEach
	void startServer() throws IOException {
		responseStatus.set(200);
		responseBody.set("""
				{"outcome":"COMPLETED","orderId":"ORD-1002","statusCode":"IN_TRANSIT",
				"summary":"The shipment is in transit.","evidence":[]}
				""");
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/delivery/investigations", exchange -> {
			receivedTraceId.set(exchange.getRequestHeaders().getFirst(CorrelationFilter.TRACE_ID_HEADER));
			receivedSpanId.set(exchange.getRequestHeaders().getFirst(CorrelationFilter.SPAN_ID_HEADER));
			receivedSessionId.set(exchange.getRequestHeaders().getFirst(CorrelationFilter.SESSION_ID_HEADER));
			receivedParentRunId.set(exchange.getRequestHeaders().getFirst(
					AgentInvocationHeaders.PARENT_RUN_ID));
			byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		MDC.clear();
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void invokesTheRemoteAgentWithCorrelationHeadersAndReturnsItsTypedResponse() {
		CapturingPublisher publisher = new CapturingPublisher();
		UUID parentRunId = UUID.randomUUID();
		DeliveryAgentApiClient client = client(publisher);
		MDC.put("supportSpanId", "support-run-span");
		MDC.put("agentVersion", "support-agent-v1");

		DeliveryInvestigationResult result = client.investigate(REQUEST,
				context(parentRunId), 2);

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.COMPLETED);
		assertThat(receivedTraceId).hasValue("trace-1");
		assertThat(receivedSessionId).hasValue("session-1");
		assertThat(receivedParentRunId).hasValue(parentRunId.toString());
		assertThat(receivedSpanId.get()).matches("[0-9a-f]{16}");
		assertThat(MDC.get("supportSpanId")).isEqualTo("support-run-span");
		assertThat(publisher.events).hasSize(2);
		assertThat(publisher.events.getLast().success()).isTrue();
		assertThat(publisher.events.getFirst().operation()).isEqualTo("INVOKE_DELIVERY_AGENT");
		assertThat(publisher.events.getFirst().agentVersion()).isEqualTo("support-agent-v1");
		assertThat(publisher.events.getFirst().parentSpanId()).isEqualTo("support-run-span");
		assertThat(publisher.events.getFirst().toolCallId()).isNull();
		assertThat(publisher.events.getFirst().toolName()).isNull();
		assertThat(publisher.events.getFirst().spanId()).isEqualTo(receivedSpanId.get());
	}

	@Test
	void convertsAnUnexpectedResponseToATypedTemporaryFailure() {
		responseBody.set("""
				{"outcome":"COMPLETED","orderId":"ORD-WRONG","statusCode":"DELIVERED",
				"summary":"Wrong order.","evidence":[]}
				""");
		CapturingPublisher publisher = new CapturingPublisher();

		DeliveryInvestigationResult result = client(publisher).investigate(
				REQUEST, context(UUID.randomUUID()));

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE);
		assertThat(result.orderId()).isEqualTo("ORD-1002");
		assertThat(result.evidence()).isEmpty();
		assertThat(publisher.events.getLast().success()).isFalse();
		assertThat(publisher.events.getLast().errorCode()).isEqualTo("INVALID_DELIVERY_AGENT_RESPONSE");
	}

	@Test
	void convertsMalformedJsonToATypedTemporaryFailure() {
		responseBody.set("not-json");
		CapturingPublisher publisher = new CapturingPublisher();

		DeliveryInvestigationResult result = client(publisher).investigate(
				REQUEST, context(UUID.randomUUID()));

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE);
		assertThat(publisher.events.getLast().success()).isFalse();
		assertThat(publisher.events.getLast().errorCode()).isEqualTo("INVALID_DELIVERY_AGENT_RESPONSE");
	}

	@Test
	void convertsAnHttpFailureToATypedTemporaryFailure() {
		responseStatus.set(503);
		responseBody.set("""
				{"error":"delivery service unavailable"}
				""");
		CapturingPublisher publisher = new CapturingPublisher();

		DeliveryInvestigationResult result = client(publisher).investigate(
				REQUEST, context(UUID.randomUUID()));

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE);
		assertThat(publisher.events.getLast().success()).isFalse();
		assertThat(publisher.events.getLast().errorCode()).isEqualTo("DELIVERY_AGENT_HTTP_503");
	}

	@Test
	void convertsANetworkFailureToATypedTemporaryFailure() {
		CapturingPublisher publisher = new CapturingPublisher();
		DeliveryAgentApiClient client = client(publisher);
		server.stop(0);
		server = null;

		DeliveryInvestigationResult result = client.investigate(
				REQUEST, context(UUID.randomUUID()));

		assertThat(result.outcome()).isEqualTo(DeliveryInvestigationOutcome.TEMPORARILY_UNAVAILABLE);
		assertThat(result.summary()).contains("temporarily unavailable");
		assertThat(publisher.events.getLast().success()).isFalse();
		assertThat(publisher.events.getLast().errorCode()).isEqualTo("DELIVERY_AGENT_UNAVAILABLE");
	}

	private DeliveryAgentApiClient client(AgentEventPublisher publisher) {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		return new DeliveryAgentApiClient(RestClient.builder(),
				new DeliveryAgentApiProperties(baseUrl, Duration.ofMillis(250), Duration.ofMillis(500)),
				publisher);
	}

	private AgentInvocationContext context(UUID parentRunId) {
		return new AgentInvocationContext(parentRunId, "session-1", "trace-1",
				"support-run-span", Instant.now());
	}

	private static final class CapturingPublisher implements AgentEventPublisher {
		private final List<ResourceCallEvent> events = new ArrayList<>();

		@Override
		public void publishResourceCall(ResourceCallEvent event) {
			events.add(event);
		}
	}
}
