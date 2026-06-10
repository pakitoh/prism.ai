package ai.prism.application.service;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TracingPort;
import ai.prism.application.reasoning.Conclusion;
import ai.prism.application.reasoning.GetTrace;
import ai.prism.application.reasoning.QueryMetrics;
import ai.prism.application.reasoning.ReasoningStep;
import ai.prism.application.reasoning.SearchLogs;
import ai.prism.application.reasoning.SearchTraces;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import java.util.Objects;

/**
 * Drives the investigation loop — the heart of the product.
 *
 * <p>Repeatedly asks the {@link ReasoningPort} for the next step and acts on it:
 * a tool request is dispatched to the matching telemetry port and the resulting
 * {@link ai.prism.domain.investigation.Signal} is recorded on the aggregate; a
 * conclusion ends the loop. The loop is bounded by {@code maxSteps} so it can
 * never run forever.
 *
 * <p>Any failure during reasoning or evidence-gathering marks the investigation
 * {@code FAILED} and it is still persisted, so no investigation is ever lost.
 */
public class InvestigationService implements InvestigateUseCase {

    private final ReasoningPort reasoningPort;
    private final MetricsPort metricsPort;
    private final LogsPort logsPort;
    private final TracingPort tracingPort;
    private final InvestigationRepository repository;
    private final int maxSteps;

    public InvestigationService(
            ReasoningPort reasoningPort,
            MetricsPort metricsPort,
            LogsPort logsPort,
            TracingPort tracingPort,
            InvestigationRepository repository,
            int maxSteps) {
        this.reasoningPort = Objects.requireNonNull(reasoningPort, "reasoningPort must not be null");
        this.metricsPort = Objects.requireNonNull(metricsPort, "metricsPort must not be null");
        this.logsPort = Objects.requireNonNull(logsPort, "logsPort must not be null");
        this.tracingPort = Objects.requireNonNull(tracingPort, "tracingPort must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1");
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public Investigation handle(InvestigationRequest request) {
        Investigation investigation = Investigation.open(request);
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
                case QueryMetrics m -> investigation.recordSignal(metricsPort.queryRange(m.promQl(), m.window()));
                case SearchLogs l -> investigation.recordSignal(logsPort.search(l.logQl(), l.window()));
                case GetTrace t -> investigation.recordSignal(tracingPort.getTrace(t.traceId()));
                case SearchTraces s -> investigation.recordSignal(tracingPort.searchTraces(s.service(), s.window()));
                case Conclusion c -> {
                    investigation.conclude(c.finding());
                    return;
                }
            }
        }
        investigation.fail("reached the step limit of " + maxSteps + " without a conclusion");
    }

    private static String describe(RuntimeException failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }
}
