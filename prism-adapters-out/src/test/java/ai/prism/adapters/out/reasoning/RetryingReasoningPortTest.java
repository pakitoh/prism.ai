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
