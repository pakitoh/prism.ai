package ai.prism.adapters.out.tracing;
import ai.prism.adapters.out.http.RecordingHttpExecutor;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TempoAdapterTest {

    private static final Instant NOW = Instant.parse("2026-06-10T10:05:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-06-10T10:00:00Z"),
            Instant.parse("2026-06-10T10:30:00Z"));

    private final RecordingHttpExecutor http = new RecordingHttpExecutor();
    private final TempoAdapter adapter =
            new TempoAdapter(http, "http://tempo:3200", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void fetchesASingleTraceById() {
        Signal signal = adapter.getTrace("abc123def456");

        assertThat(http.lastUri.toString()).isEqualTo("http://tempo:3200/api/traces/abc123def456");
        assertThat(signal.type()).isEqualTo(SignalType.TRACE);
        assertThat(signal.query()).isEqualTo("abc123def456");
        assertThat(signal.observedAt()).isEqualTo(NOW);
    }

    @Test
    void searchesTracesByServiceOverTheWindow() {
        Signal signal = adapter.searchTraces("checkout-service", WINDOW);

        assertThat(http.lastUri.toString())
                .startsWith("http://tempo:3200/api/search?tags=")
                .contains("start=" + WINDOW.from().getEpochSecond())
                .contains("end=" + WINDOW.to().getEpochSecond())
                .contains("limit=20");
        // the service tag must be percent-encoded (service.name=checkout-service)
        assertThat(http.lastUri.toString()).contains("service.name%3Dcheckout-service");
        assertThat(signal.type()).isEqualTo(SignalType.TRACE);
        assertThat(signal.query()).isEqualTo("service.name=checkout-service");
    }
}
