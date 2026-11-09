# Order Delivery Support Agent — Stage 2

This Spring Boot application now includes the Stage 1 order APIs plus a bounded, read-only support
agent and a Vaadin chat UI. The model can request tools, but only application code validates and
executes them. No refund, cancellation, exchange, replacement, inventory reservation, or payment
operation is exposed.

## Requirements

- Java 21
- Maven 3.9+ (or the included Maven Wrapper)
- A Groq API key for live chat

## Configure and run

The recommended setup keeps the key out of source control:

```bash
GROQ_API_KEY=your-key ./mvnw spring-boot:run
```

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

Set `LIVE_EVAL_PORT` if port `18080` is unavailable. The ten live evals assert tool order,
forbidden tools, exact LLM/tool counts, duplicate calls, context reuse, and trace/span/session
correlation. They deliberately do not grade the customer-facing response text.

## API

| Method | Path | Response |
|---|---|---|
| `POST` | `/chat` | Validated `ChatResponse` with answer, outcome, evidence, recommendations, approval-required actions, and call counts |
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
recent accepted turns and is cleared when the application restarts. Accepted turns retain their
verified tool evidence in memory, allowing deterministic follow-ups to reuse ownership and delivery
facts instead of calling those tools again.

## Agent safety and orchestration

The allow-list contains exactly these read-only tools:

- `get_customer_orders` → `GetCustomerOrdersTool.java`
- `get_delivery_status` → `GetDeliveryStatusTool.java`
- `get_refund_policy` → `GetRefundPolicyTool.java`
- `get_exchange_policy` → `GetExchangePolicyTool.java`

The model also receives a control-plane `submit_agent_decision` function. Its arguments become the
proposed final response and are passed to `AgentDecisionValidator`; it is never sent to the business
tool executor, does not perform an action, and does not count as a tool call.

`OrderApiClient` calls the downstream REST contracts with bounded connect/read timeouts. Every
delivery lookup revalidates order ownership. Raw tracking data is passed to the deterministic
`DeliveryInvestigator`; the case manager receives its verified finding instead of interpreting the
timeline itself.

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
only genuinely independent non-delivery calls in parallel, validates the final JSON decision,
prevents silent order selection when multiple orders match, and fails safely on model, downstream,
or rate-limit errors. Follow-up answers are limited to 50 words and rejected for repeated eight-word
sequences from prior assistant messages, so validation can ask the model for a concise correction.
OpenAI SDK retries are deliberately capped by `support.llm.max-retries`.

Set `ORDER_API_BASE_URL` if the Stage 1 APIs run in a separate service. Set `CHAT_API_BASE_URL` only
when the Vaadin UI should call a separately hosted `/chat` API.

## Seed data

- Customers: `CUST-1001`, `CUST-1002`, `CUST-1003`, `CUST-1004`
- Orders: `ORD-1001` through `ORD-1006`
- Status examples include delivered, awaiting inventory refresh, delivery agent unreachable,
  in-transit, and lost in transit.

`CUST-1004` owns only the delivered `ORD-1006` and exists specifically for negative refund and
exchange path evals. `CUST-404` has no orders.

## Eval event bus and event contract

`LoggingAgentEventPublisher` publishes typed events through Spring's in-process
`ApplicationEventPublisher`. Production listeners can subscribe without coupling the agent loop to
the consumer. The live eval harness subscribes to the same events and places them in a bounded JDK
`LinkedBlockingQueue`; this keeps tests deterministic and requires no external broker.

| Event | Trigger | Important attribute values |
|---|---|---|
| `AgentRunStartedEvent` | Immediately before a chat run starts | `runId` is a new UUID; `sessionId` comes from the request; `traceId` and root `spanId` come from MDC/HTTP correlation headers |
| `LlmCallEvent STARTED` | Immediately before each logical `LlmSession.next()` | New LLM child `spanId`; `parentSpanId` is the run root; iteration, logical ordinal, model, and a new `llmCallId` are populated |
| `LlmCallEvent COMPLETED` | Immediately after that logical call returns or fails | Same IDs as STARTED; `providerCallCount` records actual provider calls including explicit rate-limit retries and structured-output fallback; requested tool names/IDs, success, and error type are populated |
| `ToolCallEvent STARTED` | After application path validation and immediately before a tool executes | Every tool gets a unique child `spanId`; `parentSpanId` is the run root; originating LLM call/span IDs, tool call ID/name/ordinal, and sanitized arguments are populated |
| `ResourceCallEvent STARTED` | Immediately before `OrderApiClient` invokes a downstream HTTP resource | New resource child `spanId`; `parentSpanId` is the executing tool span; tool name/ID, HTTP operation, and path template are populated |
| `ResourceCallEvent COMPLETED` | When the downstream resource returns or fails | Same IDs as STARTED; success and downstream error code are populated |
| `ToolCallEvent COMPLETED` | When tool execution returns or safely maps an error | Same tool span and correlation fields as STARTED; success and error code are populated |
| `AgentRunCompletedEvent` | After a handled answer or safe failure is returned | Original run context, request/response, completion time, duration, and final LLM/tool counts |
| `AgentRunFailedEvent` | When an unhandled runtime exception escapes the run | Original run context, failure time, duration, exception type, and message |

This in-process bus is the preferred default while the producer and eval consumer run in the same
Spring service. If events later need durability, replay, or consumption by another process, keep
`AgentEventPublisher` as the application boundary and add a RabbitMQ or NATS adapter rather than
changing the agent or tests.
