package ai.prism.adapters.out.telemetry;

import ai.prism.application.port.out.LogsPort;
import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link LogsPort} backed by the Loki HTTP API (`/loki/api/v1/query_range`).
 * Loki expects nanosecond Unix timestamps. Returns the raw JSON response as a
 * {@link Signal}.
 */
public class LokiAdapter implements LogsPort {

    private static final int LIMIT = 100;

    private final HttpExecutor http;
    private final String baseUrl;
    private final Clock clock;

    public LokiAdapter(HttpExecutor http, String baseUrl, Clock clock) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.baseUrl = TelemetryUris.normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Signal search(String logQl, TimeWindow window) {
        URI uri = URI.create(baseUrl + "/loki/api/v1/query_range"
                + "?query=" + TelemetryUris.encode(logQl)
                + "&start=" + epochNanos(window.from())
                + "&end=" + epochNanos(window.to())
                + "&limit=" + LIMIT
                + "&direction=backward");
        String body = http.get(uri);
        return new Signal(SignalType.LOG, logQl, body, clock.instant());
    }

    private static long epochNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
