package ai.prism.adapters.out.reasoning;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.TimeWindow;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ObservedReasoningPortTest {

    // A no-op OpenTelemetry exercises the span lifecycle without an SDK; the actual
    // prism.reasoning.step span (nested under prism.investigation) is verified live in Tempo.
    private final OpenTelemetry openTelemetry = OpenTelemetry.noop();
    private final InvestigationContext context =
            InvestigationContext.forRequest(InvestigationRequest.manual("why errors?"));

    @Test
    void returnsTheDelegatesConclusionStep() {
        ReasoningStep step = new Conclusion(Finding.of("rc", "ev", "act", Confidence.LOW));
        ReasoningPort port = new ObservedReasoningPort(ctx -> step, openTelemetry);

        assertThat(port.nextStep(context)).isSameAs(step);
    }

    @Test
    void returnsAToolStepFromTheDelegate() {
        TimeWindow window = new TimeWindow(
                Instant.parse("2026-06-10T10:00:00Z"), Instant.parse("2026-06-10T10:30:00Z"));
        QueryMetrics step = new QueryMetrics("up", window);
        ReasoningPort port = new ObservedReasoningPort(ctx -> step, openTelemetry);

        assertThat(port.nextStep(context)).isSameAs(step);
    }
}
