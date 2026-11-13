package com.example.demo_agent.eval;

import com.example.demo_agent.DemoAgentApplication;
import com.example.demo_agent.agent.SupportAgentService;
import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.InvestigateDeliveryTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import com.example.demo_agent.tracing.AgentRunCompletedEvent;
import com.example.demo_agent.tracing.CorrelationIds;
import com.example.demo_agent.tracing.LlmCallEvent;
import com.example.demo_agent.tracing.ResourceCallEvent;
import com.example.demo_agent.tracing.ToolCallEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-eval")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVALS", matches = "(?i)true")
@ResourceLock("demo-agent-live-eval-server")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(classes = DemoAgentApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = {
				"server.port=${LIVE_EVAL_PORT:18080}",
				"support.order-api.base-url=http://localhost:${LIVE_EVAL_PORT:18080}",
				"support.agent.max-iterations=4",
				"support.llm.max-retries=0",
				"spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/demo-agent-live-eval.db"
		})
@Import(LiveLlmToolPathEvalTest.EventQueueConfiguration.class)
class LiveLlmToolPathEvalTest {

	@Autowired
	private SupportAgentService agent;

	@Autowired
	private AgentEventQueue eventQueue;

	@Test
	void eval01_listOrdersUsesOnlyCustomerOrders() {
		assertPath(run("eval-list", "CUST-1001", "Show my orders"), 2,
				GetCustomerOrdersTool.NAME);
	}

	@Test
	void eval02_trackingUsesOwnershipThenDelivery() {
		assertPath(run("eval-track", "CUST-1001", "Where is ORD-1002?"), 3,
				GetCustomerOrdersTool.NAME, InvestigateDeliveryTool.NAME);
	}

	@Test
	void eval03_refundEligibilityUsesTheRefundPath() {
		assertPath(run("eval-refund", "CUST-1001", "Is ORD-1002 eligible for a refund?"), 4,
				GetCustomerOrdersTool.NAME, InvestigateDeliveryTool.NAME, GetRefundPolicyTool.NAME);
	}

	@Test
	void eval04_exchangeEligibilityUsesTheExchangePath() {
		assertPath(run("eval-exchange", "CUST-1001", "Can I exchange ORD-1002?"), 4,
				GetCustomerOrdersTool.NAME, InvestigateDeliveryTool.NAME, GetExchangePolicyTool.NAME);
	}

	@Test
	void eval05_noOrdersStopsAfterCustomerOrders() {
		assertPath(run("eval-no-orders", "CUST-404", "Where is ORD-404?"), 1,
				GetCustomerOrdersTool.NAME);
	}

	@Test
	void eval06_noUnfulfilledOrdersPreventsRefundTools() {
		assertPath(run("eval-no-refund", "CUST-1004", "Can I get a refund for ORD-1006?"), 1,
				GetCustomerOrdersTool.NAME);
	}

	@Test
	void eval07_noUnfulfilledOrdersPreventsExchangeTools() {
		assertPath(run("eval-no-exchange", "CUST-1004", "Can I exchange ORD-1006?"), 1,
				GetCustomerOrdersTool.NAME);
	}

	@Test
	void eval08_combinedDeliveryAndRefundDoesNotDuplicateTools() {
		assertPath(run("eval-combined", "CUST-1001",
				"Where is ORD-1002, and is it eligible for a refund?"), 4,
				GetCustomerOrdersTool.NAME, InvestigateDeliveryTool.NAME, GetRefundPolicyTool.NAME);
	}

	@Test
	void eval09_followUpUsesOnlyTheNewlyNeededPolicyTool() {
		String sessionId = "eval-context-policy";
		run(sessionId, "CUST-1001", "Where is ORD-1002?");

		assertPath(run(sessionId, "CUST-1001", "Can I get a refund for it?"), 2,
				GetRefundPolicyTool.NAME);
	}

	@Test
	void eval10_followUpAnswerableFromContextUsesNoTools() {
		String sessionId = "eval-context-only";
		run(sessionId, "CUST-1001", "Where is ORD-1002?");

		assertPath(run(sessionId, "CUST-1001", "Which order did we just discuss?"), 1);
	}

	private EvalRun run(String sessionId, String customerId, String message) {
		eventQueue.clear();
		String traceId = CorrelationIds.newTraceId();
		String rootSpanId = CorrelationIds.newSpanId();
		ChatResponse response;
		try (MDC.MDCCloseable ignoredTrace = MDC.putCloseable("supportTraceId", traceId);
			 MDC.MDCCloseable ignoredSpan = MDC.putCloseable("supportSpanId", rootSpanId);
			 MDC.MDCCloseable ignoredSession = MDC.putCloseable("sessionId", sessionId)) {
			response = agent.chat(new ChatRequest(message, customerId), sessionId);
		}
		return new EvalRun(traceId, rootSpanId, sessionId, response, eventQueue.drain());
	}

	private void assertPath(EvalRun run, int expectedLogicalLlmCalls, String... expectedTools) {
		List<LlmCallEvent> allLlmEvents = run.eventsOf(LlmCallEvent.class);
		List<LlmCallEvent> llmEvents = allLlmEvents.stream()
				.filter(event -> "stage-2".equals(event.agentVersion()))
				.filter(event -> event.status() == LlmCallEvent.Status.COMPLETED)
				.toList();
		List<ToolCallEvent> allToolEvents = run.eventsOf(ToolCallEvent.class);
		List<ToolCallEvent> toolEvents = allToolEvents.stream()
				.filter(event -> "stage-2".equals(event.agentVersion()))
				.filter(event -> event.status() == ToolCallEvent.Status.COMPLETED)
				.toList();
		List<ToolCallEvent> completedToolEvents = allToolEvents.stream()
				.filter(event -> event.status() == ToolCallEvent.Status.COMPLETED)
				.toList();
		List<ResourceCallEvent> allResourceEvents = run.eventsOf(ResourceCallEvent.class);
		List<ResourceCallEvent> resourceEvents = allResourceEvents.stream()
				.filter(event -> event.status() == ResourceCallEvent.Status.COMPLETED)
				.toList();

		assertPairedLifecycleEvents(allLlmEvents, LlmCallEvent::llmCallId);
		assertPairedLifecycleEvents(allToolEvents, ToolCallEvent::toolCallId);
		assertPairedLifecycleEvents(allResourceEvents, ResourceCallEvent::spanId);
		assertThat(llmEvents).allMatch(event -> Boolean.TRUE.equals(event.success()));
		assertThat(llmEvents).hasSize(expectedLogicalLlmCalls);
		int actualProviderCalls = llmEvents.stream().mapToInt(LlmCallEvent::providerCallCount).sum();
		assertThat(actualProviderCalls).isGreaterThanOrEqualTo(expectedLogicalLlmCalls);
		assertThat(toolEvents).extracting(ToolCallEvent::toolName)
				.containsExactly(expectedTools);
		assertThat(toolEvents).extracting(ToolCallEvent::toolName)
				.doesNotHaveDuplicates();
		assertThat(run.response().llmCalls()).isEqualTo(actualProviderCalls);
		assertThat(run.response().toolCalls()).isEqualTo(expectedTools.length);

		Map<String, Long> counts = toolEvents.stream().collect(Collectors.groupingBy(
				ToolCallEvent::toolName, Collectors.counting()));
		assertThat(counts).allSatisfy((tool, count) -> assertThat(count)
				.as("number of calls to %s", tool).isEqualTo(1));

		assertThat(toolEvents).allSatisfy(tool -> {
			assertThat(tool.traceId()).isEqualTo(run.traceId());
			assertThat(tool.sessionId()).isEqualTo(run.sessionId());
			assertThat(tool.parentSpanId()).isEqualTo(run.rootSpanId());
			assertThat(tool.spanId()).isNotEqualTo(run.rootSpanId());
			assertThat(llmEvents).anySatisfy(llm -> {
				assertThat(tool.originatingLlmCallId()).isEqualTo(llm.llmCallId());
				assertThat(tool.originatingLlmSpanId()).isEqualTo(llm.spanId());
				assertThat(llm.requestedToolCallIds()).contains(tool.toolCallId());
			});
		});
		assertThat(toolEvents).extracting(ToolCallEvent::spanId).doesNotHaveDuplicates();

		assertThat(resourceEvents).extracting(ResourceCallEvent::operation)
				.containsExactlyElementsOf(expectedResourceOperations(expectedTools));
		assertThat(resourceEvents).allSatisfy(resource -> {
			assertThat(resource.traceId()).isEqualTo(run.traceId());
			assertThat(resource.sessionId()).isEqualTo(run.sessionId());
			assertThat(resource.spanId()).isNotEqualTo(resource.parentSpanId());
			assertThat(completedToolEvents).anySatisfy(tool -> {
				assertThat(resource.toolCallId()).isEqualTo(tool.toolCallId());
				assertThat(resource.toolName()).isEqualTo(tool.toolName());
				assertThat(resource.parentSpanId()).isEqualTo(tool.spanId());
			});
		});

		List<AgentRunCompletedEvent> completedRuns = run.eventsOf(AgentRunCompletedEvent.class);
		assertThat(completedRuns).singleElement().satisfies(completed -> {
			assertThat(completed.context().traceId()).isEqualTo(run.traceId());
			assertThat(completed.context().sessionId()).isEqualTo(run.sessionId());
			assertThat(completed.context().rootSpanId()).isEqualTo(run.rootSpanId());
		});
	}

	private <T> void assertPairedLifecycleEvents(List<T> events, Function<T, String> id) {
		Map<String, Long> counts = events.stream().collect(Collectors.groupingBy(id, Collectors.counting()));
		assertThat(counts).allSatisfy((eventId, count) -> assertThat(count)
				.as("STARTED and COMPLETED events for %s", eventId).isEqualTo(2));
	}

	private List<String> expectedResourceOperations(String... tools) {
		return java.util.Arrays.stream(tools).flatMap(tool -> switch (tool) {
			case GetCustomerOrdersTool.NAME -> java.util.stream.Stream.of("GET_CUSTOMER_ORDERS");
			case InvestigateDeliveryTool.NAME -> java.util.stream.Stream.of(
					"GET_CUSTOMER_ORDERS", "GET_DELIVERY_STATUS");
			case GetRefundPolicyTool.NAME -> java.util.stream.Stream.of("GET_REFUND_POLICY");
			case GetExchangePolicyTool.NAME -> java.util.stream.Stream.of("GET_EXCHANGE_POLICY");
			default -> throw new IllegalArgumentException("Unexpected tool " + tool);
		}).toList();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class EventQueueConfiguration {
		@Bean
		AgentEventQueue agentEventQueue() {
			return new AgentEventQueue();
		}
	}

	private record EvalRun(String traceId, String rootSpanId, String sessionId,
			ChatResponse response, List<Object> events) {

		<T> List<T> eventsOf(Class<T> type) {
			return events.stream().filter(type::isInstance).map(type::cast).toList();
		}
	}
}
