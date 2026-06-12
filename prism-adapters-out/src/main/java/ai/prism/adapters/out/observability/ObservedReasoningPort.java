package ai.prism.adapters.out.observability;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.Conclusion;
import ai.prism.application.reasoning.GetTrace;
import ai.prism.application.reasoning.QueryMetrics;
import ai.prism.application.reasoning.ReasoningStep;
import ai.prism.application.reasoning.SearchLogs;
import ai.prism.application.reasoning.SearchPastInvestigations;
import ai.prism.application.reasoning.SearchTraces;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;

/**
 * Instruments each reasoning step: a {@code prism.reasoning.step} span and timer
 * tagged by the kind of step the model chose. Wrapping the composed
 * {@link ReasoningPort} (after fallback) gives one observation per loop iteration.
 */
public class ObservedReasoningPort implements ReasoningPort {

    private final ReasoningPort delegate;
    private final ObservationRegistry registry;

    public ObservedReasoningPort(ReasoningPort delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        Observation observation = Observation.createNotStarted("prism.reasoning.step", registry);
        return observation.observe(() -> {
            ReasoningStep step = delegate.nextStep(context);
            observation.lowCardinalityKeyValue("step.kind", kindOf(step));
            return step;
        });
    }

    private static String kindOf(ReasoningStep step) {
        return switch (step) {
            case QueryMetrics ignored -> "query_metrics";
            case SearchLogs ignored -> "search_logs";
            case GetTrace ignored -> "get_trace";
            case SearchTraces ignored -> "search_traces";
            case SearchPastInvestigations ignored -> "search_past_investigations";
            case Conclusion ignored -> "conclusion";
        };
    }
}
