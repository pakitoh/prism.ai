package ai.prism.application.service;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.MemoryPort;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TracingPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.GetTrace;
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
        for (int step = 0; step < maxSteps; step++) {
            InvestigationContext context =
                    new InvestigationContext(investigation.request(), investigation.signals());
            ReasoningStep next = reasoningPort.nextStep(context);
            switch (next) {
                case QueryMetrics m -> dispatch(investigation, SignalType.METRIC, m.promQl(),
                        () -> metricsPort.queryRange(m.promQl(), m.window()));
                case SearchLogs l -> dispatch(investigation, SignalType.LOG, l.logQl(),
                        () -> logsPort.search(l.logQl(), l.window()));
                case GetTrace t -> dispatch(investigation, SignalType.TRACE, t.traceId(),
                        () -> tracingPort.getTrace(t.traceId()));
                case SearchTraces s -> dispatch(investigation, SignalType.TRACE, s.service(),
                        () -> tracingPort.searchTraces(s.service(), s.window()));
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
     * Dispatches a telemetry query, best-effort: a thrown {@link RuntimeException} (e.g.
     * an HTTP 400 from a malformed query) is recorded as an error {@link Signal} carrying
     * the failed query, so the next reasoning step can correct it instead of the whole
     * investigation failing.
     */
    private void dispatch(Investigation investigation, SignalType type, String query, Supplier<Signal> call) {
        try {
            investigation.recordSignal(call.get());
        } catch (RuntimeException failure) {
            investigation.recordSignal(new Signal(type, query,
                    "Query failed: " + describe(failure) + ". Adjust the query and try again.",
                    clock.instant()));
        }
    }

    private static String describe(RuntimeException failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }
}
