package ai.prism.domain.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.prism.domain.investigation.InvestigationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvestigationContextTest {

    private static final InvestigationRequest REQUEST = InvestigationRequest.manual("why errors?");

    @Test
    void carriesTheStepBudget() {
        InvestigationContext context = new InvestigationContext(REQUEST, List.of(), 3, 30);

        assertThat(context.step()).isEqualTo(3);
        assertThat(context.maxSteps()).isEqualTo(30);
    }

    @Test
    void forRequestStartsAtStepOne() {
        InvestigationContext context = InvestigationContext.forRequest(REQUEST);

        assertThat(context.step()).isEqualTo(1);
        assertThat(context.priorSignals()).isEmpty();
    }

    @Test
    void rejectsANonPositiveStep() {
        assertThatThrownBy(() -> new InvestigationContext(REQUEST, List.of(), 0, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step");
    }

    @Test
    void rejectsAMaxStepsBelowTheCurrentStep() {
        assertThatThrownBy(() -> new InvestigationContext(REQUEST, List.of(), 5, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSteps");
    }
}
