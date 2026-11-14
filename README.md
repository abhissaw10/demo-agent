# Order Support and Delivery Agents

This codebase builds two independently deployable Spring Boot applications. The Support application
runs the Stage 1 order APIs, bounded Support Agent, and Vaadin UI on port `8080`. The Delivery
application exposes its own bounded Delivery Agent on port `8081`. Models can request only validated,
read-only tools; neither application exposes refund, cancellation, exchange, replacement, inventory
reservation, or payment operations.

## Requirements

- Java 21
- Maven 3.9+ (or the included Maven Wrapper)
- A Groq API key for live chat

## Configure and run

Build both executable applications while keeping the model key out of source control:

```bash
./mvnw clean package
```

Start the Support application and Delivery application in separate processes:

```bash
GROQ_API_KEY=your-key java -jar target/demo-agent-0.0.1-SNAPSHOT.jar
GROQ_API_KEY=your-key java -jar target/demo-agent-0.0.1-SNAPSHOT-delivery.jar
```

The Support application stays on `8080`. The Delivery executable activates the `delivery` profile
and defaults to `8081`; override it with `DELIVERY_AGENT_PORT`. Support calls it through
`DELIVERY_AGENT_API_BASE_URL`, which defaults to `http://localhost:8081`.

You can instead set `support.llm.api-key` in `application.properties`. The defaults use Groq's
OpenAI-compatible Responses endpoint and `openai/gpt-oss-20b`:

```properties
support.llm.api-key=${GROQ_API_KEY:}
support.llm.base-url=https://api.groq.com/openai/v1
support.llm.model=openai/gpt-oss-20b
```

Open `http://localhost:8080` for the Vaadin chatbot. Enter a seeded customer ID such as
`CUST-1001`, then send with the button or Enter.

Build and test:

```bash
./mvnw test -Dvaadin.skip=true
```

The real-model eval suite is intentionally excluded unless explicitly enabled. It uses the configured
Groq/OpenAI-compatible model and the real application tools; it does not mock `OpenAIClient` or
`LlmSession`:

```bash
RUN_LIVE_EVALS=true GROQ_API_KEY=your-key ./mvnw \
  -Dtest=LiveLlmToolPathEvalTest test
```

Set `LIVE_EVAL_PORT` and `LIVE_DELIVERY_EVAL_PORT` if ports `18080` and `18081` are unavailable.
The live harness starts both applications. The ten live evals assert tool order,
forbidden tools, exact LLM/tool counts, duplicate calls, context reuse, and trace/span/session
correlation. A separate evaluator-LLM suite grades ten representative customer-facing answers for
correctness, grounding in tool evidence, helpfulness, and unsupported action claims:

```bash
RUN_LIVE_EVALS=true GROQ_API_KEY=your-key ./mvnw \
  -Dtest=LlmResponseGradingEvalTest test
```

Each response-grading case sends a real HTTP request to the running `/chat` endpoint. The support
agent calls the configured live LLM and the application’s real order and policy endpoints; no
`OpenAIClient`, `LlmSession`, chat response, or tool result is mocked. A second live LLM call grades
the answer and verified evidence returned by that traffic. The judge checks factual grounding,
directness, clarity, intent coverage, tone, misleading promises, and necessary clarification.

Both live suites cap the support agent at four LLM iterations and disable SDK retries. The evaluator
also has an explicit four-iteration ceiling, used only to recover from malformed grading JSON. Thus
neither the answering agent nor the judge can loop indefinitely, and both remain below five logical
iterations per case.

During a live run, the suite writes each result immediately to:

- `target/eval-reports/llm-response-grading.json` for CI ingestion and historical storage
- `target/eval-reports/llm-response-grading.html` for a local dashboard

Open the HTML file in a browser while the eval is running. It refreshes every two seconds, shows the
agent and evaluator call counts, and marks failed cases red and evaluator errors orange. Both files
are rewritten atomically after every case and once more at suite completion, so an interrupted run
still retains all completed results. Since `target/` is ignored by Git, copy the JSON and HTML files
into CI artifacts when reports need to be retained across builds.

### Continuous runtime evaluation

For production-style evaluation of ordinary `/chat` traffic, enable the asynchronous runtime
evaluator when starting the application:

```bash
RUNTIME_EVAL_ENABLED=true RUNTIME_EVAL_SAMPLE_RATE=1.0 GROQ_API_KEY=your-key \
  ./mvnw spring-boot:run
```

Then use the application normally and open `http://localhost:8080/eval-dashboard`. Every completed
chat request is eligible for background grading, and the dashboard polls for new results every two
seconds. `RUNTIME_EVAL_SAMPLE_RATE` accepts a value from `0.0` to `1.0`; use a lower value such as
`0.05` to grade approximately five percent of requests at higher volumes.

Runtime grading never blocks the customer response. It uses two background workers, a bounded queue
of 100 evaluations, a maximum of four evaluator iterations, and retains the latest 200 results.
When the queue is full, new evaluations are dropped rather than increasing request latency or memory
without bound. The current snapshot is also persisted atomically to
`target/runtime-eval/runtime-eval-results.json`. The dashboard exposes customer questions and agent
answers, so in a real deployment these endpoints must be placed behind internal authentication and
the stored data must follow the organization’s retention and redaction requirements.

## API

| Method | Path | Response |
|---|---|---|
| `POST` | `/chat` | Validated `ChatResponse` with answer, outcome, evidence, recommendations, approval-required actions, and call counts |
| `POST` | `http://localhost:8081/delivery/investigations` | `DeliveryInvestigationResult` containing structured delivery facts for one `DeliveryInvestigationRequest` |
| `GET` | `/customers/{customerId}/orders` | Customer orders and latest overall status |
| `GET` | `/orders/{orderId}/delivery-status` | Chronological delivery history and current status |
| `GET` | `/policies/refund` | Refund policy as Markdown |
| `GET` | `/policies/exchange` | Exchange policy as Markdown |
| `GET` | `/ping` | Plain-text health response |

Chat example:

```bash
curl -s http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-chat-1' \
  -d '{"message":"Where is order ORD-1001?","customerId":"CUST-1001"}'
```

The session header scopes bounded in-memory conversation history by session and customer. If it is
omitted, the service generates one and returns it in `X-Session-Id`. The default keeps the six most
recent accepted turns and is cleared when the application restarts. Accepted turns retain verified
Support-tool evidence in memory, allowing deterministic follow-ups to reuse ownership and policy
facts. Delivery findings are supplied by application orchestration and are not Support tool calls.

## Agent safety and orchestration

The Support Agent allow-list contains exactly these three read-only tools:

- `get_customer_orders` → `GetCustomerOrdersTool.java`
- `get_refund_policy` → `GetRefundPolicyTool.java`
- `get_exchange_policy` → `GetExchangePolicyTool.java`

The model also receives a control-plane `submit_agent_decision` function. Its arguments become the
proposed final response and are passed to `AgentDecisionValidator`; it is never sent to the business
tool executor, does not perform an action, and does not count as a tool call.

`DeliveryInvestigationRequest` and `DeliveryInvestigationResult` remain the agent-to-agent contract.
After `get_customer_orders` establishes the target order, Support application orchestration invokes
`POST /delivery/investigations` directly; the Support model has no Delivery tool or agent function in
its schema. `DeliveryAgentService` owns a separate, bounded LLM iteration loop, a delivery-specific
prompt, order-ownership revalidation, and private delivery resource tools. Its model must call
`resolve_delivery_order`, then
`get_delivery_status_facts` when access is verified, and finally
`submit_delivery_investigation`. Application code validates that sequence and reconstructs evidence
from tool output. Support receives the structured result as application-provided context and remains
solely responsible for customer-facing wording. This keeps support conversation/model state out of
the Delivery Agent and raw tracking data out of the Support Agent.

The Delivery Agent loop has its own budgets and reuses the configured OpenAI-compatible connection:

```properties
delivery.agent.max-iterations=4
delivery.agent.max-tool-calls=2
delivery.agent.max-validation-retries=2
```

The HTTP boundary preserves the caller's `sessionId`, `traceId`, parent run ID, and span lineage.
Support records the outbound request as `INVOKE_DELIVERY_AGENT`; that resource span becomes the
Delivery Agent run's `parentSpanId`. The Delivery Agent then creates its own run ID and root span,
with child spans for its LLM, private tools, and downstream order API calls. Each event includes
`agentVersion`, so distributed logs can be grouped into Support and Delivery call counts:

```text
Support run → Support LLM → ownership tool → application HTTP orchestration :8081
                                                    └─ Delivery run → Delivery LLM → Delivery tool → Order API :8080
```

The loop is bounded by these properties:

```properties
support.agent.max-iterations=5
support.agent.max-tool-calls=8
support.agent.max-validation-retries=2
support.agent.history-turns=6
```

The Vaadin server-side client waits up to 30 seconds for `/chat` responses:

```properties
support.chat-api.connect-timeout=2s
support.chat-api.read-timeout=30s
```

It validates tool names and arguments, detects repeated identical validation failures, executes
genuinely independent calls in parallel, validates the final JSON decision,
prevents silent order selection when multiple orders match, and fails safely on model, downstream,
or rate-limit errors. Follow-up answers are limited to 50 words and rejected for repeated eight-word
sequences from prior assistant messages, so validation can ask the model for a concise correction.
OpenAI SDK retries are deliberately capped by `support.llm.max-retries`.

Set `ORDER_API_BASE_URL` if the Stage 1 APIs run in a separate service. Set `CHAT_API_BASE_URL` only
when the Vaadin UI should call a separately hosted `/chat` API. Set `DELIVERY_AGENT_API_BASE_URL`
when Support should call a Delivery deployment other than `http://localhost:8081`. Connection,
timeout, HTTP, malformed JSON, missing-field, and order-mismatch failures are converted into a typed
`TEMPORARILY_UNAVAILABLE` result rather than leaking transport errors into the Support Agent loop.

## Seed data

- Customers: `CUST-1001`, `CUST-1002`, `CUST-1003`, `CUST-1004`
- Orders: `ORD-1001` through `ORD-1006`
- Status examples include delivered, awaiting inventory refresh, delivery agent unreachable,
  in-transit, and lost in transit.

`CUST-1004` owns only the delivered `ORD-1006` and exists specifically for negative refund and
exchange path evals. `CUST-404` has no orders.

## Eval event bus and event contract

Each application publishes typed events through its own Spring in-process
`ApplicationEventPublisher`. Production listeners can subscribe without coupling either agent loop
to the consumer. Correlation IDs preserve the distributed trace across processes; production logs
or exported telemetry can therefore join Support and Delivery events. The live eval harness uses a
bounded JDK `LinkedBlockingQueue` for deterministic Support-side assertions.

| Event | Trigger | Important attribute values |
|---|---|---|
| `AgentRunStartedEvent` | Immediately before a chat run starts | `runId` is a new UUID; `sessionId` comes from the request; `traceId` and root `spanId` come from MDC/HTTP correlation headers |
| `DelegatedAgentRunStartedEvent` | Immediately after the Delivery REST endpoint accepts an investigation | The Delivery Agent gets a new `runId` and root span, inherits `sessionId`/`traceId`, and records the Support Agent run and outbound HTTP span as `parentRunId`/`parentSpanId` |
| `LlmCallEvent STARTED` | Immediately before each logical `LlmSession.next()` in either agent | New LLM child `spanId`; `parentSpanId` is that agent's run root; agent identity, iteration, logical ordinal, model, and a new `llmCallId` are populated |
| `LlmCallEvent COMPLETED` | Immediately after that logical call returns or fails | Same IDs as STARTED; `providerCallCount` records actual provider calls including explicit rate-limit retries and structured-output fallback; requested tool names/IDs, success, and error type are populated |
| `ToolCallEvent STARTED` | After application path validation and immediately before a tool executes in either agent | Every tool gets a unique child `spanId`; `parentSpanId` is that agent's run root; agent identity, originating LLM call/span IDs, tool call ID/name/ordinal, and sanitized arguments are populated |
| `ResourceCallEvent STARTED` | Immediately before either application invokes a downstream HTTP resource | New resource child `spanId`. Delivery resource calls use the executing tool span and tool name/ID. Support's direct `INVOKE_DELIVERY_AGENT` call uses the Support run root and has null tool name/ID. |
| `ResourceCallEvent COMPLETED` | When the downstream resource returns or fails | Same IDs as STARTED; success and downstream error code are populated |
| `ToolCallEvent COMPLETED` | When tool execution returns or safely maps an error | Same tool span and correlation fields as STARTED; success and error code are populated |
| `AgentRunCompletedEvent` | After a handled answer or safe failure is returned | Original run context, request/response, completion time, duration, and final LLM/tool counts |
| `AgentRunFailedEvent` | When an unhandled runtime exception escapes the run | Original run context, failure time, duration, exception type, and message |
| `DelegatedAgentRunCompletedEvent` | After the Delivery Agent returns a handled structured result | Original nested run linkage plus the Delivery Agent's own LLM/tool totals, result, completion time, and duration |
| `DelegatedAgentRunFailedEvent` | When an unhandled exception escapes the Delivery Agent boundary | Original nested run linkage, failure time, duration, exception type, and message |

The in-process bus remains the preferred local application boundary. If events need durability,
replay, or centralized cross-process consumption, keep `AgentEventPublisher` as the boundary and add
a RabbitMQ, NATS, or telemetry-export adapter rather than changing either agent or its tests.
