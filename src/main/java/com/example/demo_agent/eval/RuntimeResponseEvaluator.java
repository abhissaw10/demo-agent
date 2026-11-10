package com.example.demo_agent.eval;

import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.config.RuntimeEvalProperties;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.tracing.AgentRunCompletedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "support.runtime-eval", name = "enabled", havingValue = "true")
public class RuntimeResponseEvaluator {

	private static final Logger log = LoggerFactory.getLogger(RuntimeResponseEvaluator.class);
	private static final String JUDGE_INSTRUCTIONS = """
			You are a strict evaluator of an order-support answer. Tool evidence is the only source of truth.
			Check factual grounding, whether the customer's question and every intent were answered, clarity,
			tone, misleading promises, unsupported actions, and whether clarification was requested when needed.
			Score correctness, groundedness, and helpfulness from 0 to 4. Set policyCompliance to FAIL for a
			misleading promise or unsupported claimed action; otherwise set it to PASS. Return exactly one JSON
			object with fields correctness, groundedness, helpfulness, policyCompliance, and explanation.
			""";
	private static final String RUBRIC = """
			1. Was the response factually grounded in the tool results?
			2. Did it answer the customer's question?
			3. Was the explanation understandable?
			4. Did it handle every user intent?
			5. Was the tone appropriate?
			6. Did it make misleading promises?
			7. Did it ask for clarification when necessary?
			""";

	private final OpenAIClient client;
	private final RuntimeEvalProperties properties;
	private final SupportAgentProperties supportProperties;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final ThreadPoolExecutor executor;
	private final Object resultsLock = new Object();
	private final Deque<RuntimeEvalResult> results = new ArrayDeque<>();
	private final AtomicLong observed = new AtomicLong();
	private final AtomicLong sampled = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();

	public RuntimeResponseEvaluator(OpenAIClient client, RuntimeEvalProperties properties,
			SupportAgentProperties supportProperties) {
		this.client = client;
		this.properties = validate(properties);
		this.supportProperties = supportProperties;
		AtomicLong threadOrdinal = new AtomicLong();
		ThreadFactory threadFactory = task -> {
			Thread thread = new Thread(task, "runtime-eval-" + threadOrdinal.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		this.executor = new ThreadPoolExecutor(properties.workers(), properties.workers(),
				0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.queueCapacity()),
				threadFactory, new ThreadPoolExecutor.AbortPolicy());
	}

	@EventListener
	public void onAgentRunCompleted(AgentRunCompletedEvent event) {
		observed.incrementAndGet();
		if (ThreadLocalRandom.current().nextDouble() >= properties.sampleRate()) {
			return;
		}
		sampled.incrementAndGet();
		try {
			executor.execute(() -> evaluate(event));
		} catch (RejectedExecutionException exception) {
			dropped.incrementAndGet();
			log.warn("Runtime eval queue is full; dropping runId={}", event.context().runId());
		}
	}

	public RuntimeEvalSnapshot snapshot() {
		List<RuntimeEvalResult> copy;
		synchronized (resultsLock) {
			copy = List.copyOf(results);
		}
		long failed = copy.stream().filter(result -> "FAILED".equals(result.status())).count();
		long errors = copy.stream().filter(result -> "ERROR".equals(result.status())).count();
		return new RuntimeEvalSnapshot(Instant.now(), properties.sampleRate(), observed.get(), sampled.get(),
				dropped.get(), executor.getQueue().size(), copy.size(), failed, errors, copy);
	}

	private void evaluate(AgentRunCompletedEvent event) {
		RuntimeEvalResult result;
		try {
			EvaluatedGrade evaluated = grade(event);
			Grade grade = evaluated.grade();
			String status = grade.correctness() >= 3 && grade.groundedness() >= 3
					&& grade.helpfulness() >= 3 && "PASS".equals(grade.policyCompliance())
					? "PASSED" : "FAILED";
			result = result(event, status, grade, evaluated.iterations(), null);
		} catch (Exception exception) {
			log.warn("Runtime response evaluation failed runId={}", event.context().runId(), exception);
			result = result(event, "ERROR", null, properties.maxIterations(),
					exception.getClass().getSimpleName() + ": " + exception.getMessage());
		}
		synchronized (resultsLock) {
			results.addFirst(result);
			while (results.size() > properties.maxResults()) {
				results.removeLast();
			}
		}
		writeReport();
	}

	private EvaluatedGrade grade(AgentRunCompletedEvent event) throws Exception {
		String input = RUBRIC + "\nCustomer question:\n" + event.request().message()
				+ "\n\nVerified live tool evidence:\n" + evidence(event.response())
				+ "\n\nLive answer:\n" + event.response().message();
		Exception lastFailure = null;
		for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
			try {
				Response response = client.responses().create(ResponseCreateParams.builder()
						.model(supportProperties.llm().model())
						.instructions(JUDGE_INSTRUCTIONS)
						.input(input + (iteration == 1 ? "" : "\nReturn valid JSON only."))
						.temperature(0.0)
						.maxOutputTokens(500)
						.build());
				JsonNode node = objectMapper.readTree(outputText(response));
				if (!fields(node).equals(List.of("correctness", "explanation", "groundedness",
						"helpfulness", "policyCompliance"))) {
					throw new IllegalArgumentException("Evaluator JSON fields did not match the contract.");
				}
				Grade grade = objectMapper.treeToValue(node, Grade.class);
				validate(grade);
				return new EvaluatedGrade(grade, iteration);
			} catch (Exception exception) {
				lastFailure = exception;
			}
		}
		throw new IllegalStateException("Evaluator exceeded its bounded iteration limit.", lastFailure);
	}

	private List<String> fields(JsonNode node) {
		List<String> fields = new ArrayList<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields.stream().sorted().toList();
	}

	private void validate(Grade grade) {
		if (grade.correctness() < 0 || grade.correctness() > 4
				|| grade.groundedness() < 0 || grade.groundedness() > 4
				|| grade.helpfulness() < 0 || grade.helpfulness() > 4
				|| !("PASS".equals(grade.policyCompliance()) || "FAIL".equals(grade.policyCompliance()))
				|| grade.explanation() == null || grade.explanation().isBlank()) {
			throw new IllegalArgumentException("Evaluator JSON contained invalid grading values.");
		}
	}

	private String outputText(Response response) {
		StringBuilder text = new StringBuilder();
		for (ResponseOutputItem item : response.output()) {
			item.message().stream().flatMap(message -> message.content().stream())
					.flatMap(content -> content.outputText().stream())
					.forEach(output -> text.append(output.text()));
		}
		if (text.isEmpty()) {
			throw new IllegalArgumentException("Evaluator returned no response text.");
		}
		return text.toString().trim();
	}

	private String evidence(ChatResponse response) {
		return "Outcome: " + response.outcome()
				+ "\nSelected single order ID: " + response.orderId()
				+ " (null means no single order was selected; it does not mean the customer has no orders)"
				+ "\nResolved intents: " + response.resolvedIntents()
				+ "\nEvidence: " + response.evidence()
				+ "\nRecommendations: " + response.recommendations()
				+ "\nActions requiring approval: " + response.actionsRequiringApproval();
	}

	private RuntimeEvalResult result(AgentRunCompletedEvent event, String status, Grade grade,
			int evaluatorIterations, String error) {
		return new RuntimeEvalResult(event.context().runId().toString(), event.context().traceId(),
				event.completedAt(), status, event.request().message(), event.response().message(),
				event.response().outcome(), event.response().evidence(), event.response().llmCalls(),
				event.response().toolCalls(), evaluatorIterations, grade, error);
	}

	private void writeReport() {
		try {
			Path directory = Path.of(properties.reportDirectory());
			Files.createDirectories(directory);
			Path destination = directory.resolve("runtime-eval-results.json");
			Path temporary = directory.resolve("runtime-eval-results.json.tmp");
			Files.writeString(temporary,
					objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot()));
			try {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			log.warn("Could not persist runtime eval report", exception);
		}
	}

	private RuntimeEvalProperties validate(RuntimeEvalProperties value) {
		if (value.sampleRate() < 0 || value.sampleRate() > 1) {
			throw new IllegalArgumentException("support.runtime-eval.sample-rate must be between 0 and 1.");
		}
		if (value.maxIterations() < 1 || value.maxIterations() >= 5) {
			throw new IllegalArgumentException("support.runtime-eval.max-iterations must be between 1 and 4.");
		}
		if (value.maxResults() < 1 || value.workers() < 1 || value.queueCapacity() < 1) {
			throw new IllegalArgumentException("Runtime eval capacity settings must be positive.");
		}
		return value;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}

	private record EvaluatedGrade(Grade grade, int iterations) {
	}

	public record Grade(int correctness, int groundedness, int helpfulness,
			String policyCompliance, String explanation) {
	}

	public record RuntimeEvalResult(String runId, String traceId, Instant completedAt, String status,
			String question, String answer, String outcome, List<String> evidence, int agentLlmCalls,
			int toolCalls, int evaluatorIterations, Grade grade, String error) {
	}

	public record RuntimeEvalSnapshot(Instant generatedAt, double sampleRate, long observed,
			long sampled, long dropped, int queued, int retained, long failed, long errors,
			List<RuntimeEvalResult> results) {
	}
}
