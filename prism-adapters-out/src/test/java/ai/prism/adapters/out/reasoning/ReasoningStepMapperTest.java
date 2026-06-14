package ai.prism.adapters.out.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.GetTrace;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.SearchLogs;
import ai.prism.domain.reasoning.SearchPastInvestigations;
import ai.prism.domain.reasoning.SearchTraces;
import ai.prism.domain.investigation.Confidence;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReasoningStepMapperTest {

    private final ReasoningStepMapper mapper = new ReasoningStepMapper();

    private static final String FROM = "2026-06-10T10:00:00Z";
    private static final String TO = "2026-06-10T10:30:00Z";

    @Test
    void mapsQueryMetricsWithItsWindow() {
        var step = mapper.map(ReasoningToolNames.QUERY_METRICS,
                Map.of("promQl", "rate(http_errors_total[5m])", "from", FROM, "to", TO));

        assertThat(step).isInstanceOfSatisfying(QueryMetrics.class, q -> {
            assertThat(q.promQl()).isEqualTo("rate(http_errors_total[5m])");
            assertThat(q.window().from()).isEqualTo(Instant.parse(FROM));
            assertThat(q.window().to()).isEqualTo(Instant.parse(TO));
        });
    }

    @Test
    void mapsSearchLogs() {
        var step = mapper.map(ReasoningToolNames.SEARCH_LOGS,
                Map.of("logQl", "{app=\"checkout\"} |= \"ERROR\"", "from", FROM, "to", TO));

        assertThat(step).isInstanceOfSatisfying(SearchLogs.class,
                l -> assertThat(l.logQl()).isEqualTo("{app=\"checkout\"} |= \"ERROR\""));
    }

    @Test
    void mapsGetTrace() {
        var step = mapper.map(ReasoningToolNames.GET_TRACE, Map.of("traceId", "abc123"));

        assertThat(step).isInstanceOfSatisfying(GetTrace.class,
                t -> assertThat(t.traceId()).isEqualTo("abc123"));
    }

    @Test
    void mapsSearchTraces() {
        var step = mapper.map(ReasoningToolNames.SEARCH_TRACES,
                Map.of("service", "checkout-service", "from", FROM, "to", TO));

        assertThat(step).isInstanceOfSatisfying(SearchTraces.class,
                s -> assertThat(s.service()).isEqualTo("checkout-service"));
    }

    @Test
    void mapsSearchPastInvestigations() {
        var step = mapper.map(ReasoningToolNames.SEARCH_PAST_INVESTIGATIONS, Map.of("query", "checkout errors"));

        assertThat(step).isInstanceOfSatisfying(SearchPastInvestigations.class,
                s -> assertThat(s.query()).isEqualTo("checkout errors"));
    }

    @Test
    void mapsConcludeIntoAFinding() {
        var step = mapper.map(ReasoningToolNames.CONCLUDE, Map.of(
                "rootCause", "DB pool exhausted",
                "evidence", "errors track pool saturation",
                "recommendedAction", "raise the pool size",
                "confidence", "high"));

        assertThat(step).isInstanceOfSatisfying(Conclusion.class, c -> {
            assertThat(c.finding().rootCause()).isEqualTo("DB pool exhausted");
            assertThat(c.finding().confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void defaultsUnrecognisedConfidenceToMedium() {
        var step = mapper.map(ReasoningToolNames.CONCLUDE, Map.of(
                "rootCause", "rc", "evidence", "ev", "recommendedAction", "act", "confidence", "pretty sure"));

        assertThat(((Conclusion) step).finding().confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void rejectsAnUnknownTool() {
        assertThatThrownBy(() -> mapper.map("delete_everything", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delete_everything");
    }

    @Test
    void rejectsAMissingRequiredArgument() {
        Map<String, Object> noQuery = new HashMap<>();
        noQuery.put("from", FROM);
        noQuery.put("to", TO);
        assertThatThrownBy(() -> mapper.map(ReasoningToolNames.QUERY_METRICS, noQuery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promQl");
    }
}
