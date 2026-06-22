package ai.prism.adapters.out.link;

import ai.prism.adapters.out.http.HttpUris;
import ai.prism.application.port.out.DashboardLinkPort;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DashboardLinkPort} that builds a Grafana Explore deep link for a signal —
 * the datasource, the query and the time range encoded into a {@code /explore} URL
 * (Grafana 10/11 {@code panes} schema). Pure string construction: no network call.
 *
 * <p>The signal already carries everything needed: its {@link SignalType} selects
 * the datasource, {@link Signal#query()} is the query, and {@link Signal#window()}
 * is the range (defaulting to the hour before {@code observedAt} for a trace fetched
 * by id, which has no window). {@code MEMORY} signals and unconfigured datasources
 * yield no link.
 */
public class GrafanaDashboardLinkAdapter implements DashboardLinkPort {

    private static final Logger log = LoggerFactory.getLogger(GrafanaDashboardLinkAdapter.class);

    private static final String PANE_ID = "prism";
    private static final Duration DEFAULT_LOOKBACK = Duration.ofHours(1);

    private final String baseUrl;
    private final GrafanaDatasources datasources;
    private final JsonMapper jsonMapper;

    public GrafanaDashboardLinkAdapter(String grafanaBaseUrl, GrafanaDatasources datasources, JsonMapper jsonMapper) {
        this.baseUrl = HttpUris.normalizeBaseUrl(Objects.requireNonNull(grafanaBaseUrl, "grafanaBaseUrl must not be null"));
        this.datasources = Objects.requireNonNull(datasources, "datasources must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    @Override
    public Optional<URI> dashboardLink(Signal signal) {
        Datasource datasource = datasourceFor(signal.type());
        if (datasource == null) {
            return Optional.empty();
        }
        try {
            String panes = jsonMapper.writeValueAsString(panes(signal, datasource));
            String url = baseUrl + "/explore?schemaVersion=1&panes=" + HttpUris.encode(panes) + "&orgId=1";
            return Optional.of(URI.create(url));
        } catch (RuntimeException failure) {
            // Best-effort enrichment: never fail a query because a link could not be built.
            log.warn("Could not build Grafana link for {} signal: {}", signal.type(), failure.toString());
            return Optional.empty();
        }
    }

    /** The Grafana Explore {@code panes} object: a single pane keyed by an arbitrary id. */
    private Map<String, Object> panes(Signal signal, Datasource datasource) {
        Map<String, Object> pane = new LinkedHashMap<>();
        pane.put("datasource", datasource.uid());
        pane.put("queries", List.of(query(signal, datasource)));
        pane.put("range", range(signal));
        return Map.of(PANE_ID, pane);
    }

    private Map<String, Object> query(Signal signal, Datasource datasource) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("refId", "A");
        query.put("datasource", Map.of("type", datasource.type(), "uid", datasource.uid()));
        if (signal.type() == SignalType.TRACE) {
            query.put("queryType", "traceql");
            query.put("query", signal.query());
        } else {
            query.put("expr", signal.query());
        }
        return query;
    }

    private static Map<String, String> range(Signal signal) {
        TimeWindow window = signal.window().orElseGet(() -> {
            Instant to = signal.observedAt();
            return new TimeWindow(to.minus(DEFAULT_LOOKBACK), to);
        });
        return Map.of(
                "from", Long.toString(window.from().toEpochMilli()),
                "to", Long.toString(window.to().toEpochMilli()));
    }

    private Datasource datasourceFor(SignalType type) {
        String uid = switch (type) {
            case METRIC -> datasources.prometheusUid();
            case LOG -> datasources.lokiUid();
            case TRACE -> datasources.tempoUid();
            case MEMORY -> null;
        };
        if (uid == null || uid.isBlank()) {
            return null;
        }
        return new Datasource(grafanaType(type), uid);
    }

    private static String grafanaType(SignalType type) {
        return switch (type) {
            case METRIC -> "prometheus";
            case LOG -> "loki";
            case TRACE -> "tempo";
            case MEMORY -> throw new IllegalStateException("MEMORY has no datasource");
        };
    }

    private record Datasource(String type, String uid) {
    }
}
