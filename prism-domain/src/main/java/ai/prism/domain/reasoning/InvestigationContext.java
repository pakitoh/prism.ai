package ai.prism.domain.reasoning;

import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.Signal;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@code ReasoningPort.nextStep}: the originating request, any signals
 * already accumulated (empty for a fresh investigation, non-empty when resuming
 * one), and the step budget — {@code step} of {@code maxSteps} — so the reasoning
 * model knows how much room it has left and can conclude before exhausting it.
 */
public record InvestigationContext(InvestigationRequest request, List<Signal> priorSignals,
                                   int step, int maxSteps) {

    public InvestigationContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(priorSignals, "priorSignals must not be null");
        priorSignals = List.copyOf(priorSignals);
        if (step < 1) {
            throw new IllegalArgumentException("step must be at least 1");
        }
        if (maxSteps < step) {
            throw new IllegalArgumentException("maxSteps (" + maxSteps + ") must not be less than step (" + step + ")");
        }
    }

    /** A context for the first step of a fresh investigation with no prior signals. */
    public static InvestigationContext forRequest(InvestigationRequest request) {
        return new InvestigationContext(request, List.of(), 1, 1);
    }
}
