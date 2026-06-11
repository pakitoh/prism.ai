package ai.prism.adapters.out.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.Conclusion;
import ai.prism.application.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.InvestigationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class FallbackReasoningPortTest {

    private final InvestigationContext context =
            InvestigationContext.forRequest(InvestigationRequest.manual("why errors?"));
    private final ReasoningStep step =
            new Conclusion(new Finding("root", "evidence", "action", Confidence.LOW));

    @Test
    void returnsThePrimaryResultWithoutTouchingFallbacks() {
        ReasoningPort primary = ctx -> step;
        ReasoningPort fallback = ctx -> {
            throw new AssertionError("fallback must not be called when the primary succeeds");
        };

        ReasoningPort port = new FallbackReasoningPort(List.of(primary, fallback));

        assertThat(port.nextStep(context)).isSameAs(step);
    }

    @Test
    void fallsThroughToTheNextDelegateWhenThePrimaryFails() {
        ReasoningPort primary = ctx -> {
            throw new RuntimeException("primary down");
        };
        ReasoningPort fallback = ctx -> step;

        ReasoningPort port = new FallbackReasoningPort(List.of(primary, fallback));

        assertThat(port.nextStep(context)).isSameAs(step);
    }

    @Test
    void throwsWhenEveryDelegateFails() {
        ReasoningPort first = ctx -> {
            throw new RuntimeException("first down");
        };
        ReasoningPort second = ctx -> {
            throw new RuntimeException("second down");
        };

        ReasoningPort port = new FallbackReasoningPort(List.of(first, second));

        assertThatThrownBy(() -> port.nextStep(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("second down");
    }

    @Test
    void requiresAtLeastOneDelegate() {
        assertThatThrownBy(() -> new FallbackReasoningPort(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
