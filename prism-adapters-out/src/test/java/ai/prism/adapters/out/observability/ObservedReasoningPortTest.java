package ai.prism.adapters.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.Conclusion;
import ai.prism.application.reasoning.QueryMetrics;
import ai.prism.application.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.TimeWindow;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ObservedReasoningPortTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final InvestigationContext context =
            InvestigationContext.forRequest(InvestigationRequest.manual("why errors?"));

    @Test
    void recordsAStepObservationTaggedAsConclusion() {
        ReasoningStep step = new Conclusion(new Finding("rc", "ev", "act", Confidence.LOW));
        ReasoningPort port = new ObservedReasoningPort(ctx -> step, registry);

        ReasoningStep nextStep = port.nextStep(context);

        assertThat(nextStep).isSameAs(step);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.reasoning.step")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("step.kind", "conclusion");
    }

    @Test
    void tagsAToolStepByItsKind() {
        TimeWindow window = new TimeWindow(
                Instant.parse("2026-06-10T10:00:00Z"), Instant.parse("2026-06-10T10:30:00Z"));
        ReasoningPort port = new ObservedReasoningPort(ctx -> new QueryMetrics("up", window), registry);

        port.nextStep(context);

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.reasoning.step")
                .that()
                .hasLowCardinalityKeyValue("step.kind", "query_metrics");
    }
}
