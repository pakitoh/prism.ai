package ai.prism.adapters.out.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.application.service.InvestigationRunner;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

class ObservedInvestigationRunnerTest {

    // A no-op OpenTelemetry exercises the span lifecycle (build/makeCurrent/end) without an SDK;
    // the actual span + log correlation is verified live against the running collector.
    private final OpenTelemetry openTelemetry = OpenTelemetry.noop();

    @Test
    void runsTheInvestigationAndReturnsTheResult() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        concluded.conclude(Finding.of("DB pool exhausted", "ev", "raise the pool", Confidence.HIGH));

        InvestigationRunner runner = new ObservedInvestigationRunner(investigation -> concluded, openTelemetry);

        assertThat(runner.run(Investigation.open(InvestigationRequest.manual("why errors?")))).isSameAs(concluded);
    }

    @Test
    void propagatesAndEndsTheSpanWhenTheRunThrows() {
        InvestigationRunner failing = investigation -> {
            throw new IllegalStateException("boom");
        };
        InvestigationRunner runner = new ObservedInvestigationRunner(failing, openTelemetry);

        try {
            runner.run(Investigation.open(InvestigationRequest.manual("why errors?")));
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
    }
}
