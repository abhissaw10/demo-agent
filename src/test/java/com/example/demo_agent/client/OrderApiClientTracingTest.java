package com.example.demo_agent.client;

import com.example.demo_agent.config.SupportAgentProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderApiClientTracingTest {

	private HttpServer server;
	private final AtomicReference<String> receivedTraceId = new AtomicReference<>();
	private final AtomicReference<String> receivedSpanId = new AtomicReference<>();
	private final AtomicReference<String> receivedSessionId = new AtomicReference<>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/policies/refund", exchange -> {
			receivedTraceId.set(exchange.getRequestHeaders().getFirst(CorrelationFilter.TRACE_ID_HEADER));
			receivedSpanId.set(exchange.getRequestHeaders().getFirst(CorrelationFilter.SPAN_ID_HEADER));
			receivedSessionId.set(exchange.getRequestHeaders().getFirst(CorrelationFilter.SESSION_ID_HEADER));
			byte[] response = "verified refund policy".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.createContext("/customers/CUST-404/orders", exchange -> {
			byte[] response = """
					{"timestamp":"2026-11-08T12:00:00Z","status":404,"error":"Not Found",
					"code":"CUSTOMER_ORDERS_NOT_FOUND","message":"No orders were found.",
					"path":"/customers/CUST-404/orders","traceId":"trace-resource"}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(404, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		MDC.clear();
		server.stop(0);
	}

	@Test
	void publishesAResourceChildSpanAndPropagatesItToTheHttpRequest() {
		CapturingPublisher publisher = new CapturingPublisher();
		OrderApiClient client = new OrderApiClient(RestClient.builder(), properties(), publisher);
		UUID runId = UUID.randomUUID();
		MDC.put("supportRunId", runId.toString());
		MDC.put("supportTraceId", "trace-resource");
		MDC.put("supportSpanId", "tool-span");
		MDC.put("sessionId", "session-resource");
		MDC.put("supportAgentIteration", "3");
		MDC.put("supportToolCallId", "tool-call-refund");
		MDC.put("supportToolName", "get_refund_policy");

		String policy = client.getRefundPolicy();

		assertThat(policy).isEqualTo("verified refund policy");
		assertThat(publisher.events).hasSize(2);
		assertThat(publisher.events).extracting(ResourceCallEvent::status)
				.containsExactly(ResourceCallEvent.Status.STARTED, ResourceCallEvent.Status.COMPLETED);
		ResourceCallEvent started = publisher.events.getFirst();
		ResourceCallEvent completed = publisher.events.getLast();
		assertThat(started.runId()).isEqualTo(runId);
		assertThat(started.sessionId()).isEqualTo("session-resource");
		assertThat(started.traceId()).isEqualTo("trace-resource");
		assertThat(started.parentSpanId()).isEqualTo("tool-span");
		assertThat(started.agentIteration()).isEqualTo(3);
		assertThat(started.toolCallId()).isEqualTo("tool-call-refund");
		assertThat(started.operation()).isEqualTo("GET_REFUND_POLICY");
		assertThat(started.resource()).isEqualTo("/policies/refund");
		assertThat(completed.spanId()).isEqualTo(started.spanId());
		assertThat(completed.success()).isTrue();
		assertThat(receivedTraceId).hasValue("trace-resource");
		assertThat(receivedSpanId).hasValue(started.spanId());
		assertThat(receivedSessionId).hasValue("session-resource");
		assertThat(MDC.get("supportSpanId")).isEqualTo("tool-span");
	}

	@Test
	void preservesTheDownstreamDomainErrorCodeForNoOrders() {
		CapturingPublisher publisher = new CapturingPublisher();
		OrderApiClient client = new OrderApiClient(RestClient.builder(), properties(), publisher);
		MDC.put("supportSpanId", "tool-span");

		assertThatThrownBy(() -> client.getCustomerOrders("CUST-404"))
				.isInstanceOfSatisfying(DownstreamApiException.class, exception ->
						assertThat(exception.getCode()).isEqualTo("CUSTOMER_ORDERS_NOT_FOUND"));

		assertThat(publisher.events).hasSize(2);
		assertThat(publisher.events.getLast().success()).isFalse();
		assertThat(publisher.events.getLast().errorCode()).isEqualTo("CUSTOMER_ORDERS_NOT_FOUND");
		assertThat(MDC.get("supportSpanId")).isEqualTo("tool-span");
	}

	private SupportAgentProperties properties() {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		return new SupportAgentProperties(
				new SupportAgentProperties.OrderApi(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(1)),
				new SupportAgentProperties.ChatApi(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(1)),
				new SupportAgentProperties.Llm("test", baseUrl, "test-model", Duration.ofSeconds(1), 0),
				new SupportAgentProperties.Agent(5, 8, 2, 6));
	}

	private static final class CapturingPublisher implements AgentEventPublisher {
		private final List<ResourceCallEvent> events = new ArrayList<>();

		@Override
		public void publishResourceCall(ResourceCallEvent event) {
			events.add(event);
		}
	}
}
