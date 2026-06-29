package ai.prism.adapters.out.reasoning;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.ReasoningStep;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
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
 *
 * <p>Between a failed attempt and the next it waits with exponential backoff and
 * full jitter — {@code min(maxBackoff, initialBackoff × 2^attempt)}, randomized in
 * {@code [0, that]}. Without a pause, all attempts fire within milliseconds and
 * simply pile more load onto an already-overloaded provider (a 503/429 spike never
 * gets a chance to clear); the jitter avoids retrying every model in lockstep.
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

    /** Pauses the current thread; abstracted so tests can verify backoff without real waits. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final List<Delegate> delegates;
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final Sleeper sleeper;

    /** Convenience constructor with no backoff — retained for tests and simple wiring. */
    public RetryingReasoningPort(List<Delegate> delegates, int maxAttempts) {
        this(delegates, maxAttempts, Duration.ZERO, Duration.ZERO, Thread::sleep);
    }

    public RetryingReasoningPort(List<Delegate> delegates, int maxAttempts,
                                 Duration initialBackoff, Duration maxBackoff, Sleeper sleeper) {
        Objects.requireNonNull(delegates, "delegates must not be null");
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("at least one reasoning delegate is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("backoff durations must not be negative");
        }
        this.delegates = List.copyOf(delegates);
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoff.toMillis();
        this.maxBackoffMillis = maxBackoff.toMillis();
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        RuntimeException lastFailure = null;
        String lastModelId = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Delegate delegate = delegates.get(attempt % delegates.size());
            try {
                ReasoningStep step = delegate.port().nextStep(context);
                // Record which model actually answered on the surrounding prism.reasoning.step
                // span (made current by ObservedReasoningPort) — the model is only known here.
                io.opentelemetry.api.trace.Span.current().setAttribute("model.id", delegate.modelId());
                return step;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                lastModelId = delegate.modelId();
                boolean willRetry = attempt < maxAttempts - 1;
                log.warn("Reasoning attempt {}/{} on model '{}' failed{}: {}",
                        attempt + 1, maxAttempts, delegate.modelId(),
                        willRetry ? ", backing off then rotating to next model" : "",
                        failure.getMessage());
                if (willRetry) {
                    backoff(attempt);
                }
            }
        }
        String errorMessage = "Reasoning failed after " + maxAttempts + " attempt(s) across "
                + delegates.size() + " model(s) " + modelIds()
                + "; last error on '" + lastModelId + "': "
                + (lastFailure != null ? lastFailure.getMessage() : "unknown");
        log.warn(errorMessage);
        throw new ReasoningException(errorMessage, lastFailure);
    }

    /** Sleeps for a jittered, exponentially-growing delay before the next attempt. */
    private void backoff(int attempt) {
        if (initialBackoffMillis == 0 && maxBackoffMillis == 0) {
            return;
        }
        long exponential = initialBackoffMillis << Math.min(attempt, 30);  // initial × 2^attempt, guarded
        long capped = Math.min(maxBackoffMillis, exponential < 0 ? maxBackoffMillis : exponential);
        long jittered = capped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(capped + 1);
        try {
            sleeper.sleep(jittered);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ReasoningException("Reasoning retry interrupted while backing off", interrupted);
        }
    }

    private List<String> modelIds() {
        return delegates.stream().map(Delegate::modelId).toList();
    }
}
