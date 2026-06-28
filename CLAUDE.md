# CLAUDE.md — prism.ai

## Project

AI-powered observability investigation assistant. On-demand and alert-driven root-cause analysis over a Prometheus / Loki / Tempo / Grafana stack, backed by LLM tool-use (Google Gemini by default) and pgvector memory.

See [README.md](README.md) for stack overview and [PLAN.md](PLAN.md) for implementation phases.

---

## Architecture rules

**Hexagonal architecture. No exceptions.**

```
prism-domain        — pure Java 25; zero framework dependencies
prism-adapters-in   — REST, MCP server, Kafka consumer; depends on domain
prism-adapters-out  — model reasoning, Prometheus, Loki, Tempo, Postgres, pgvector; depends on domain
prism-boot          — Spring Boot wiring; depends on all adapter modules
```

- The domain layer must compile without Spring, Jackson, or any infrastructure library on the classpath
- Domain objects are plain Java records and classes — no JPA annotations, no JSON annotations
- Ports are Java interfaces defined in `prism-domain`; adapters implement them in `prism-adapters-out` or `prism-adapters-in`
- If you need to cross a module boundary, use a port — never reach directly into an adapter from a use case

**DDD vocabulary.** The core concepts are `Investigation`, `Signal`, `Finding`, and `InvestigationRequest`. Use these names consistently — not "case", "incident", "analysis" or other synonyms. When adding a new concept, define it in the domain first, name it explicitly, and let that name propagate to adapters.

---

## Technology

- **Java 25** — use records for value objects, sealed interfaces for domain enums with behaviour, pattern matching where it reduces noise
- **Maven multi-module** — BOM-managed dependencies; no version numbers scattered across child POMs
- **Spring Boot** (boot module only) — no `@Component`, `@Service`, or `@Repository` in `prism-domain`
- **Model access via Spring AI; provider- and config-driven** — no provider or model name appears in the domain or port names. The reasoning port is `ReasoningPort`, implemented by `SpringAiReasoningAdapter`. It runs Spring AI with **internal tool execution disabled** so the framework does not run the agentic loop — it only returns the model's single tool choice, which the application loop dispatches. Because Spring AI is a unified abstraction, both the model id and the provider are configuration changes (the model-id list in `prism.reasoning.models`, or a different starter), not code changes. Default provider is Google Gemini (API-key auth). Multiple models are composed for resilience: `RetryingReasoningPort` retries each reasoning step up to `prism.reasoning.max-attempts`, rotating (round-robin) to a different `SpringAiReasoningAdapter` — each targeting one model id, including a cross-provider Groq fallback — on every error, waiting between attempts with exponential backoff + jitter (`prism.reasoning.retry-backoff` … `retry-backoff-max`) so an overloaded provider can recover instead of being hammered.
- **Direct HTTP adapters** — `PrometheusAdapter`, `LokiAdapter`, and `TempoAdapter` call the datasource HTTP APIs directly: range queries (`/api/v1/query_range`, `/loki/api/v1/query_range`), a trace by id (`/api/traces/{id}`) and **TraceQL** search (`/api/search?q=`), plus the schema-discovery endpoints below. `LokiAdapter` reduces a query response to just its log lines (grouped by stream labels), dropping Loki's large `stats` block, before it becomes a `Signal`. Do not introduce grafana-mcp as a runtime dependency.
- **Schema discovery & seeding** — to stop the model guessing label/metric/tag names (e.g. `service` vs OTel-native `service_name`), the reasoning vocabulary includes discovery steps (`ListLogLabels`, `ListLogLabelValues`, `ListMetricNames`, `ListTraceTags`, `ListTraceTagValues`), exposed as `list_*` tools and backed by the datasource discovery endpoints (Loki `/labels` & `/label/{n}/values`, Prometheus `/label/__name__/values`, Tempo v2 `/api/v2/search/tags` & `/tag/{t}/values`). Their results are `SignalType.SCHEMA` signals (no dashboard link). `InvestigationLoop` also **seeds** the schema (log labels, metric names, trace tags) as the first signals of every investigation, best-effort, so the model sees the real names before its first query; `SCHEMA` signals are kept fully visible in the reasoning prompt (never truncated or omitted) so they aren't wastefully re-discovered.
- **Dashboard deep links** — `DashboardLinkPort` (in `prism-domain`) turns a `Signal` into a clickable Grafana Explore URL, surfaced on the REST/MCP investigation views (`signals[].link` plus a `primaryLink` headline the model nominates via `Finding.keySignalIndex`). The sole implementation, `GrafanaDashboardLinkAdapter` (`adapters.out.link`), is **pure URL construction from data the `Signal` already carries — no network call**, which is also why this does not need (and must not pull in) grafana-mcp. Configured by `prism.grafana.url` + the per-source datasource UIDs (`prometheus-uid`/`loki-uid`/`tempo-uid`); a blank UID disables links for that source. Best-effort: it never throws and returns no link rather than failing.
- **MCP Java SDK** — via the Spring AI MCP server (WebMVC) starter, `InvestigationMcpTools` (inbound) exposes prism.ai's investigation tools (`investigate`, `get_investigation`, `list_recent_investigations`) over the **Streamable HTTP** transport at `/mcp` (MCP revision 2025-06-18; the deprecated HTTP+SSE transport is not used); this makes prism.ai a remote MCP server that Claude Code and Claude Desktop can connect to
- **pgvector via JDBC** — no ORM for vector operations; write plain SQL for similarity search queries
- **Memory of past investigations** — `MemoryPort` (in `prism-domain`): `remember(Investigation)` after a conclusion, `findSimilar(query)` during the loop. Both are **best-effort** (never fail an investigation): `findSimilar` never throws (returns an "unavailable" `MEMORY` signal on error), and storing runs through the `RememberingInvestigationRunner` decorator which logs and swallows failures. Two implementations selected by `prism.knowledge.store`: `TokenOverlapMemory` (token overlap, for dev) and `PgVectorMemory` (Spring AI `EmbeddingModel` + pgvector, plain SQL), both in `adapters.out.memory`.
- **Database schema** — DDL lives in `scripts/prism-db-init.sql`, applied by the Postgres container's `docker-entrypoint-initdb.d` on first init of an empty data dir (wired in `docker-compose.yaml`). The app runs **no** DDL — there is no `spring.sql.init`, and the persistence adapters assume the schema exists. Because the init hook only fires on a fresh data dir, schema changes require recreating the DB: `./data/postgres` is a bind mount, so clear it (`docker compose down && sudo rm -rf data/postgres && docker compose up -d`) rather than relying on `down -v`.
- **Self-observability** — the app exports its own traces, logs and metrics via OTLP to the collector (→ Tempo / Prometheus / Loki). **OpenTelemetry is the single tracing façade**: the `opentelemetry-spring-boot-starter` provides the SDK, auto-instrumentation (HTTP-server, JDBC) and OTLP export with log↔trace correlation (logs carry the active span's `trace_id`). Domain-specific spans are created via the OpenTelemetry `Tracer` in `Observed*` decorators colocated with the port family each one wraps: `ObservedInvestigationRunner` → `prism.investigation` (the root), `ObservedReasoningPort` → `prism.reasoning.step` (per model decision), `ObservedHttpExecutor` → `prism.telemetry.query`, `ObservedEmbeddingModel` → `prism.embedding`; each makes its span current so child spans and logs nest correctly. Wired at the composition root by injecting `OpenTelemetry`. **Do NOT add `micrometer-tracing-bridge-otel`** — it is a competing tracer that silently suppresses these spans (Micrometer is kept for metrics only). Never put telemetry code in the domain or application layers — instrument with decorators that wrap the ports.

---

## Testing

Every module must have tests. The bar is:

| Scope                        | Type                   | Rule                                                               |
|------------------------------|------------------------|--------------------------------------------------------------------|
| Domain layer in domain       | Pure unit tests        | No mocks, no Spring; test invariants and domain rules              |
| Application layer in domain  | Unit tests             | Mock all port interfaces; test orchestration logic only            |
| Outbound adapters            | Integration tests      | Run against the docker-compose stack; use real HTTP/JDBC           |
| Inbound adapters             | Integration tests      | `@SpringBootTest` slice; verify request mapping and error handling |
| End-to-end                   | At least one per phase | Full investigation cycle against local stack                       |

- Do not mock the database in adapter integration tests — the docker-compose stack is the test fixture
- Testcontainers is acceptable for CI where docker-compose is not available
- Test the domain's behaviour, not its implementation. Tests should survive refactors.

---

## Investigation loop

The investigation loop is the core of the business, so it lives in the application
layer (`InvestigationLoop`), inside the hexagon — not in any adapter.

The split of responsibility:

- **`ReasoningPort.nextStep(context)`** decides the *single* next step given the
  investigation so far: gather a specific piece of evidence, or conclude. This is
  the only part that knows a provider's tool-use protocol; it is implemented by an
  adapter and returns a `ReasoningStep`.
- **`InvestigationLoop`** (implements the `InvestigationRunner` SPI) owns the loop.
  Before the first step it best-effort **seeds** the telemetry schema (log labels,
  metric names, trace tags) as the opening signals. It then repeatedly asks for the
  next step, dispatches tool requests to the telemetry ports (`MetricsPort`, `LogsPort`,
  `TracingPort`) or the memory port (`MemoryPort`), records each `Signal` on the
  aggregate, and ends when a `Conclusion` arrives or the `maxSteps` bound is hit.
  A failed telemetry tool call is best-effort: a **rejected query** (HTTP 4xx, surfaced
  as `TelemetryException`) is recorded as an error `Signal` telling the model to fix the
  query, while an **unreachable datasource** is recorded as an infrastructure failure the
  model must not mistake for evidence or an outage. A reasoning failure fails the run.

**Async + CQRS at the boundary.** Two inbound ports split the command and query
sides: `InvestigationCommandsUseCase` (`submit` runs the loop on a virtual-thread
worker and returns an `InvestigationId` immediately; `handle` runs it inline) and
`InvestigationQueriesUseCase` (`findById`, `recent`). `InvestigationCommandsService`
opens the aggregate, persists it `PENDING`, and schedules the run; the cross-cutting
decorators (`ObservedInvestigationRunner`, `RememberingInvestigationRunner`) wrap the
`InvestigationRunner`, so observability and memory apply to sync and async runs alike.

```
ReasoningStep (sealed):
  QueryMetrics | SearchLogs | GetTrace | SearchTraces(TraceQL)   → telemetry query
  ListLogLabels | ListLogLabelValues | ListMetricNames
    | ListTraceTags | ListTraceTagValues                         → schema discovery (SCHEMA signal)
  SearchPastInvestigations                                       → knowledge base (MEMORY signal)
  Conclusion(Finding)                                            → ends the loop
```

The adapter never sees the loop; the application never sees tool-use JSON or which
model is configured. `maxSteps` guarantees termination.

---

## What to avoid

- No business logic in adapters — adapters translate, they do not decide
- No domain objects with `null` fields — use `Optional` or domain-specific absent values
- No `@Transactional` in the domain or application layer — transaction boundaries belong in adapters or the boot module
- Do not add a vector DB service to docker-compose — pgvector in the existing Postgres container is sufficient
- Do not introduce grafana-mcp as a runtime dependency — the Prometheus, Loki, and Tempo HTTP APIs are simple and well-documented; build adapters directly against them
- prism.ai IS an MCP server (inbound), not an MCP client — do not confuse the two roles; the MCP Java SDK is used to expose tools to external clients, not to call external MCP servers
