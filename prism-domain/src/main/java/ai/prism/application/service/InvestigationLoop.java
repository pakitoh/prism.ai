package ai.prism.application.service;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.MemoryPort;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TelemetryException;
import ai.prism.application.port.out.TracingPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.GetTrace;
import ai.prism.domain.reasoning.ListLogLabelValues;
import ai.prism.domain.reasoning.ListTraceTagValues;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.reasoning.SearchLogs;
import ai.prism.domain.reasoning.SearchPastInvestigations;
import ai.prism.domain.reasoning.SearchTraces;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Drives the investigation loop — the heart of the product.
 *
 * <p>Repeatedly asks the {@link ReasoningPort} for the next step and acts on it:
 * a tool request is dispatched to the matching telemetry port and the resulting
 * {@link ai.prism.domain.investigation.Signal} is recorded on the aggregate; a
 * conclusion ends the loop. The loop is bounded by {@code maxSteps} so it can
 * never run forever.
 *
 * <p>A failure while gathering evidence from a telemetry port is best-effort: it is
 * recorded as an error {@link ai.prism.domain.investigation.Signal} so the model can
 * react and try a different query, rather than aborting the run. Only a reasoning
 * failure (after the port's own retries) marks the investigation {@code FAILED}. Either
 * way the investigation is still persisted, so none is ever lost.
 *
 * <p>It runs an already-opened aggregate ({@link InvestigationRunner}); opening the
 * aggregate and choosing synchronous vs. asynchronous execution is the job of
 * {@link InvestigationCommandsService}.
 */
public class InvestigationLoop implements InvestigationRunner {

    private final ReasoningPort reasoningPort;
    private final MetricsPort metricsPort;
    private final LogsPort logsPort;
    private final TracingPort tracingPort;
    private final MemoryPort knowledgeBase;
    private final InvestigationRepository repository;
    private final Clock clock;
    private final int maxSteps;

    public InvestigationLoop(
            ReasoningPort reasoningPort,
            MetricsPort metricsPort,
            LogsPort logsPort,
            TracingPort tracingPort,
            MemoryPort knowledgeBase,
            InvestigationRepository repository,
            Clock clock,
            int maxSteps) {
        this.reasoningPort = Objects.requireNonNull(reasoningPort, "reasoningPort must not be null");
        this.metricsPort = Objects.requireNonNull(metricsPort, "metricsPort must not be null");
        this.logsPort = Objects.requireNonNull(logsPort, "logsPort must not be null");
        this.tracingPort = Objects.requireNonNull(tracingPort, "tracingPort must not be null");
        this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1");
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public Investigation run(Investigation investigation) {
        investigation.start();
        try {
            investigate(investigation);
        } catch (RuntimeException failure) {
            investigation.fail(describe(failure));
        }
        repository.save(investigation);
        return investigation;
    }

    private void investigate(Investigation investigation) {
        seedSchema(investigation);
        for (int step = 0; step < maxSteps; step++) {
            InvestigationContext context = new InvestigationContext(
                    investigation.request(), investigation.signals(), step + 1, maxSteps);
            ReasoningStep next = reasoningPort.nextStep(context);
            switch (next) {
                case QueryMetrics m -> dispatch(investigation, SignalType.METRIC, m.promQl(),
                        () -> metricsPort.queryRange(m.promQl(), m.window()));
                case SearchLogs l -> dispatch(investigation, SignalType.LOG, l.logQl(),
                        () -> logsPort.search(l.logQl(), l.window()));
                case GetTrace t -> dispatch(investigation, SignalType.TRACE, t.traceId(),
                        () -> tracingPort.getTrace(t.traceId()));
                case SearchTraces s -> dispatch(investigation, SignalType.TRACE, s.traceQl(),
                        () -> tracingPort.searchTraces(s.traceQl(), s.window()));
                case ListLogLabelValues v -> dispatch(investigation, SignalType.SCHEMA,
                        "log label values: " + v.label(), () -> logsPort.listLabelValues(v.label()));
                case ListTraceTagValues v -> dispatch(investigation, SignalType.SCHEMA,
                        "trace tag values: " + v.tag(), () -> tracingPort.listTagValues(v.tag()));
                case SearchPastInvestigations p -> investigation.recordSignal(knowledgeBase.findSimilar(p.query()));
                case Conclusion c -> {
                    investigation.conclude(c.finding());
                    return;
                }
            }
        }
        investigation.fail("reached the step limit of " + maxSteps + " without a conclusion");
    }

    /**
     * Seeds the investigation with the telemetry schema before any reasoning, so the model's
     * first prompt shows the real label/metric/tag names and cannot guess wrong ones (e.g.
     * {@code service} instead of OTel-native {@code service_name}). Best-effort and consumes no
     * reasoning step; an unreachable datasource is simply skipped — the model would discover that
     * when it queries.
     */
    private void seedSchema(Investigation investigation) {
        recordSchema(investigation, logsPort::listLabelNames);
        recordSchema(investigation, metricsPort::listMetricNames);
        recordSchema(investigation, tracingPort::listTagNames);
    }

    private void recordSchema(Investigation investigation, Supplier<Signal> call) {
        try {
            Signal signal = call.get();
            if (signal != null) {
                investigation.recordSignal(signal);
            }
        } catch (RuntimeException bestEffort) {
            // Seeding is best-effort: skip silently rather than record a noisy error signal.
        }
    }

    /**
     * Dispatches a telemetry query, best-effort: a thrown {@link RuntimeException} (e.g.
     * an HTTP 400 from a malformed query) is recorded as an error {@link Signal} carrying
     * the failed query, so the next reasoning step can correct it instead of the whole
     * investigation failing.
     */
    private void dispatch(Investigation investigation, SignalType type, String query, Supplier<Signal> call) {
        try {
            investigation.recordSignal(call.get());
        } catch (TelemetryException failure) {
            investigation.recordSignal(Signal.of(type, query, toolFailure(type, failure), clock.instant()));
        } catch (RuntimeException failure) {
            investigation.recordSignal(Signal.of(type, query,
                    "Query failed: " + describe(failure) + ". Adjust the query and try again.",
                    clock.instant()));
        }
    }

    /**
     * Frames a telemetry failure for the model. A rejected query is the model's to fix. An
     * unreachable/erroring datasource is infrastructure: we say so explicitly and withhold the
     * raw transport error, so the model does not mistake it for evidence (e.g. search logs for
     * "ConnectException") or infer a service outage from a tooling failure.
     */
    private static String toolFailure(SignalType type, TelemetryException failure) {
        return switch (failure.kind()) {
            case QUERY_REJECTED -> "The datasource rejected this query: " + describe(failure)
                    + ". The query is likely malformed — correct it and try again.";
            case DATASOURCE_UNREACHABLE, DATASOURCE_ERROR -> "The " + sourceName(type)
                    + " datasource is unavailable right now. This is an infrastructure failure — not a"
                    + " problem with the query and not evidence about the target service. Do not change"
                    + " the query in response, and do not conclude the service is down from this; rely on"
                    + " other evidence.";
        };
    }

    private static String sourceName(SignalType type) {
        return switch (type) {
            case METRIC -> "metrics (Prometheus)";
            case LOG -> "logs (Loki)";
            case TRACE -> "tracing (Tempo)";
            case MEMORY -> "memory";
            case SCHEMA -> "schema discovery";
        };
    }

    private static String describe(RuntimeException failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }
}
