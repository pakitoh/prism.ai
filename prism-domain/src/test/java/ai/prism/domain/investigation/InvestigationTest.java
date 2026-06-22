package ai.prism.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InvestigationTest {

    private static InvestigationRequest sampleRequest() {
        return InvestigationRequest.manual("why is checkout-service erroring?");
    }

    private static Signal sampleSignal() {
        return Signal.of(
                SignalType.METRIC,
                "rate(http_errors_total[5m])",
                "0.42",
                Instant.parse("2026-06-10T10:00:00Z"));
    }

    private static Finding sampleFinding() {
        return Finding.of(
                "DB connection pool exhausted",
                "error spike correlates with pool saturation at 10:00",
                "raise the pool size and add a saturation alert",
                Confidence.HIGH);
    }

    private static Investigation startedInvestigation() {
        Investigation investigation = Investigation.open(sampleRequest());
        investigation.start();
        return investigation;
    }

    @Nested
    class WhenOpened {

        @Test
        void startsPendingWithNoSignalsOrOutcome() {
            Investigation investigation = Investigation.open(sampleRequest());

            assertThat(investigation.id()).isNotNull();
            assertThat(investigation.status()).isEqualTo(InvestigationStatus.PENDING);
            assertThat(investigation.request()).isEqualTo(sampleRequest());
            assertThat(investigation.signals()).isEmpty();
            assertThat(investigation.finding()).isEmpty();
            assertThat(investigation.failureReason()).isEmpty();
        }

        @Test
        void assignsAUniqueId() {
            assertThat(Investigation.open(sampleRequest()).id())
                    .isNotEqualTo(Investigation.open(sampleRequest()).id());
        }
    }

    @Nested
    class Start {

        @Test
        void movesFromPendingToInProgress() {
            Investigation investigation = Investigation.open(sampleRequest());
            investigation.start();
            assertThat(investigation.status()).isEqualTo(InvestigationStatus.IN_PROGRESS);
        }

        @Test
        void rejectsStartingWhenNotPending() {
            Investigation investigation = startedInvestigation();
            assertThatThrownBy(investigation::start)
                    .isInstanceOf(InvestigationStateException.class)
                    .hasMessageContaining("IN_PROGRESS");
        }
    }

    @Nested
    class RecordSignal {

        @Test
        void appendsSignalsWhileInProgress() {
            Investigation investigation = startedInvestigation();
            investigation.recordSignal(sampleSignal());
            assertThat(investigation.signals()).containsExactly(sampleSignal());
        }

        @Test
        void rejectsRecordingBeforeStarted() {
            Investigation investigation = Investigation.open(sampleRequest());
            assertThatThrownBy(() -> investigation.recordSignal(sampleSignal()))
                    .isInstanceOf(InvestigationStateException.class);
        }

        @Test
        void rejectsRecordingAfterConcluded() {
            Investigation investigation = startedInvestigation();
            investigation.conclude(sampleFinding());
            assertThatThrownBy(() -> investigation.recordSignal(sampleSignal()))
                    .isInstanceOf(InvestigationStateException.class);
        }

        @Test
        void rejectsNullSignal() {
            Investigation investigation = startedInvestigation();
            assertThatNullPointerException().isThrownBy(() -> investigation.recordSignal(null));
        }
    }

    @Nested
    class Conclude {

        @Test
        void movesToConcludedAndExposesTheFinding() {
            Investigation investigation = startedInvestigation();
            investigation.conclude(sampleFinding());
            assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
            assertThat(investigation.finding()).contains(sampleFinding());
        }

        @Test
        void rejectsConcludingBeforeStarted() {
            Investigation investigation = Investigation.open(sampleRequest());
            assertThatThrownBy(() -> investigation.conclude(sampleFinding()))
                    .isInstanceOf(InvestigationStateException.class);
        }

        @Test
        void rejectsConcludingTwice() {
            Investigation investigation = startedInvestigation();
            investigation.conclude(sampleFinding());
            assertThatThrownBy(() -> investigation.conclude(sampleFinding()))
                    .isInstanceOf(InvestigationStateException.class);
        }
    }

    @Nested
    class Fail {

        @Test
        void canFailFromPending() {
            Investigation investigation = Investigation.open(sampleRequest());
            investigation.fail("otel collector unreachable");
            assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
            assertThat(investigation.failureReason()).contains("otel collector unreachable");
        }

        @Test
        void canFailFromInProgress() {
            Investigation investigation = startedInvestigation();
            investigation.fail("model timed out");
            assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        }

        @Test
        void cannotFailOnceConcluded() {
            Investigation investigation = startedInvestigation();
            investigation.conclude(sampleFinding());
            assertThatThrownBy(() -> investigation.fail("too late"))
                    .isInstanceOf(InvestigationStateException.class);
        }
    }

    @Nested
    class SignalsView {

        @Test
        void isAnImmutableSnapshot() {
            Investigation investigation = startedInvestigation();
            investigation.recordSignal(sampleSignal());
            assertThatThrownBy(() -> investigation.signals().add(sampleSignal()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
