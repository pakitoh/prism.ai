<p align="center">
    <a href="https://github.com/pakitoh/prism.ai">
        <img src="media/logo.png" alt="Logo" width="400">
    </a>
</p>
<p align="center" style="color:rgb(40,82,100);font-size:44px;font-weight:bold;">
    <span style="color:rgb(23,47,88)">Prism</span><span style="color:white">AI</span>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-21+-blue" alt="Java 21+">
  <img src="https://img.shields.io/badge/license-GPLv3.0-green" alt="GPLv3 License">
  <img src="https://img.shields.io/badge/docker-compose-blue" alt="Docker Compose">
</p>

## 📋 Table of Contents

- [Why](#-why)
- [Key Features](#-key-features)
- [Getting Started](#-getting-started)
- [AI Design](#-ai-design)
- [Stack](#-stack)
- [Contributing](#-contributing)
- [License](#-license)



## ✨ Why

An AI-powered observability investigation assistant. Feed it a metric anomaly, a log query, or an alert — it reasons through your telemetry stack, correlates signals across Prometheus, Loki and Tempo, and surfaces the root cause.

> **Prism:** raw, undifferentiated telemetry goes in. Focused, actionable signal comes out.


## 🔑 Key features

- **On-demand investigation** — ask "what's wrong with checkout-service in the last 30 minutes?" and get a structured root-cause analysis backed by live queries
- **Signal correlation** — autonomously chains metric → log → trace queries rather than forcing you to pivot between tools manually
- **Growing memory** — past investigations are embedded in pgvector; recurring failure patterns surface their own history
- **Alert-driven (Phase 3)** — Alertmanager fires a Kafka event; prism.ai picks it up, investigates, and delivers a report
- **Remote MCP server (Phase 4)** — runs in the cloud with access to the company stack; developers connect Claude Code or Claude Desktop from their laptops and investigate in natural language

## 🤖 AI Design

### Why not RAG

A conventional RAG pipeline retrieves documents before the model reasons. That works well for knowledge bases and documentation search, but it's the wrong abstraction for incident investigation: you don't know which metrics to pull until you've seen the alert context, and you don't know which trace to fetch until you've seen the metric spike. The investigation is inherently sequential and context-dependent.

prism.ai uses **agentic tool-use** instead. The application drives a loop: it asks the model for the next step given the context so far, executes that step, incorporates the result, and continues until the model reaches a conclusion. Each step informs the next.

The loop itself is core business logic and lives in the application layer, not in the model adapter. The model only decides *one step at a time* — gather a specific piece of evidence, or conclude — and the application orchestrates everything else. The model and provider are configuration-driven (Google Gemini by default), with a configurable primary + fallback model list.

### Investigation loop

```
InvestigationRequest  (alert · free-text query · metric anomaly)
         │
         ▼
    next step? ◀─────────────────────────┐   (model decides one step)
         │                               │
         ├─ QueryMetrics(PromQL, window) → Prometheus HTTP API ─┐
         ├─ SearchLogs(LogQL, window)    → Loki HTTP API ───────┤
         ├─ GetTrace(traceId)            → Tempo HTTP API ───────┤ record Signal
         ├─ SearchTraces(service, win)   → Tempo HTTP API ───────┘   then loop
         │                               │
         └─ Conclusion ─────────────────────────────────────────▶ done
         │
         ▼
    Finding  (root cause · supporting evidence · recommended action)
```

A typical investigation: detect an error-rate spike → pull correlated logs → find an exemplar trace → identify the failing span and dependency. No manual pivoting between dashboards. A step limit guarantees the loop always terminates.

### Growing memory

Every completed investigation is embedded and stored in pgvector. When a similar failure recurs, the `search_past_investigations` tool surfaces relevant history — the original symptoms, the root cause, and what resolved it. Runbooks are not authored manually; they emerge from usage.

### Agent observability

Each investigation is traced in [Langfuse](https://langfuse.com): every tool call, model response, token count, and final conclusion is recorded and scoreable. This feedback loop is used to evaluate quality and validate investigation reasoning before enabling fully autonomous alert-driven runs.


## 🚀 Getting Started

```bash
# Copy environment file
cp .env.example .env           # set UID/GID

# Start the full observability stack
docker compose up -d

# Verify everything is healthy
docker compose ps
```

Services after startup:

| Service | URL |
|---|---|
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |
| Kafka UI | http://localhost:8080 |
| Schema Registry | http://localhost:8081 |
| OTLP (gRPC) | localhost:4317 |
| OTLP (HTTP) | localhost:4318 |


## 🏗️ Stack

| Layer | Technology |
|---|---|
| Observability | Prometheus · Loki · Tempo · Grafana · OpenTelemetry Collector |
| Observability APIs | Prometheus HTTP · Loki HTTP · Tempo HTTP |
| LLM | Google Gemini by default · primary + fallback, provider/model configurable |
| Vector memory | PostgreSQL + pgvector |
| Event bus | Kafka (KRaft) + Schema Registry |
| MCP interface | MCP Java SDK — SSE/HTTP transport |
| Application | Java 21 · Maven · Spring Boot |


---
### Project structure

```
prism-domain/          Domain model — pure Java, zero framework deps
prism-application/     Use cases and port interfaces
prism-adapters-in/     Inbound adapters: REST, MCP server, Kafka consumer
prism-adapters-out/    Outbound adapters: model reasoning, Prometheus, Loki, Tempo, Postgres, pgvector
prism-boot/            Spring Boot wiring and configuration
```

See [PLAN.md](PLAN.md) for implementation phases.


---
### Architecture

Hexagonal architecture with a clean domain core. The investigation domain has no knowledge of Prometheus, Kafka, or the model — it only knows about signals, findings, and investigations. Adapters translate between the domain's port interfaces and external systems.

```
  REST / MCP server (SSE) / Kafka
               │
        [Inbound Adapters]
               │
       [Application: Use Cases]
               │
          [Domain Core]
               │
       [Outbound Port Interfaces]
               │
   model reasoning · Prometheus · Loki · Tempo · Postgres · pgvector
```

## 🤝 Contributing

Contributions are welcome. Fork the repo, create a feature branch, and open a PR. Please include tests for any new behaviour and ensure the existing test suite passes.


## 📄 License

GPL — see [LICENSE](LICENSE).
