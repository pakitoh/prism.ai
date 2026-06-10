package ai.prism.domain.investigation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariants of the smaller value objects whose only behaviour is
 * construction-time validation.
 */
class ValueObjectValidationTest {

    @Test
    void signalRejectsBlankQuery() {
        assertThatThrownBy(() -> new Signal(SignalType.LOG, " ", "content", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findingRejectsBlankRootCause() {
        assertThatThrownBy(() -> new Finding("", "evidence", "action", Confidence.LOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void investigationIdRoundTripsThroughString() {
        InvestigationId id = InvestigationId.newId();
        assertThatThrownBy(() -> InvestigationId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThat(InvestigationId.of(id.toString())).isEqualTo(id);
    }
}
