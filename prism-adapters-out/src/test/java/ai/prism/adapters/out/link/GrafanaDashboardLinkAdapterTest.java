package ai.prism.adapters.out.link;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GrafanaDashboardLinkAdapterTest {

    private static final Instant FROM = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-10T10:30:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(FROM, TO);

    private final GrafanaDashboardLinkAdapter adapter = new GrafanaDashboardLinkAdapter(
            "http://grafana.local/",  // trailing slash is normalized away
            new GrafanaDatasources("prom-uid", "loki-uid", "tempo-uid"),
            JsonMapper.builder().build());

    @Test
    void buildsAnExploreLinkForAMetricSignalWithItsWindow() {
        Signal signal = Signal.over(SignalType.METRIC, "rate(http_errors_total[5m])", "0.42", TO, WINDOW);

        URI uri = adapter.dashboardLink(signal).orElseThrow();

        assertThat(uri.toString()).startsWith("http://grafana.local/explore?schemaVersion=1&panes=");
        String panes = decodedPanes(uri);
        assertThat(panes).contains("\"type\":\"prometheus\"").contains("\"uid\":\"prom-uid\"");
        assertThat(panes).contains("\"expr\":\"rate(http_errors_total[5m])\"");
        // range carries the window as epoch-millis.
        assertThat(panes).contains("\"from\":\"" + FROM.toEpochMilli() + "\"")
                .contains("\"to\":\"" + TO.toEpochMilli() + "\"");
    }

    @Test
    void buildsALokiLinkForALogSignal() {
        Signal signal = Signal.over(SignalType.LOG, "{app=\"checkout\"} |= \"ERROR\"", "...", TO, WINDOW);

        String panes = decodedPanes(adapter.dashboardLink(signal).orElseThrow());

        assertThat(panes).contains("\"type\":\"loki\"").contains("\"uid\":\"loki-uid\"");
        assertThat(panes).contains("\"expr\":\"{app=\\\"checkout\\\"} |= \\\"ERROR\\\"\"");
    }

    @Test
    void buildsATraceqlLinkForATraceSignalDefaultingTheRange() {
        // get_trace produces a TRACE signal with no window.
        Signal signal = Signal.of(SignalType.TRACE, "abc123", "...", TO);

        String panes = decodedPanes(adapter.dashboardLink(signal).orElseThrow());

        assertThat(panes).contains("\"type\":\"tempo\"").contains("\"uid\":\"tempo-uid\"");
        assertThat(panes).contains("\"queryType\":\"traceql\"").contains("\"query\":\"abc123\"");
        // window absent → defaults to the hour before observedAt.
        assertThat(panes).contains("\"from\":\"" + TO.minusSeconds(3600).toEpochMilli() + "\"")
                .contains("\"to\":\"" + TO.toEpochMilli() + "\"");
    }

    @Test
    void returnsNoLinkForAMemorySignal() {
        Signal signal = Signal.of(SignalType.MEMORY, "why errors?", "recall", TO);

        assertThat(adapter.dashboardLink(signal)).isEmpty();
    }

    @Test
    void returnsNoLinkForASchemaSignal() {
        Signal signal = Signal.of(SignalType.SCHEMA, "log labels", "[\"service_name\"]", TO);

        assertThat(adapter.dashboardLink(signal)).isEmpty();
    }

    @Test
    void returnsNoLinkWhenTheDatasourceUidIsBlank() {
        GrafanaDashboardLinkAdapter noTempo = new GrafanaDashboardLinkAdapter(
                "http://grafana.local",
                new GrafanaDatasources("prom-uid", "loki-uid", "  "),
                JsonMapper.builder().build());

        assertThat(noTempo.dashboardLink(Signal.of(SignalType.TRACE, "abc123", "...", TO))).isEqualTo(Optional.empty());
    }

    private static String decodedPanes(URI uri) {
        String query = uri.getRawQuery();
        int start = query.indexOf("panes=") + "panes=".length();
        int end = query.indexOf('&', start);
        String raw = end < 0 ? query.substring(start) : query.substring(start, end);
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
