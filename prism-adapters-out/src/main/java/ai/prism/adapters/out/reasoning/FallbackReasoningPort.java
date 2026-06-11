package ai.prism.adapters.out.reasoning;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.ReasoningStep;
import java.util.List;
import java.util.Objects;

/**
 * A {@link ReasoningPort} that tries an ordered list of delegates (primary
 * first) and returns the first one that succeeds. If a delegate throws, the
 * next is tried; if all fail, the last failure is rethrown.
 *
 * <p>Failover is per step: each call to {@link #nextStep} independently tries
 * the primary then the fallbacks, which suits the stateless reasoning step. A
 * single configured delegate degenerates to plain single-model behaviour.
 */
public class FallbackReasoningPort implements ReasoningPort {

    private final List<ReasoningPort> delegates;

    public FallbackReasoningPort(List<ReasoningPort> delegates) {
        Objects.requireNonNull(delegates, "delegates must not be null");
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("at least one reasoning delegate is required");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        RuntimeException lastFailure = null;
        for (ReasoningPort delegate : delegates) {
            try {
                return delegate.nextStep(context);
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
        }
        throw new ReasoningException(
                "All " + delegates.size() + " reasoning models failed; last error: "
                        + (lastFailure != null ? lastFailure.getMessage() : "unknown"),
                lastFailure);
    }
}
