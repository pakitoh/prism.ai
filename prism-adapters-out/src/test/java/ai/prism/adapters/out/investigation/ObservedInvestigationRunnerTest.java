package ai.prism.adapters.out.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.application.service.InvestigationRunner;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

class ObservedInvestigationRunnerTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void recordsTheInvestigationTaggedBySourceAndOutcome() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        concluded.conclude(Finding.of("DB pool exhausted", "ev", "raise the pool", Confidence.HIGH));

        InvestigationRunner runner = new ObservedInvestigationRunner(investigation -> concluded, registry);

        assertThat(runner.run(Investigation.open(InvestigationRequest.manual("why errors?")))).isSameAs(concluded);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.investigation")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("source", "MANUAL")
                .hasLowCardinalityKeyValue("outcome", "CONCLUDED");
    }
}
