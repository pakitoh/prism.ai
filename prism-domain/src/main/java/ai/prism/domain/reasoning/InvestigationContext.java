package ai.prism.domain.reasoning;

import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.Signal;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@code ReasoningPort.nextStep}: the originating request plus any
 * signals already accumulated (empty for a fresh investigation, non-empty
 * when resuming one).
 */
public record InvestigationContext(InvestigationRequest request, List<Signal> priorSignals) {

    public InvestigationContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(priorSignals, "priorSignals must not be null");
        priorSignals = List.copyOf(priorSignals);
    }

    /** A context for a fresh investigation with no prior signals. */
    public static InvestigationContext forRequest(InvestigationRequest request) {
        return new InvestigationContext(request, List.of());
    }
}
