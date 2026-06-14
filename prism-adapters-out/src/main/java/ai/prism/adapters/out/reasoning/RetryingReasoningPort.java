package ai.prism.adapters.out.reasoning;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.ReasoningStep;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ReasoningPort} that retries a reasoning step up to {@code maxAttempts}
 * times, switching to a different underlying model on each error.
 *
 * <p>The configured delegates (one per model) are used round-robin: attempt
 * {@code n} uses {@code delegates[n % size]}. The first attempt that succeeds
 * wins; only when all attempts fail is the last error rethrown. Each delegate
 * carries its model id so retry/exhaustion logs name the model that failed —
 * making it obvious which configuration to check (e.g. a 401 points at the wrong
 * key for that specific model).
 *
 * <p>This keeps the investigation loop model-agnostic: it simply asks for the
 * next step, and this port transparently rotates models and retries on failure
 * (e.g. a 429) rather than giving up after a single pass over the model list.
 * Decoupling {@code maxAttempts} from the number of models means a transient
 * error can be retried more times than there are models.
 */
public class RetryingReasoningPort implements ReasoningPort {

    private static final Logger log = LoggerFactory.getLogger(RetryingReasoningPort.class);

    /** A reasoning delegate paired with the model id it targets, for logging. */
    public record Delegate(String modelId, ReasoningPort port) {
        public Delegate {
            Objects.requireNonNull(port, "port must not be null");
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId must not be blank");
            }
        }
    }

    private final List<Delegate> delegates;
    private final int maxAttempts;

    public RetryingReasoningPort(List<Delegate> delegates, int maxAttempts) {
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
        String lastModelId = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Delegate delegate = delegates.get(attempt % delegates.size());
            try {
                return delegate.port().nextStep(context);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                lastModelId = delegate.modelId();
                log.warn("Reasoning attempt {}/{} on model '{}' failed, rotating to next model: {}",
                        attempt + 1, maxAttempts, delegate.modelId(), failure.getMessage());
            }
        }
        String errorMessage = "Reasoning failed after " + maxAttempts + " attempt(s) across "
                + delegates.size() + " model(s) " + modelIds()
                + "; last error on '" + lastModelId + "': "
                + (lastFailure != null ? lastFailure.getMessage() : "unknown");
        log.warn(errorMessage);
        throw new ReasoningException(errorMessage, lastFailure);
    }

    private List<String> modelIds() {
        return delegates.stream().map(Delegate::modelId).toList();
    }
}
