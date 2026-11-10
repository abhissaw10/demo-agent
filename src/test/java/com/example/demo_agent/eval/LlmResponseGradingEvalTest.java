package com.example.demo_agent.eval;

import com.example.demo_agent.DemoAgentApplication;
import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.client.SupportChatApiClient;
import com.example.demo_agent.config.SupportAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-eval")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVALS", matches = "(?i)true")
@ResourceLock("demo-agent-live-eval-server")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = DemoAgentApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = {
				"server.port=${LIVE_EVAL_PORT:18080}",
				"support.order-api.base-url=http://localhost:${LIVE_EVAL_PORT:18080}",
				"support.chat-api.base-url=http://localhost:${LIVE_EVAL_PORT:18080}",
				"support.agent.max-iterations=4",
				"support.llm.max-retries=0",
				"spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/demo-agent-response-eval.db"
		})
class LlmResponseGradingEvalTest {
	private static final int MAX_EVALUATOR_ITERATIONS = 4;
	private static final Path REPORT_DIRECTORY = Path.of("target", "eval-reports");
	private static final Path JSON_REPORT = REPORT_DIRECTORY.resolve("llm-response-grading.json");
	private static final Path HTML_REPORT = REPORT_DIRECTORY.resolve("llm-response-grading.html");

	private static final String RUBRIC = """
			Evaluation rubric:
			1. Was the response factually grounded in the tool results?
			2. Did it answer the customer's question?
			3. Was the explanation understandable?
			4. Did it handle every user intent?
			5. Was the tone appropriate?
			6. Did it make misleading promises?
			7. Did it ask for clarification when necessary?

			Return JSON:
			{
			  "correctness": 0-4,
			  "groundedness": 0-4,
			  "helpfulness": 0-4,
			  "policyCompliance": "PASS|FAIL",
			  "explanation": "..."
			}
			""";

	private static final String JUDGE_INSTRUCTIONS = """
			You are a strict evaluator of an order-support answer. Treat the supplied tool evidence as the
			only source of truth. Apply all seven rubric checks to the question, evidence, and answer.

			Map the checks to the JSON scores as follows:
			- correctness measures whether the answer reaches the right conclusion, states the current order
			  status correctly when asked, handles every user intent, and does not guess when clarification
			  is required.
			- groundedness measures whether every factual statement, date, status, explanation, and claimed
			  action is supported by the tool evidence. Unsupported details must reduce this score.
			- helpfulness measures whether the answer is direct, complete, understandable, appropriately
			  toned, and asks a concise clarification question when the available information is ambiguous.
			- policyCompliance is FAIL if the answer makes a misleading promise or claims an unsupported
			  action was or will be performed, including issuing a refund, contacting a carrier, cancelling,
			  replacing, or reshipping an order. Otherwise it is PASS.

			For each numeric score, use 4 when all relevant checks are satisfied, 3 for a minor issue, 2 for
			a material omission or mixed result, 1 when the answer mostly fails, and 0 when it wholly fails.
			A fluent or polite answer must not receive high scores if it is ungrounded, incomplete, or fails
			to request necessary clarification. The explanation must identify which rubric checks drove any
			deductions.
			Return one JSON object only, with exactly the five requested fields and no Markdown fences.
			""";

	private static final ExpectedRange HIGH_QUALITY = new ExpectedRange(3, 4, 3, 4, 3, 4, "PASS");
	private static final List<GradingCase> CASES = List.of(
			new GradingCase("delivered-order", "CUST-1001",
					"What is the current status of ORD-1001?", HIGH_QUALITY),
			new GradingCase("awaiting-inventory", "CUST-1001",
					"Where is ORD-1002?", HIGH_QUALITY),
			new GradingCase("delivery-agent-unreachable", "CUST-1002",
					"What is happening with ORD-1003?", HIGH_QUALITY),
			new GradingCase("in-transit", "CUST-1002",
					"What is the latest status for ORD-1004?", HIGH_QUALITY),
			new GradingCase("lost-in-transit", "CUST-1003",
					"Where is ORD-1005?", HIGH_QUALITY),
			new GradingCase("list-orders", "CUST-1001",
					"Show my orders", HIGH_QUALITY),
			new GradingCase("refund-eligibility", "CUST-1001",
					"Is ORD-1002 eligible for a refund?", HIGH_QUALITY),
			new GradingCase("exchange-eligibility", "CUST-1001",
					"Can I exchange ORD-1002?", HIGH_QUALITY),
			new GradingCase("clarification-required", "CUST-1001",
					"Where is my order?", HIGH_QUALITY),
			new GradingCase("combined-delivery-and-refund", "CUST-1001",
					"Where is ORD-1002, and is it eligible for a refund?", HIGH_QUALITY)
	);

	@Autowired
	private OpenAIClient client;

	@Autowired
	private SupportAgentProperties properties;

	@Autowired
	private SupportChatApiClient chatClient;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final List<CaseResult> results = new ArrayList<>();

	@BeforeAll
	void initializeReport() throws IOException {
		results.clear();
		writeReport();
	}

	@TestFactory
	Stream<DynamicTest> evaluatorLlmGradesResponsesAgainstToolEvidence() {
		assertThat(CASES).hasSize(10);
		return CASES.stream().map(testCase -> DynamicTest.dynamicTest(testCase.name(),
				() -> runAndRecord(testCase)));
	}

	@AfterAll
	void finalizeReport() throws IOException {
		writeReport();
	}

	private void runAndRecord(GradingCase testCase) throws Throwable {
		try {
			ChatResponse liveResponse = chatClient.chat(
					new ChatRequest(testCase.question(), testCase.customerId()),
					"response-eval-" + testCase.name());
			assertThat(liveResponse.llmCalls())
					.as("live agent LLM calls for %s", testCase.name())
					.isLessThanOrEqualTo(4);
			String toolEvidence = liveToolEvidence(liveResponse);
			EvaluatedGrade evaluated = grade(testCase.question(), toolEvidence, liveResponse.message());
			Grade grade = evaluated.grade();
			List<String> failures = expectationFailures(grade, testCase.expected());
			String status = failures.isEmpty() ? "PASSED" : "FAILED";
			results.add(new CaseResult(testCase.name(), status, testCase.question(),
					toolEvidence, liveResponse.message(), testCase.expected(), grade,
					liveResponse.llmCalls(), evaluated.iterations(), failures, null, Instant.now()));
			writeReport();
			assertThat(failures).as("grading failures for %s", testCase.name()).isEmpty();
		} catch (Throwable failure) {
			if (results.stream().noneMatch(result -> result.name().equals(testCase.name()))) {
				results.add(new CaseResult(testCase.name(), "ERROR", testCase.question(),
						"Live traffic did not complete successfully.", "", testCase.expected(), null,
						0, 0, List.of(), failure.getClass().getSimpleName() + ": " + failure.getMessage(),
						Instant.now()));
				writeReport();
			}
			throw failure;
		}
	}

	private String liveToolEvidence(ChatResponse response) {
		return "Live /chat outcome: " + response.outcome()
				+ "\nResolved intents: " + response.resolvedIntents()
				+ "\nSelected single order ID: " + response.orderId()
				+ " (null means no single order was selected; it does not mean there are no orders)"
				+ "\nVerified tool evidence: " + response.evidence()
				+ "\nRecommendations: " + response.recommendations()
				+ "\nActions requiring approval: " + response.actionsRequiringApproval()
				+ "\nExecuted tool calls: " + response.toolCalls();
	}

	private List<String> expectationFailures(Grade grade, ExpectedRange expected) {
		List<String> failures = new ArrayList<>();
		addRangeFailure(failures, "correctness", grade.correctness(),
				expected.correctnessMin(), expected.correctnessMax());
		addRangeFailure(failures, "groundedness", grade.groundedness(),
				expected.groundednessMin(), expected.groundednessMax());
		addRangeFailure(failures, "helpfulness", grade.helpfulness(),
				expected.helpfulnessMin(), expected.helpfulnessMax());
		if (!expected.policyCompliance().equals(grade.policyCompliance())) {
			failures.add("policyCompliance expected " + expected.policyCompliance()
					+ " but was " + grade.policyCompliance());
		}
		if (grade.explanation() == null || grade.explanation().isBlank()) {
			failures.add("explanation must not be blank");
		}
		return List.copyOf(failures);
	}

	private void addRangeFailure(List<String> failures, String field, int actual, int minimum, int maximum) {
		if (actual < minimum || actual > maximum) {
			failures.add(field + " expected between " + minimum + " and " + maximum + " but was " + actual);
		}
	}

	private EvaluatedGrade grade(String question, String toolEvidence, String answer) throws Exception {
		String input = RUBRIC + "\nCustomer question:\n" + question
				+ "\n\nTool evidence from the live /chat run:\n" + toolEvidence
				+ "\n\nLive answer to grade:\n" + answer;
		Throwable lastFailure = null;
		for (int iteration = 1; iteration <= MAX_EVALUATOR_ITERATIONS; iteration++) {
			try {
				Response response = client.responses().create(ResponseCreateParams.builder()
						.model(properties.llm().model())
						.instructions(JUDGE_INSTRUCTIONS)
						.input(input + (iteration == 1 ? "" : "\n\nPrevious evaluator output was invalid. "
								+ "Return only the exact JSON object requested."))
						.temperature(0.0)
						.maxOutputTokens(500)
						.build());
				JsonNode node = objectMapper.readTree(outputText(response).trim());
				assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder(
						"correctness", "groundedness", "helpfulness", "policyCompliance", "explanation");
				Grade grade = objectMapper.treeToValue(node, Grade.class);
				assertThat(grade.correctness()).isBetween(0, 4);
				assertThat(grade.groundedness()).isBetween(0, 4);
				assertThat(grade.helpfulness()).isBetween(0, 4);
				assertThat(grade.policyCompliance()).isIn("PASS", "FAIL");
				return new EvaluatedGrade(grade, iteration);
			} catch (Exception | AssertionError failure) {
				lastFailure = failure;
			}
		}
		throw new IllegalStateException("Evaluator did not return valid grading JSON within "
				+ MAX_EVALUATOR_ITERATIONS + " iterations.", lastFailure);
	}

	private String outputText(Response response) {
		StringBuilder text = new StringBuilder();
		for (ResponseOutputItem item : response.output()) {
			item.message().stream()
					.flatMap(message -> message.content().stream())
					.flatMap(content -> content.outputText().stream())
					.forEach(output -> text.append(output.text()));
		}
		assertThat(text).as("evaluator response text").isNotEmpty();
		return text.toString();
	}

	private void writeReport() throws IOException {
		long passed = results.stream().filter(result -> "PASSED".equals(result.status())).count();
		long failed = results.stream().filter(result -> "FAILED".equals(result.status())).count();
		long errors = results.stream().filter(result -> "ERROR".equals(result.status())).count();
		EvalReport report = new EvalReport(Instant.now(), CASES.size(), results.size(),
				passed, failed, errors, List.copyOf(results));
		Files.createDirectories(REPORT_DIRECTORY);
		writeAtomically(JSON_REPORT, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
		writeAtomically(HTML_REPORT, renderDashboard(report));
	}

	private void writeAtomically(Path destination, String content) throws IOException {
		Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
		Files.writeString(temporary, content);
		try {
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private String renderDashboard(EvalReport report) {
		StringBuilder rows = new StringBuilder();
		for (CaseResult result : report.results()) {
			String detail = result.error() != null
					? result.error()
					: String.join("; ", result.failures());
			String scores = result.grade() == null ? "-" : "C " + result.grade().correctness()
					+ " / G " + result.grade().groundedness()
					+ " / H " + result.grade().helpfulness()
					+ " / " + result.grade().policyCompliance();
			String iterations = "Agent " + result.agentLlmCalls()
					+ " / Judge " + result.evaluatorIterations();
			rows.append("<tr class=\"").append(result.status().toLowerCase()).append("\">")
					.append("<td><span class=\"status\">").append(html(result.status())).append("</span></td>")
					.append("<td>").append(html(result.name())).append("</td>")
					.append("<td>").append(html(scores)).append("</td>")
					.append("<td>").append(html(iterations)).append("</td>")
					.append("<td>").append(html(detail.isBlank() && result.grade() != null
							? result.grade().explanation() : detail)).append("</td>")
					.append("</tr>");
		}
		if (rows.isEmpty()) {
			rows.append("<tr><td colspan=\"5\" class=\"empty\">Waiting for the first eval result...</td></tr>");
		}
		return """
				<!doctype html>
				<html lang="en">
				<head>
				<meta charset="utf-8">
				<meta http-equiv="refresh" content="2">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<title>LLM response grading</title>
				<style>
				body{font-family:system-ui,sans-serif;margin:0;background:#f6f7fb;color:#172033}
				main{max-width:1100px;margin:32px auto;padding:0 20px}h1{margin-bottom:4px}.muted{color:#64748b}
				.cards{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin:24px 0}
				.card{background:white;border-radius:10px;padding:16px;box-shadow:0 1px 3px #0001}
				.card b{display:block;font-size:26px}.danger b{color:#b42318}.success b{color:#067647}
				table{width:100%;border-collapse:collapse;background:white;box-shadow:0 1px 3px #0001}
				th,td{text-align:left;padding:12px;border-bottom:1px solid #e5e7eb;vertical-align:top}
				tr.failed{background:#fff1f0}tr.error{background:#fff7ed}tr.passed{background:#f0fdf4}
				.status{font-weight:700}.empty{text-align:center;color:#64748b;padding:30px}
				@media(max-width:750px){.cards{grid-template-columns:repeat(2,1fr)}th:nth-child(3),td:nth-child(3){display:none}}
				</style>
				</head><body><main>
				<h1>LLM response grading</h1>
				<p class="muted">Live dashboard · refreshes every 2 seconds · generated %s</p>
				<div class="cards">
				<div class="card"><span>Total</span><b>%d</b></div>
				<div class="card"><span>Completed</span><b>%d</b></div>
				<div class="card success"><span>Passed</span><b>%d</b></div>
				<div class="card danger"><span>Failed</span><b>%d</b></div>
				<div class="card danger"><span>Errors</span><b>%d</b></div>
				</div>
				<table><thead><tr><th>Status</th><th>Case</th><th>Scores</th><th>LLM calls</th><th>Explanation / failure</th></tr></thead>
				<tbody>%s</tbody></table>
				</main></body></html>
				""".formatted(html(report.generatedAt().toString()), report.total(), report.completed(),
				report.passed(), report.failed(), report.errors(), rows);
	}

	private String html(String value) {
		return value == null ? "" : value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	private record GradingCase(String name, String customerId, String question,
			ExpectedRange expected) {
	}

	private record ExpectedRange(int correctnessMin, int correctnessMax,
			int groundednessMin, int groundednessMax,
			int helpfulnessMin, int helpfulnessMax,
			String policyCompliance) {
	}

	private record Grade(int correctness, int groundedness, int helpfulness,
			String policyCompliance, String explanation) {
	}

	private record EvaluatedGrade(Grade grade, int iterations) {
	}

	private record CaseResult(String name, String status, String question, String toolEvidence,
			String answer, ExpectedRange expected, Grade grade, int agentLlmCalls,
			int evaluatorIterations, List<String> failures, String error, Instant completedAt) {
	}

	private record EvalReport(Instant generatedAt, int total, int completed,
			long passed, long failed, long errors, List<CaseResult> results) {
	}
}
