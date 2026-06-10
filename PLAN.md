# Implementation Plan

## Phase 0 — Infrastructure (done)

Docker Compose stack is live: Prometheus, Loki, Tempo, Grafana, OpenTelemetry Collector, PostgreSQL+pgvector, Kafka, Schema Registry.

---

## Phase 1 — On-demand Investigation Core

**Goal:** given a service name, time window, or alert context, produce a structured root-cause report via live tool calls.

### 1.1 Maven project skeleton

Multi-module layout: `prism-domain`, `prism-application`, `prism-adapters-in`, `prism-adapters-out`, `prism-boot`.

### 1.2 Domain model

```
Investigation          — aggregate root; owns the full investigation lifecycle
InvestigationRequest   — value object; the starting point (alert, free-text query, metric spike)
Signal                 — value object; a single telemetry data point (type: METRIC | LOG | TRACE)
Finding                — value object; the model's interpretation of one or more signals
InvestigationStatus    — PENDING | IN_PROGRESS | CONCLUDED | FAILED
```

### 1.3 Outbound port interfaces

```java
MetricsPort       — queryRange(PromQL, window) → Signal
LogsPort          — search(LogQL, window) → Signal
TracingPort       — getTrace(traceId), searchTraces(service, window) → Signal
ReasoningPort     — nextStep(InvestigationContext) → ReasoningStep (a tool request, or a conclusion)
InvestigationRepository — persist / load Investigation aggregates
```

The investigation loop lives in `InvestigationService` (application layer), not in
any adapter. The service drives the steps, dispatches tool requests to the telemetry
ports, records signals, and stops on a conclusion or the `maxSteps` bound. The
`ReasoningStep` sealed type (`QueryMetrics | SearchLogs | GetTrace | SearchTraces |
Conclusion`) is the model-agnostic vocabulary crossing the `ReasoningPort` boundary.

### 1.4 Adapters

- `PrometheusAdapter` implements `MetricsPort` via Prometheus HTTP API (`/api/v1/query_range`)
- `LokiAdapter` implements `LogsPort` via Loki HTTP API (`/loki/api/v1/query_range`)
- `TempoAdapter` implements `TracingPort` via Tempo HTTP API (`/api/traces/{traceId}`)
- `AnthropicReasoningAdapter` implements `ReasoningPort` via the Anthropic Java SDK; maps the model's tool-use response to a `ReasoningStep`. Model id is configuration-driven, never hardcoded. The loop itself stays in `InvestigationService`.
- `PostgresInvestigationRepository` persists Investigation aggregates

### 1.5 Inbound adapter

Simple REST endpoint: `POST /investigations` with an `InvestigationRequest` body. Returns a report synchronously for now (async in Phase 3).

### 1.6 Testing

- Domain model: pure unit tests, no Spring context
- Application use cases: unit tests with mocked ports
- Adapters: integration tests against the docker-compose stack (Testcontainers or direct)
- End-to-end: at least one happy-path test that fires a real investigation against the local stack

---

## Phase 2 — Memory & Knowledge Base

**Goal:** past investigations make future ones better. Recurring failure patterns surface their own history without manual runbook writing.

### 2.1 Storage

Add `InvestigationEmbedding` table (pgvector). After each investigation concludes, embed the `(symptoms + conclusion)` text using Claude's embeddings and store it alongside the investigation ID.

### 2.2 Knowledge search port

```java
KnowledgeSearchPort — findSimilarInvestigations(query: String, limit: int) → List<InvestigationSummary>
```

`PgVectorKnowledgeAdapter` implements it.

### 2.3 Wire into the investigation flow

The `ClaudeAdapter` tool-use loop gains a new tool: `search_past_investigations(query)`. The model calls it when it recognizes a pattern worth checking history for. Runbooks emerge from usage — no manual authoring required.

---

## Phase 3 — Autonomous Alert-Driven Investigation

**Goal:** Alertmanager fires → Kafka event published → prism.ai investigates and delivers a report automatically.

### 3.1 Kafka consumer (inbound adapter)

`AlertConsumer` listens to `prism.alerts` topic. Deserializes an `AlertEvent` (Avro schema in Schema Registry), constructs an `InvestigationRequest`, and submits it to the application use case.

### 3.2 Async investigation

Investigations become async. The REST endpoint returns `202 Accepted` with an investigation ID; clients poll `GET /investigations/{id}` for status and result.

### 3.3 Observability of the agent

Instrument the investigation loop with Langfuse traces so each tool call, model response, and final conclusion is traceable and scoreable. This is the feedback loop that lets you judge quality before trusting autonomous runs.

---

## Phase 4 — MCP Server Interface

**Goal:** expose prism.ai as a remote MCP server so developers can trigger and query investigations from Claude Code or Claude Desktop, regardless of where the observability stack lives.

### 4.1 Why this matters

prism.ai runs in the cloud with direct access to the company's observability stack. Developers on their laptops don't have that access — but they don't need to. They configure their MCP client once and investigate in natural language through their AI assistant of choice.

```json
// ~/.claude/mcp.json (developer's laptop)
{
  "mcpServers": {
    "prism": {
      "url": "https://prism.internal.company.com/mcp",
      "headers": { "X-API-Key": "..." }
    }
  }
}
```

### 4.2 McpServerAdapter (inbound)

New inbound adapter in `prism-adapters-in`. Implements the MCP server protocol using the MCP Java SDK with SSE/HTTP transport (suitable for remote/cloud deployment).

Tools exposed:

```
investigate(query, service?, window?)  — trigger an investigation; returns investigation ID
get_investigation(id)                  — fetch status and result of a running or completed investigation
list_recent_investigations(limit?)     — surface recent investigations; useful for pattern spotting
```

### 4.3 Authentication

API key via request header. Keys are scoped per team or developer. No anonymous access.

### 4.4 Transport

SSE over HTTPS. The MCP client (Claude Code/Desktop) opens a persistent SSE connection; the server streams tool results back as events. This works through corporate proxies and firewalls where WebSockets may be blocked.

### 4.5 Testing

- Unit test: MCP tool definitions serialise correctly
- Integration test: MCP client connects, calls `investigate`, receives a `Finding`

---

## Phase 5 — Hardening

- Cost tracking per investigation (token counts → Langfuse metadata)
- Rate limiting on the investigation endpoint
- Quality evaluation: score past investigations in Langfuse; surface low-confidence conclusions
- Grafana dashboard for investigation history, p95 time-to-conclusion, tool-call distribution
