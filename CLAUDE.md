# CLAUDE.md — prism.ai

## Project

AI-powered observability investigation assistant. On-demand and alert-driven root-cause analysis over a Prometheus / Loki / Tempo / Grafana stack, backed by LLM tool-use (Google Gemini by default) and pgvector memory.

See [README.md](README.md) for stack overview and [PLAN.md](PLAN.md) for implementation phases.

---

## Architecture rules

**Hexagonal architecture. No exceptions.**

```
prism-domain        — pure Java 21; zero framework dependencies
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
- **Model access via Spring AI; provider- and config-driven** — no provider or model name appears in the domain or port names. The reasoning port is `ReasoningPort`, implemented by `SpringAiReasoningAdapter`. It runs Spring AI with **internal tool execution disabled** so the framework does not run the agentic loop — it only returns the model's single tool choice, which the application loop dispatches. Because Spring AI is a unified abstraction, both the model id and the provider are configuration changes (the model-id list in `prism.reasoning.models`, or a different starter), not code changes. Default provider is Google Gemini (API-key auth). Multiple models are composed for resilience: `RetryingReasoningPort` retries each reasoning step up to `prism.reasoning.max-attempts`, rotating (round-robin) to a different `SpringAiReasoningAdapter` — each targeting one model id, including a cross-provider Groq fallback — on every error.
- **Direct HTTP adapters** — `PrometheusAdapter`, `LokiAdapter`, and `TempoAdapter` call the datasource HTTP APIs directly (`/api/v1/query_range`, `/loki/api/v1/query_range`, `/api/traces/{id}`); do not introduce grafana-mcp as a runtime dependency
- **MCP Java SDK** — used in `McpServerAdapter` (inbound) to expose prism.ai's investigation tools over SSE/HTTP transport; this makes prism.ai a remote MCP server that Claude Code and Claude Desktop can connect to
- **pgvector via JDBC** — no ORM for vector operations; write plain SQL for similarity search queries
- **Memory of past investigations** — `MemoryPort` (in `prism-domain`): `remember(Investigation)` after a conclusion, `findSimilar(query)` during the loop. Both are **best-effort** (never fail an investigation): `findSimilar` never throws (returns an "unavailable" `MEMORY` signal on error), and storing runs through the `RememberingInvestigationRunner` decorator which logs and swallows failures. Two implementations selected by `prism.knowledge.store`: `TokenOverlapMemory` (token overlap, for dev) and `PgVectorMemory` (Spring AI `EmbeddingModel` + pgvector, plain SQL), both in `adapters.out.memory`.
- **Database schema** — DDL lives in `scripts/prism-db-init.sql`, applied by the Postgres container's `docker-entrypoint-initdb.d` on first init of an empty data dir (wired in `docker-compose.yaml`). The app runs **no** DDL — there is no `spring.sql.init`, and the persistence adapters assume the schema exists. Because the init hook only fires on a fresh data dir, schema changes require recreating the DB: `./data/postgres` is a bind mount, so clear it (`docker compose down && sudo rm -rf data/postgres && docker compose up -d`) rather than relying on `down -v`.
- **Self-observability** — the app exports its own metrics, traces and logs via OTLP to the collector (→ Tempo / Prometheus / Loki). Baseline comes from Actuator + Micrometer Tracing (HTTP-server spans, JVM metrics, Spring AI model-call spans); domain-specific instrumentation lives in `Observed*` decorators colocated with the port family each one wraps (e.g. `ObservedReasoningPort` in `adapters.out.reasoning`, `ObservedHttpExecutor` in `adapters.out.http`, `ObservedEmbeddingModel` in `adapters.out.memory`, `ObservedInvestigationRunner` in `adapters.out.investigation`), wired at the composition root. Never put Micrometer/observation code in the domain or application layers — instrument with decorators that wrap the ports.

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
layer (`InvestigationService`), inside the hexagon — not in any adapter.

The split of responsibility:

- **`ReasoningPort.nextStep(context)`** decides the *single* next step given the
  investigation so far: gather a specific piece of evidence, or conclude. This is
  the only part that knows a provider's tool-use protocol; it is implemented by an
  adapter and returns a `ReasoningStep`.
- **`InvestigationLoop`** owns the loop. It repeatedly asks for the next step,
  dispatches tool requests to the telemetry ports (`MetricsPort`, `LogsPort`,
  `TracingPort`) or the memory port (`MemoryPort`), records each
  `Signal` on the aggregate, and ends when a `Conclusion` arrives or the `maxSteps`
  bound is hit.

```
ReasoningStep (sealed):
  QueryMetrics | SearchLogs | GetTrace | SearchTraces  → dispatched to a telemetry port
  SearchPastInvestigations                             → dispatched to the knowledge base
  Conclusion(Finding)                                  → ends the loop
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
