package ai.prism.adapters.out.reasoning;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.ReasoningStep;
import java.util.List;
import java.util.Objects;

/**
 * A {@link ReasoningPort} that retries a reasoning step up to {@code maxAttempts}
 * times, switching to a different underlying model on each error.
 *
 * <p>The configured delegates (one per model) are used round-robin: attempt
 * {@code n} uses {@code delegates[n % size]}. The first attempt that succeeds
 * wins; only when all attempts fail is the last error rethrown.
 *
 * <p>This keeps the investigation loop model-agnostic: it simply asks for the
 * next step, and this port transparently rotates models and retries on failure
 * (e.g. a 429) rather than giving up after a single pass over the model list.
 * Decoupling {@code maxAttempts} from the number of models means a transient
 * error can be retried more times than there are models.
 */
public class RetryingReasoningPort implements ReasoningPort {

    private final List<ReasoningPort> delegates;
    private final int maxAttempts;

    public RetryingReasoningPort(List<ReasoningPort> delegates, int maxAttempts) {
        Objects.requireNonNull(delegates, "delegates must not be null");
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("at least one reasoning delegate is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.delegates = List.copyOf(delegates);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            ReasoningPort delegate = delegates.get(attempt % delegates.size());
            try {
                return delegate.nextStep(context);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                // On error, the next attempt rotates to the next model.
            }
        }
        throw new ReasoningException(
                "Reasoning failed after " + maxAttempts + " attempt(s) across "
                        + delegates.size() + " model(s); last error: "
                        + (lastFailure != null ? lastFailure.getMessage() : "unknown"),
                lastFailure);
    }
}
