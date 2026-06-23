package ai.prism.application.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.MemoryPort;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TelemetryException;
import ai.prism.application.port.out.TracingPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.SearchLogs;
import ai.prism.domain.reasoning.SearchPastInvestigations;
import ai.prism.application.service.InvestigationLoop;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestigationLoopTest {

    private static final int MAX_STEPS = 5;
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-06-10T10:00:00Z"),
            Instant.parse("2026-06-10T10:30:00Z"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-10T10:15:00Z"), ZoneOffset.UTC);

    @Mock
    private ReasoningPort reasoningPort;
    @Mock
    private MetricsPort metricsPort;
    @Mock
    private LogsPort logsPort;
    @Mock
    private TracingPort tracingPort;
    @Mock
    private MemoryPort knowledgeBase;
    @Mock
    private InvestigationRepository repository;

    private InvestigationLoop service;

    @BeforeEach
    void setUp() {
        service = serviceWithMaxSteps(MAX_STEPS);
    }

    private InvestigationLoop serviceWithMaxSteps(int maxSteps) {
        return new InvestigationLoop(
                reasoningPort, metricsPort, logsPort, tracingPort, knowledgeBase, repository, CLOCK, maxSteps);
    }

    private static InvestigationRequest request() {
        return InvestigationRequest.manual("why is checkout-service erroring?");
    }

    private static Signal metricSignal() {
        return Signal.of(SignalType.METRIC, "rate(http_errors_total[5m])", "0.42", WINDOW.from());
    }

    private static Signal logSignal() {
        return Signal.of(SignalType.LOG, "{app=\"checkout\"} |= \"ERROR\"", "pool exhausted", WINDOW.from());
    }

    private static Finding finding() {
        return Finding.of("DB pool exhausted", "errors track pool saturation", "raise the pool", Confidence.HIGH);
    }

    private static Signal memorySignal() {
        return Signal.of(SignalType.MEMORY, "why is checkout-service erroring?",
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

        Investigation investigation = service.run(Investigation.open(request()));

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

        service.run(Investigation.open(request()));

        ArgumentCaptor<InvestigationContext> contexts = ArgumentCaptor.forClass(InvestigationContext.class);
        verify(reasoningPort, times(2)).nextStep(contexts.capture());
        assertThat(contexts.getAllValues().get(0).priorSignals()).isEmpty();
        assertThat(contexts.getAllValues().get(1).priorSignals()).containsExactly(metricSignal());
        // each step carries an increasing 1-based budget position against the configured max
        assertThat(contexts.getAllValues().get(0).step()).isEqualTo(1);
        assertThat(contexts.getAllValues().get(1).step()).isEqualTo(2);
        assertThat(contexts.getAllValues()).allSatisfy(c -> assertThat(c.maxSteps()).isEqualTo(MAX_STEPS));
    }

    @Test
    void failsWhenTheStepLimitIsReachedWithoutAConclusion() {
        service = serviceWithMaxSteps(2);
        when(reasoningPort.nextStep(any()))
                .thenReturn(new QueryMetrics("rate(http_errors_total[5m])", WINDOW));
        when(metricsPort.queryRange(any(), any())).thenReturn(metricSignal());

        Investigation investigation = service.run(Investigation.open(request()));

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.failureReason()).get().asString().contains("step limit");
        verify(reasoningPort, times(2)).nextStep(any());
        verify(repository).save(investigation);
    }

    @Test
    void failsAndStillPersistsWhenReasoningThrows() {
        when(reasoningPort.nextStep(any())).thenThrow(new RuntimeException("model timed out"));

        Investigation investigation = service.run(Investigation.open(request()));

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.failureReason()).contains("model timed out");
        verify(repository).save(investigation);
    }

    @Test
    void recordsAnErrorSignalAndContinuesWhenATelemetryQueryThrows() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new QueryMetrics("rate(http_errors_total[5m])", WINDOW),
                new Conclusion(finding()));
        when(metricsPort.queryRange(any(), any())).thenThrow(new RuntimeException("prometheus unreachable"));

        Investigation investigation = service.run(Investigation.open(request()));

        // The failed query becomes recoverable evidence, not a fatal error: the loop
        // carries on and the model concludes on the next step.
        assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
        assertThat(investigation.finding()).contains(finding());
        assertThat(investigation.signals()).hasSize(1);
        Signal error = investigation.signals().get(0);
        assertThat(error.type()).isEqualTo(SignalType.METRIC);
        assertThat(error.query()).isEqualTo("rate(http_errors_total[5m])");
        assertThat(error.content()).contains("prometheus unreachable");
        verify(repository).save(investigation);
    }

    @Test
    void recordsAnErrorSignalWhenALogQueryIsRejected() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new SearchLogs("{app=\"checkout\"} |= \"a\" or |= \"b\"", WINDOW),
                new Conclusion(finding()));
        when(logsPort.search(any(), any())).thenThrow(new RuntimeException("HTTP 400: parse error"));

        Investigation investigation = service.run(Investigation.open(request()));

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
        Signal error = investigation.signals().get(0);
        assertThat(error.type()).isEqualTo(SignalType.LOG);
        assertThat(error.query()).isEqualTo("{app=\"checkout\"} |= \"a\" or |= \"b\"");
        assertThat(error.content()).contains("HTTP 400: parse error");
    }

    @Test
    void framesAnUnreachableDatasourceAsInfrastructureNotEvidence() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new SearchLogs("{service=\"analysis-worker\"}", WINDOW),
                new Conclusion(finding()));
        when(logsPort.search(any(), any())).thenThrow(new TelemetryException(
                TelemetryException.Kind.DATASOURCE_UNREACHABLE,
                "GET http://loki:3100/loki/api/v1/query_range failed: ConnectException", null));

        Investigation investigation = service.run(Investigation.open(request()));

        Signal error = investigation.signals().get(0);
        assertThat(error.content())
                .contains("infrastructure failure")
                .doesNotContain("ConnectException")   // raw transport text withheld
                .doesNotContain("Adjust the query");   // no query-mutation nudge
    }

    @Test
    void tellsTheModelToCorrectARejectedQuery() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new SearchLogs("{app=\"checkout\"} |= \"a\" or |= \"b\"", WINDOW),
                new Conclusion(finding()));
        when(logsPort.search(any(), any())).thenThrow(new TelemetryException(
                TelemetryException.Kind.QUERY_REJECTED,
                "GET http://loki:3100/... returned HTTP 400: parse error at line 1", null));

        Investigation investigation = service.run(Investigation.open(request()));

        Signal error = investigation.signals().get(0);
        assertThat(error.content())
                .contains("parse error")
                .contains("correct it and try again");
    }

    @Test
    void dispatchesSchemaDiscoveryToTheLogsPortAndRecordsASchemaSignal() {
        // listLabelValues is not part of the up-front seeding, so this isolates the
        // reasoning-step dispatch from the seeded label/metric/tag signals.
        Signal values = Signal.of(SignalType.SCHEMA, "log label values: service_name",
                "analysis-worker, ingestion-service", WINDOW.from());
        when(reasoningPort.nextStep(any())).thenReturn(
                new ai.prism.domain.reasoning.ListLogLabelValues("service_name"),
                new Conclusion(finding()));
        when(logsPort.listLabelValues("service_name")).thenReturn(values);

        Investigation investigation = service.run(Investigation.open(request()));

        assertThat(investigation.signals()).containsExactly(values);
        verify(logsPort).listLabelValues("service_name");
    }

    @Test
    void seedsTheTelemetrySchemaBeforeTheFirstReasoningStep() {
        Signal logLabels = Signal.of(SignalType.SCHEMA, "log labels", "service_name, level", WINDOW.from());
        Signal metricNames = Signal.of(SignalType.SCHEMA, "metric names", "up, http_requests_total", WINDOW.from());
        Signal traceTags = Signal.of(SignalType.SCHEMA, "trace tags", "resource.service.name", WINDOW.from());
        when(logsPort.listLabelNames()).thenReturn(logLabels);
        when(metricsPort.listMetricNames()).thenReturn(metricNames);
        when(tracingPort.listTagNames()).thenReturn(traceTags);
        when(reasoningPort.nextStep(any())).thenReturn(new Conclusion(finding()));

        Investigation investigation = service.run(Investigation.open(request()));

        // The schema is recorded up front, ahead of any reasoning evidence.
        assertThat(investigation.signals()).containsExactly(logLabels, metricNames, traceTags);
        // ...and is already visible to the very first reasoning decision.
        ArgumentCaptor<InvestigationContext> context = ArgumentCaptor.forClass(InvestigationContext.class);
        verify(reasoningPort).nextStep(context.capture());
        assertThat(context.getValue().priorSignals()).containsExactly(logLabels, metricNames, traceTags);
    }

    @Test
    void seedingIsBestEffortAndSwallowsFailures() {
        when(logsPort.listLabelNames()).thenThrow(new RuntimeException("loki unreachable"));
        when(metricsPort.listMetricNames()).thenReturn(
                Signal.of(SignalType.SCHEMA, "metric names", "up", WINDOW.from()));
        when(tracingPort.listTagNames()).thenReturn(
                Signal.of(SignalType.SCHEMA, "trace tags", "resource.service.name", WINDOW.from()));
        when(reasoningPort.nextStep(any())).thenReturn(new Conclusion(finding()));

        Investigation investigation = service.run(Investigation.open(request()));

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
        // The failed log-labels seed is skipped silently (no error signal), the others recorded.
        assertThat(investigation.signals()).hasSize(2);
        assertThat(investigation.signals()).noneMatch(s -> s.content().contains("loki unreachable"));
    }

    @Test
    void recallsPastInvestigationsFromTheKnowledgeBase() {
        when(reasoningPort.nextStep(any())).thenReturn(
                new SearchPastInvestigations("why is checkout-service erroring?"),
                new Conclusion(finding()));
        when(knowledgeBase.findSimilar(any())).thenReturn(memorySignal());

        Investigation investigation = service.run(Investigation.open(request()));

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.CONCLUDED);
        assertThat(investigation.signals()).containsExactly(memorySignal());
        verify(knowledgeBase).findSimilar("why is checkout-service erroring?");
    }
}
