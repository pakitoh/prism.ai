package ai.prism.adapters.out.metrics;

import ai.prism.application.port.out.MetricsPort;
import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.HttpUris;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/**
 * {@link MetricsPort} backed by the Prometheus HTTP API (`/api/v1/query_range`, plus
 * metric-name discovery via `/api/v1/label/__name__/values`). Returns the raw JSON
 * response as a {@link Signal} for the model to interpret.
 */
public class PrometheusAdapter implements MetricsPort {

    private static final int TARGET_POINTS = 250;

    private final HttpExecutor http;
    private final String baseUrl;
    private final Clock clock;

    public PrometheusAdapter(HttpExecutor http, String baseUrl, Clock clock) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.baseUrl = HttpUris.normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Signal queryRange(String promQl, TimeWindow window) {
        URI uri = URI.create(baseUrl + "/api/v1/query_range"
                + "?query=" + HttpUris.encode(promQl)
                + "&start=" + window.from().getEpochSecond()
                + "&end=" + window.to().getEpochSecond()
                + "&step=" + step(window));
        String body = http.get(uri);
        return Signal.over(SignalType.METRIC, promQl, body, clock.instant(), window);
    }

    @Override
    public Signal listMetricNames() {
        String body = http.get(URI.create(baseUrl + "/api/v1/label/__name__/values"));
        return Signal.of(SignalType.SCHEMA, "metric names", body, clock.instant());
    }

    /** A step that keeps the result around {@value #TARGET_POINTS} points, in seconds. */
    private static String step(TimeWindow window) {
        long seconds = Math.max(1, window.duration().toSeconds() / TARGET_POINTS);
        return seconds + "s";
    }
}
