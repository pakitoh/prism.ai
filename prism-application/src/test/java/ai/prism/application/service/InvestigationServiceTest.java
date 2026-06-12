package ai.prism.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.InvestigationKnowledgeBase;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TracingPort;
import ai.prism.application.reasoning.Conclusion;
import ai.prism.application.reasoning.QueryMetrics;
import ai.prism.application.reasoning.SearchLogs;
import ai.prism.application.reasoning.SearchPastInvestigations;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

    private static final int MAX_STEPS = 5;
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-06-10T10:00:00Z"),
            Instant.parse("2026-06-10T10:30:00Z"));

    @Mock
    private ReasoningPort reasoningPort;
    @Mock
    private MetricsPort metricsPort;
    @Mock
    private LogsPort logsPort;
    @Mock
    private TracingPort tracingPort;
    @Mock
    private InvestigationKnowledgeBase knowledgeBase;
    @Mock
    private InvestigationRepository repository;

    private InvestigationService service;

    @BeforeEach
    void setUp() {
        service = serviceWithMaxSteps(MAX_STEPS);
    }

    private InvestigationService serviceWithMaxSteps(int maxSteps) {
        return new InvestigationService(
                reasoningPort, metricsPort, logsPort, tracingPort, knowledgeBase, repository, maxSteps);
    }

    private static InvestigationRequest request() {
        return InvestigationRequest.manual("why is checkout-service erroring?");
    }

    private static Signal metricSignal() {
        return new Signal(SignalType.METRIC, "rate(http_errors_total[5m])", "0.42", WINDOW.from());
    }

    private static Signal logSignal() {
        return new Signal(SignalType.LOG, "{app=\"checkout\"} |= \"ERROR\"", "pool exhausted", WINDOW.from());
    }

    private static Finding finding() {
        return new Finding("DB pool exhausted", "errors track pool saturation", "raise the pool", Confidence.HIGH);
    }

    private static Signal memorySignal() {
        return new Signal(SignalType.MEMORY, "why is checkout-service erroring?",
                "- \"past incident\" => DB pool exhausted (recommended: raise the pool)", WINDOW.from());
    }

    @Test
    void gathersEvidenceAcrossStepsThenConcludes() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new QueryMetrics("rate(http_errors_total[5m])", WINDOW),
                new SearchLogs("{app=\"checkout\"} |= \"ERROR\"", WINDOW),
                new Conclusion(finding()));
        when(metricsPort.queryRange(any(), any())).thenReturn(metricSignal());
        when(logsPort.search(any(), any())).thenReturn(logSignal());

        Investigation investigation = service.handle(request());

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
        assertThat(investigation.signals()).containsExactly(metricSignal(), logSignal());
        assertThat(investigation.finding()).contains(finding());
        verify(repository).save(investigation);
    }

    @Test
    void feedsAccumulatedSignalsBackIntoEachReasoningStep() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new QueryMetrics("rate(http_errors_total[5m])", WINDOW),
                new Conclusion(finding()));
        when(metricsPort.queryRange(any(), any())).thenReturn(metricSignal());

        service.handle(request());

        ArgumentCaptor<InvestigationContext> contexts = ArgumentCaptor.forClass(InvestigationContext.class);
        verify(reasoningPort, times(2)).nextStep(contexts.capture());
        assertThat(contexts.getAllValues().get(0).priorSignals()).isEmpty();
        assertThat(contexts.getAllValues().get(1).priorSignals()).containsExactly(metricSignal());
    }

    @Test
    void failsWhenTheStepLimitIsReachedWithoutAConclusion() {
        service = serviceWithMaxSteps(2);
        when(reasoningPort.nextStep(any()))
                .thenReturn(new QueryMetrics("rate(http_errors_total[5m])", WINDOW));
        when(metricsPort.queryRange(any(), any())).thenReturn(metricSignal());

        Investigation investigation = service.handle(request());

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.failureReason()).get().asString().contains("step limit");
        verify(reasoningPort, times(2)).nextStep(any());
        verify(repository).save(investigation);
    }

    @Test
    void failsAndStillPersistsWhenReasoningThrows() {
        when(reasoningPort.nextStep(any())).thenThrow(new RuntimeException("model timed out"));

        Investigation investigation = service.handle(request());

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.failureReason()).contains("model timed out");
        verify(repository).save(investigation);
    }

    @Test
    void failsAndStillPersistsWhenATelemetryQueryThrows() {
        when(reasoningPort.nextStep(any()))
                .thenReturn(new QueryMetrics("rate(http_errors_total[5m])", WINDOW));
        when(metricsPort.queryRange(any(), any())).thenThrow(new RuntimeException("prometheus unreachable"));

        Investigation investigation = service.handle(request());

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.failureReason()).contains("prometheus unreachable");
        verify(repository).save(investigation);
    }

    @Test
    void recallsPastInvestigationsFromTheKnowledgeBase() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new SearchPastInvestigations("why is checkout-service erroring?"),
                new Conclusion(finding()));
        when(knowledgeBase.findSimilar(any())).thenReturn(memorySignal());

        Investigation investigation = service.handle(request());

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
        assertThat(investigation.signals()).containsExactly(memorySignal());
        verify(knowledgeBase).findSimilar("why is checkout-service erroring?");
    }
}
