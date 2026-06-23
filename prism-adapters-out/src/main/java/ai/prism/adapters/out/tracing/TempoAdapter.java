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
        return Signal.of(SignalType.TRACE, traceId, body, clock.instant());
    }

    @Override
    public Signal searchTraces(String traceQl, TimeWindow window) {
        URI uri = URI.create(baseUrl + "/api/search"
                + "?q=" + HttpUris.encode(traceQl)
                + "&start=" + window.from().getEpochSecond()
                + "&end=" + window.to().getEpochSecond()
                + "&limit=" + SEARCH_LIMIT);
        String body = http.get(uri);
        return Signal.over(SignalType.TRACE, traceQl, body, clock.instant(), window);
    }

    @Override
    public Signal listTagNames() {
        // v2: tags grouped by scope (resource / span / intrinsic), so the model can write
        // correctly-scoped TraceQL (e.g. resource.service.name, not bare service.name).
        String body = http.get(URI.create(baseUrl + "/api/v2/search/tags"));
        return Signal.of(SignalType.SCHEMA, "trace tags", body, clock.instant());
    }

    @Override
    public Signal listTagValues(String tag) {
        URI uri = URI.create(baseUrl + "/api/v2/search/tag/" + HttpUris.encode(tag) + "/values");
        return Signal.of(SignalType.SCHEMA, "trace tag values: " + tag, http.get(uri), clock.instant());
    }
}
