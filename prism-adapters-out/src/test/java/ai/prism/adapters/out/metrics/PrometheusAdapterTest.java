package ai.prism.adapters.out.metrics;
import ai.prism.adapters.out.http.RecordingHttpExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.prism.application.port.out.TelemetryException;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PrometheusAdapterTest {

    private static final Instant NOW = Instant.parse("2026-06-10T10:05:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-06-10T10:00:00Z"),
            Instant.parse("2026-06-10T10:30:00Z"));

    private final RecordingHttpExecutor http = new RecordingHttpExecutor();
    private final PrometheusAdapter adapter =
            new PrometheusAdapter(http, "http://prometheus:9090/", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void queriesTheRangeEndpointAndReturnsTheBodyAsAMetricSignal() {
        http.response = "{\"data\":{\"result\":[]}}";

        Signal signal = adapter.queryRange("rate(http_errors_total[5m])", WINDOW);

        assertThat(http.lastUri.toString())
                .startsWith("http://prometheus:9090/api/v1/query_range?query=")
                .contains("start=" + WINDOW.from().getEpochSecond())
                .contains("end=" + WINDOW.to().getEpochSecond())
                .contains("step=");
        assertThat(signal.type()).isEqualTo(SignalType.METRIC);
        assertThat(signal.query()).isEqualTo("rate(http_errors_total[5m])");
        assertThat(signal.content()).isEqualTo("{\"data\":{\"result\":[]}}");
        assertThat(signal.observedAt()).isEqualTo(NOW);
    }

    @Test
    void urlEncodesThePromQlQuery() {
        adapter.queryRange("rate(http_errors_total[5m])", WINDOW);
        // parentheses and brackets must be percent-encoded, not raw, in the URL
        assertThat(http.lastUri.toString()).doesNotContain("[5m]").contains("%5B5m%5D");
    }

    @Test
    void propagatesTransportFailures() {
        PrometheusAdapter failing = new PrometheusAdapter(
                RecordingHttpExecutor.failing("connection refused"),
                "http://prometheus:9090", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> failing.queryRange("up", WINDOW))
                .isInstanceOf(TelemetryException.class)
                .hasMessageContaining("connection refused");
    }
}
