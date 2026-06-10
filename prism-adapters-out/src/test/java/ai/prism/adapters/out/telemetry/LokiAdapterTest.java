package ai.prism.adapters.out.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LokiAdapterTest {

    private static final Instant NOW = Instant.parse("2026-06-10T10:05:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-06-10T10:00:00Z"),
            Instant.parse("2026-06-10T10:30:00Z"));

    private final RecordingHttpExecutor http = new RecordingHttpExecutor();
    private final LokiAdapter adapter =
            new LokiAdapter(http, "http://loki:3100", Clock.fixed(NOW, ZoneOffset.UTC));

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
}
