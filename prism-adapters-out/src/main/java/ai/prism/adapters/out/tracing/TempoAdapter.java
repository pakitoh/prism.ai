package ai.prism.adapters.out.tracing;

import ai.prism.application.port.out.TracingPort;
import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.HttpUris;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/**
 * {@link TracingPort} backed by the Tempo HTTP API: `/api/traces/{id}` for a
 * single trace and `/api/search` for finding traces of a service. Returns the
 * raw JSON response as a {@link Signal}.
 */
public class TempoAdapter implements TracingPort {

    private static final int SEARCH_LIMIT = 20;

    private final HttpExecutor http;
    private final String baseUrl;
    private final Clock clock;

    public TempoAdapter(HttpExecutor http, String baseUrl, Clock clock) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.baseUrl = HttpUris.normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Signal getTrace(String traceId) {
        URI uri = URI.create(baseUrl + "/api/traces/" + HttpUris.encode(traceId));
        String body = http.get(uri);
        return new Signal(SignalType.TRACE, traceId, body, clock.instant());
    }

    @Override
    public Signal searchTraces(String service, TimeWindow window) {
        String tag = "service.name=" + service;
        URI uri = URI.create(baseUrl + "/api/search"
                + "?tags=" + HttpUris.encode(tag)
                + "&start=" + window.from().getEpochSecond()
                + "&end=" + window.to().getEpochSecond()
                + "&limit=" + SEARCH_LIMIT);
        String body = http.get(uri);
        return new Signal(SignalType.TRACE, tag, body, clock.instant());
    }
}
