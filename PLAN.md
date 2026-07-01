# Implementation Plan

## Phase 0 — Infrastructure (done)

Docker Compose stack is live: Prometheus, Loki, Tempo, Grafana, OpenTelemetry Collector, PostgreSQL+pgvector.

---

## Phase 1 — On-demand Investigation Core

**Goal:** given a service name, time window, or alert context, produce a structured root-cause report via live tool calls.

### 1.1 Maven project skeleton

Multi-module layout: `prism-domain`, `prism-adapters-in`, `prism-adapters-out`, `prism-boot`.

### 1.2 Domain model

```
Investigation          — aggregate root; owns the full investigation lifecycle
InvestigationRequest   — value object; the starting point (alert, free-text query, metric spike)
Signal                 — value object; a single observation (type: METRIC | LOG | TRACE | MEMORY | SCHEMA)
Finding                — value object; the model's interpretation of one or more signals
InvestigationStatus    — PENDING | IN_PROGRESS | CONCLUDED | FAILED
```

### 1.3 Outbound port interfaces

```java
MetricsPort       — queryRange(PromQL, window) → Signal; listMetricNames() → Signal
LogsPort          — search(LogQL, window) → Signal; listLabelNames(), listLabelValues(label) → Signal
TracingPort       — getTrace(traceId), searchTraces(TraceQL, window) → Signal; listTagNames(), listTagValues(tag) → Signal
ReasoningPort     — nextStep(InvestigationContext) → ReasoningStep (a tool request, or a conclusion)
InvestigationRepository — persist / load Investigation aggregates
```

The investigation loop lives in `InvestigationLoop` (application layer), not in
any adapter. It seeds the telemetry schema up front, then drives the steps,
dispatches tool requests to the telemetry ports, records signals, and stops on a
conclusion or the `maxSteps` bound. The `ReasoningStep` sealed type (`QueryMetrics |
SearchLogs | GetTrace | SearchTraces | ListLogLabelValues | ListTraceTagValues |
SearchPastInvestigations | Conclusion`, in `ai.prism.domain.reasoning`) is the
model-agnostic vocabulary crossing the `ReasoningPort` boundary. Schema *names* are
seeded up front, so only the on-demand *value* lookups remain as discovery steps.

### 1.4 Adapters

- `PrometheusAdapter` implements `MetricsPort` via Prometheus HTTP API (`/api/v1/query_range`, metric-name discovery `/api/v1/label/__name__/values`)
- `LokiAdapter` implements `LogsPort` via Loki HTTP API (`/loki/api/v1/query_range`, label discovery `/labels` & `/label/{n}/values`); reduces query responses to their log lines, dropping Loki's `stats` block
- `TempoAdapter` implements `TracingPort` via Tempo HTTP API (`/api/traces/{traceId}`, TraceQL search `/api/search?q=`, v2 tag discovery `/api/v2/search/tags`)
- `SpringAiReasoningAdapter` implements `ReasoningPort` via Spring AI: the framework's agentic tool-execution loop is not used, so the model's single tool choice is returned to the loop rather than executed by the framework. A pure `ReasoningStepMapper` translates the tool call into a `ReasoningStep`. Provider and model id are configuration-driven; the loop stays in `InvestigationLoop`.
- `PostgresInvestigationRepository` persists Investigation aggregates
- **Reasoning resilience** (added later): `RetryingReasoningPort` wraps the per-model `SpringAiReasoningAdapter`s and retries each step up to `prism.reasoning.max-attempts`, rotating models on error — including a cross-provider Groq (OpenAI-compatible) fallback.

### 1.5 Inbound adapter

Simple REST endpoint: `POST /investigations` with an `InvestigationRequest` body. Returns a report synchronously for now (async in Phase 3).

### 1.6 Testing

- Domain model: pure unit tests, no Spring context
- Application use cases: unit tests with mocked ports
- Adapters: integration tests against the docker-compose stack (Testcontainers or direct)
- End-to-end: at least one happy-path test that fires a real investigation against the local stack

---

## Phase 2 — Memory & Knowledge Base (done)

**Goal:** past investigations make future ones better. Recurring failure patterns surface their own history without manual runbook writing.

### 2.1 Port

`MemoryPort` (in `prism-domain`):

```java
remember(Investigation)      — store a concluded investigation
findSimilar(query) → Signal  — recall similar past investigations as a MEMORY signal
```

Both are **best-effort** (never fail an investigation): `findSimilar` never throws; storing runs through a decorator that logs and swallows failures.

### 2.2 Implementations (selected by `prism.knowledge.store`)

- `TokenOverlapMemory` — token-overlap ranking, in-process; for local dev (no DB/embeddings).
- `PgVectorMemory` (default) — embeds via a Spring AI `EmbeddingModel` (Gemini), stores in the `investigation_embeddings` pgvector table (unspecified `vector` dimension, so model-agnostic), searches by cosine distance. Plain JDBC/SQL. Embedding calls inherit Spring AI's retry (backoff, 429-aware) but have no model rotation.

### 2.3 Wired into the flow

The reasoning step set gains `SearchPastInvestigations` + the `search_past_investigations` tool (the system prompt encourages calling it early); the loop dispatches it to the knowledge base and records a `MEMORY` `Signal`. `RememberingInvestigationRunner` (decorator) stores each concluded investigation. Runbooks emerge from usage — no manual authoring required.

---

## Phase 3 — Autonomous Alert-Driven Investigation

**Goal:** an alert fires → prism.ai is notified over HTTP → it investigates and delivers a report automatically.

### 3.1 Alert webhook (inbound adapter) — done

`AlertWebhookController` exposes `POST /alerts`, accepting the Alertmanager-format webhook (Prometheus
Alertmanager, or Grafana's built-in alerting via a webhook contact point). It maps each *firing* alert to
an `InvestigationRequest` (`RequestSource.ALERT`) — alertname + summary as the query, the `service`/`job`
label as scope, `startsAt`/`endsAt` as the window — and submits it through the same async
`InvestigationCommandsUseCase.submit` that REST and MCP drive (`AlertMapper`); resolved alerts are skipped.
So the loop, tracing (the investigation trace links back to the `POST /alerts` request span) and memory all
apply unchanged. The endpoint is guarded by an optional shared-secret `X-API-Key` filter
(`prism.alerts.api-key`, blank = open for dev), mirroring the MCP key.

Webhook-direct (not Kafka): far less to run, and reuses the REST stack. The trade-off is no durability —
an alert that fires while prism is down is lost (Alertmanager retries briefly, then drops it); Kafka can be
re-introduced behind the same `submit` spine later if buffering is needed. Dedup by `fingerprint`
(Alertmanager re-sends firing alerts on an interval) is a follow-up.

### 3.2 Async investigation (done)

Investigations run asynchronously. The inbound side splits into a command port
(`InvestigationCommandsUseCase` — `submit` schedules the run on a virtual-thread
worker and returns an `InvestigationId`; `handle` runs inline) and a query port
(`InvestigationQueriesUseCase` — `findById`, `recent`). `InvestigationCommandsService`
opens the aggregate, persists it `PENDING`, and schedules the loop; the loop itself
(`InvestigationLoop`) is the `InvestigationRunner` SPI, wrapped by the observability
and memory decorators. REST: `POST /investigations` → `202 Accepted` + id;
`GET /investigations/{id}` polls status/result; `GET /investigations?limit=` lists recent.

---

## Phase 4 — Remote MCP Server

**Goal:** expose prism.ai as a remote MCP server so developers can trigger and query investigations from Claude Code or Claude Desktop, regardless of where the observability stack lives.

> MCP uses date-versioned protocol revisions; this phase targets **2025-06-18** over the modern **Streamable HTTP** transport, which replaced the deprecated HTTP+SSE two-endpoint transport.

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

### 4.2 Transport & protocol

**Streamable HTTP** on a single endpoint (`POST`/`GET /mcp`): POST carries JSON-RPC requests; the server may upgrade the same connection to SSE for notifications/progress; sessions are tracked via the `Mcp-Session-Id` header; the revision is negotiated via `MCP-Protocol-Version` (target `2025-06-18`). Apply the spec's HTTP-transport hardening: `Origin` validation (DNS-rebinding protection) and TLS at the edge. The deprecated HTTP+SSE two-endpoint transport is not used.

### 4.3 MCP tools adapter (inbound)

`InvestigationMcpTools` in `prism-adapters-in` — `@Tool`-annotated methods exposed as MCP tools by the **Spring AI MCP server (WebMVC) starter** (the servlet variant matching the app's stack), which wraps the MCP Java SDK and provides the Streamable HTTP transport. It depends only on the inbound ports `InvestigationCommandsUseCase` + `InvestigationQueriesUseCase` — the same spine REST and the alert webhook use — and translates only, no business logic. Registered at the composition root as a `ToolCallbackProvider` bean (`McpConfiguration`).

Tools exposed (mapped to the ports), returning 2025-06-18 **structured output** (`outputSchema` / `structuredContent`):

```
investigate(query, service?, from?, to?)  → commands.submit(request)  — returns { id, status: PENDING } at once
get_investigation(id)                     → queries.findById(id)      — status, finding, signal count
list_recent_investigations(limit?)        → queries.recent(limit)     — recent summaries for pattern-spotting
```

These inbound tool DTOs are distinct from the LLM's outbound `ReasoningTools`.

### 4.4 Async behaviour

`investigate` returns the id immediately (it maps to `submit`); the client polls `get_investigation` until the status is `CONCLUDED`/`FAILED`. State lives in Postgres, so it survives sessions and restarts. *(Optional later: stream live progress over the Streamable HTTP SSE channel instead of polling.)*

### 4.5 Authentication

- **v1:** API key via request header, enforced by a filter on `/mcp`; keys scoped per team or developer, no anonymous access.
- **Spec-aligned upgrade:** OAuth 2.1 with the server acting as a Resource Server (bearer token, `WWW-Authenticate` challenge, protected-resource metadata).

### 4.6 Testing

- Unit: MCP tool schemas serialise correctly; request → port mapping with mocked ports.
- Integration: an MCP client (MCP Java SDK, Streamable HTTP) connects, calls `investigate`, polls `get_investigation`, and receives a `Finding` — `@SpringBootTest` over the WebMVC transport.

---

## Phase 5 — Hardening & Evaluation

### 5.1 Observability of the agent (done — core)

Each investigation is a Langfuse trace and each LLM call a costed *generation*. Wired **collector-side**:
the app keeps exporting OTLP once to the collector (single telemetry façade), and
`scripts/otel-collector-config.yaml` fans the traces pipeline out to Langfuse Cloud's OTLP endpoint
(`otlphttp/langfuse`). The only app change is `ObservedChatModel` (mirrors
`ObservedEmbeddingModel`): it wraps every reasoning `ChatModel` and emits a `prism.reasoning.llm` span
carrying the OpenTelemetry **GenAI** attributes (`gen_ai.request.model`, `gen_ai.usage.*`, and — behind
`prism.langfuse.capture-io` — `gen_ai.prompt`/`gen_ai.completion`) that Langfuse parses; **cost is derived
by Langfuse** from the model id + token counts, so no pricing table lives in the app. A
`prism.reasoning.tokens` histogram (by model / input·output) is exported alongside.

**Shared-collector conventions** (so one observability stack serves many projects):
- **Scope filter** — a dedicated `traces/langfuse` pipeline keeps only `ai.*` instrumentation-scope spans
  (the agent/GenAI spans) and drops auto-instrumentation noise; any service that names its custom tracers
  `ai.<project>.*` is covered with no per-project collector change (prism uses `ai.prism.*`).
- **Per-service credentials** — each service forwards its own Langfuse key as a `langfuse-authorization`
  OTLP header (`OTEL_EXPORTER_OTLP_HEADERS`), so it lands in its own Langfuse project through the *same*
  collector; the collector holds **no** Langfuse credentials (a service that forwards none is not exported).
  The `headers_setter` extension propagates the header, and `batch/langfuse` uses
  `metadata_keys: [langfuse-authorization]` to bucket batches per credential so the header survives
  batching. A `routing` connector is only needed if tenants target different Langfuse
  endpoints.

### 5.2 Hardening (done — core)

- **Token tracking** per step → the `prism.reasoning.tokens` histogram + the GenAI span attrs above
  (Langfuse turns these into cost).
- **Low-confidence surfacing**: `ObservedInvestigationRunner` tags `finding.confidence` /
  `investigation.low_confidence` on the investigation span, increments a `prism.investigation.concluded`
  counter (by confidence), and WARN-logs a LOW-confidence conclusion for review.
- **Grafana dashboard** (`scripts/grafana-dashboard-prism.json` + `grafana-dashboards.yaml` provider):
  investigations by outcome, p95 time-to-conclusion, tool-call distribution, confidence mix, token usage.

**Deferred (follow-up):**
- Rate limiting on the investigation / alert POST endpoints.
- Langfuse LLM-as-judge quality scoring (eval configured in Langfuse over the captured I/O).
