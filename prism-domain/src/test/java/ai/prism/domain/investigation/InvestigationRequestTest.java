package ai.prism.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvestigationRequestTest {

    @Test
    void manualFactoryBuildsAManualRequestWithNoServiceOrWindow() {
        InvestigationRequest request = InvestigationRequest.manual("what is wrong with checkout?");

        assertThat(request.source()).isEqualTo(RequestSource.MANUAL);
        assertThat(request.query()).isEqualTo("what is wrong with checkout?");
        assertThat(request.service()).isEmpty();
        assertThat(request.window()).isEmpty();
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> InvestigationRequest.manual("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullQuery() {
        assertThatNullPointerException().isThrownBy(
                () -> new InvestigationRequest(null, Optional.empty(), Optional.empty(), RequestSource.MANUAL));
    }

    @Test
    void rejectsNullOptionalsToForceExplicitAbsence() {
        assertThatNullPointerException().isThrownBy(
                () -> new InvestigationRequest("q", null, Optional.empty(), RequestSource.MANUAL));
        assertThatNullPointerException().isThrownBy(
                () -> new InvestigationRequest("q", Optional.empty(), null, RequestSource.MANUAL));
    }
}
