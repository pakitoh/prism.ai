package ai.prism.adapters.out.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.InvestigationRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryingReasoningPortTest {

    private final InvestigationContext context =
            InvestigationContext.forRequest(InvestigationRequest.manual("why errors?"));
    private final ReasoningStep step =
            new Conclusion(Finding.of("root", "evidence", "action", Confidence.LOW));

    private RetryingReasoningPort.Delegate succeeds(AtomicInteger calls) {
        return new RetryingReasoningPort.Delegate("ok-model", ctx -> {
            calls.incrementAndGet();
            return step;
        });
    }

    private RetryingReasoningPort.Delegate fails(AtomicInteger calls, String message) {
        return new RetryingReasoningPort.Delegate("failing-model", ctx -> {
            calls.incrementAndGet();
            throw new RuntimeException(message);
        });
    }

    @Test
    void returnsTheFirstModelResultWithoutRetrying() {
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger secondary = new AtomicInteger();
        ReasoningPort port = new RetryingReasoningPort(List.of(succeeds(primary), succeeds(secondary)), 4);

        assertThat(port.nextStep(context)).isSameAs(step);
        assertThat(primary).hasValue(1);
        assertThat(secondary).hasValue(0);
    }

    @Test
    void switchesToTheNextModelOnError() {
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger secondary = new AtomicInteger();
        ReasoningPort port = new RetryingReasoningPort(List.of(fails(primary, "429"), succeeds(secondary)), 4);

        assertThat(port.nextStep(context)).isSameAs(step);
        assertThat(primary).hasValue(1);    // tried once, failed
        assertThat(secondary).hasValue(1);  // rotated to the next model, succeeded
    }

    @Test
    void retriesUpToMaxAttemptsRotatingAcrossModels() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        ReasoningPort port = new RetryingReasoningPort(List.of(fails(a, "down-a"), fails(b, "down-b")), 5);

        assertThatThrownBy(() -> port.nextStep(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("5 attempt");
        // round-robin over 2 models for 5 attempts: a, b, a, b, a
        assertThat(a).hasValue(3);
        assertThat(b).hasValue(2);
    }

    @Test
    void retriesASingleModelUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        ReasoningPort port = new RetryingReasoningPort(List.of(fails(calls, "boom")), 3);

        assertThatThrownBy(() -> port.nextStep(context)).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void backsOffBetweenAttemptsButNotAfterSuccess() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        // a fails, b succeeds → exactly one failed attempt → exactly one backoff.
        ReasoningPort port = new RetryingReasoningPort(
                List.of(fails(a, "429"), succeeds(b)), 4,
                Duration.ofMillis(500), Duration.ofSeconds(8), sleeps::add);

        assertThat(port.nextStep(context)).isSameAs(step);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(0L, 500L);   // full jitter within initial backoff
    }

    @Test
    void backoffGrowsAcrossRepeatedFailuresAndIsCapped() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        // 5 attempts on one failing model → 4 backoffs (none after the final attempt).
        ReasoningPort port = new RetryingReasoningPort(
                List.of(fails(calls, "503")), 5,
                Duration.ofMillis(100), Duration.ofMillis(300), sleeps::add);

        assertThatThrownBy(() -> port.nextStep(context)).isInstanceOf(ReasoningException.class);
        assertThat(sleeps).hasSize(4);
        // full-jitter caps: attempt n bounded by min(max, 100*2^n) = 100,200,300,300
        assertThat(sleeps.get(0)).isBetween(0L, 100L);
        assertThat(sleeps.get(1)).isBetween(0L, 200L);
        assertThat(sleeps.get(2)).isBetween(0L, 300L);
        assertThat(sleeps.get(3)).isBetween(0L, 300L);
    }

    @Test
    void abortsWhenInterruptedWhileBackingOff() {
        AtomicInteger calls = new AtomicInteger();
        ReasoningPort port = new RetryingReasoningPort(
                List.of(fails(calls, "503")), 5,
                Duration.ofMillis(100), Duration.ofSeconds(1),
                millis -> { throw new InterruptedException("stop"); });

        assertThatThrownBy(() -> port.nextStep(context))
                .isInstanceOf(ReasoningException.class)
                .hasMessageContaining("interrupted");
        assertThat(calls).hasValue(1);   // failed once, then the interrupt aborted the retries
        assertThat(Thread.interrupted()).isTrue();   // interrupt flag restored (and cleared here)
    }

    @Test
    void requiresAtLeastOneDelegate() {
        assertThatThrownBy(() -> new RetryingReasoningPort(List.of(), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresPositiveMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> new RetryingReasoningPort(List.of(succeeds(calls)), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
