package ai.prism.adapters.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

class ObservedInvestigateUseCaseTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void recordsTheInvestigationTaggedBySourceAndOutcome() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        concluded.conclude(new Finding("DB pool exhausted", "ev", "raise the pool", Confidence.HIGH));

        InvestigateUseCase useCase = new ObservedInvestigateUseCase(request -> concluded, registry);

        assertThat(useCase.handle(InvestigationRequest.manual("why errors?"))).isSameAs(concluded);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.investigation")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("source", "MANUAL")
                .hasLowCardinalityKeyValue("outcome", "CONCLUDED");
    }
}
