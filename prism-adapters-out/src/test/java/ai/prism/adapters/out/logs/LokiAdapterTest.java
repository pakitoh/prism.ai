package ai.prism.adapters.out.logs;
import ai.prism.adapters.out.http.RecordingHttpExecutor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LokiAdapterTest {

    private static final Instant NOW = Instant.parse("2026-06-10T10:05:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-06-10T10:00:00Z"),
            Instant.parse("2026-06-10T10:30:00Z"));

    private final RecordingHttpExecutor http = new RecordingHttpExecutor();
    private final LokiAdapter adapter =
            new LokiAdapter(http, "http://loki:3100", Clock.fixed(NOW, ZoneOffset.UTC), JsonMapper.builder().build());

    @Test
    void queriesTheRangeEndpointWithNanosecondTimestamps() {
        Signal signal = adapter.search("{app=\"checkout\"} |= \"ERROR\"", WINDOW);

        long expectedStartNanos = WINDOW.from().getEpochSecond() * 1_000_000_000L;
        assertThat(http.lastUri.toString())
                .startsWith("http://loki:3100/loki/api/v1/query_range?query=")
                .contains("start=" + expectedStartNanos)
                .contains("limit=100");
        assertThat(signal.type()).isEqualTo(SignalType.LOG);
        assertThat(signal.query()).isEqualTo("{app=\"checkout\"} |= \"ERROR\"");
        assertThat(signal.observedAt()).isEqualTo(NOW);
    }

    @Test
    void extractsLogLinesAndDropsTheStatsBlob() {
        // 1781432400000000000 ns = 2026-06-14T10:20:00Z
        http.response = """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"service_name":"analysis-worker","level":"error"},
                   "values":[["1781432400000000000","pool exhausted"],
                             ["1781432401000000000","retry failed"]]}],
                  "stats":{"summary":{"totalBytesProcessed":999,"bytesProcessedPerSecond":42}}}}""";

        Signal signal = adapter.search("{service_name=\"analysis-worker\"}", WINDOW);

        assertThat(signal.content())
                .contains("1 stream(s), 2 line(s):")
                .contains("{service_name=\"analysis-worker\", level=\"error\"}")
                .contains("2026-06-14T10:20:00Z pool exhausted")
                .contains("2026-06-14T10:20:01Z retry failed")
                .doesNotContain("stats")
                .doesNotContain("totalBytesProcessed");
    }

    @Test
    void reportsWhenNoLogLinesMatch() {
        http.response = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[],"
                + "\"stats\":{\"summary\":{\"totalBytesProcessed\":0}}}}";

        Signal signal = adapter.search("{service=\"analysis-worker\"}", WINDOW);

        assertThat(signal.content()).startsWith("No log lines matched");
        assertThat(signal.content()).doesNotContain("stats");
    }

    @Test
    void fallsBackToTheRawBodyWhenUnparseable() {
        http.response = "not json at all";

        Signal signal = adapter.search("{service_name=\"x\"}", WINDOW);

        assertThat(signal.content()).isEqualTo("not json at all");
    }

    @Test
    void listsLabelNames() {
        Signal signal = adapter.listLabelNames();

        assertThat(http.lastUri.toString()).isEqualTo("http://loki:3100/loki/api/v1/labels");
        assertThat(signal.type()).isEqualTo(SignalType.SCHEMA);
        assertThat(signal.query()).isEqualTo("log labels");
    }

    @Test
    void listsLabelValues() {
        Signal signal = adapter.listLabelValues("service_name");

        assertThat(http.lastUri.toString())
                .isEqualTo("http://loki:3100/loki/api/v1/label/service_name/values");
        assertThat(signal.type()).isEqualTo(SignalType.SCHEMA);
        assertThat(signal.query()).isEqualTo("log label values: service_name");
    }
}
