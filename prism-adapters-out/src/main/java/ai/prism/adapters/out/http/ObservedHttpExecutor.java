package ai.prism.adapters.out.http;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments every outbound telemetry HTTP query at the single {@link HttpExecutor}
 * choke point: a {@code prism.telemetry.query} span and timer tagged by backend.
 * One decorator covers the Prometheus, Loki and Tempo adapters, and makes each
 * tool call a child span of the investigation trace.
 */
public class ObservedHttpExecutor implements HttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(ObservedHttpExecutor.class);

    private static final int PREVIEW_LIMIT = 1000;

    private final HttpExecutor delegate;
    private final ObservationRegistry registry;

    public ObservedHttpExecutor(HttpExecutor delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public String get(URI uri) {
        String backend = backendOf(uri);
        Observation observation = Observation.createNotStarted("prism.telemetry.query", registry)
                .lowCardinalityKeyValue("backend", backend)
                .highCardinalityKeyValue("uri", uri.toString());
        log.debug("Telemetry {} query: {}", backend, uri);
        try {
            String body = observation.observe(() -> delegate.get(uri));
            if (log.isTraceEnabled()) {
                log.trace("Telemetry {} result ({} chars): {}", backend, body.length(), body);
            } else if (log.isDebugEnabled()) {
                log.debug("Telemetry {} result ({} chars): {}", backend, body.length(), preview(body));
            }
            return body;
        } catch (RuntimeException failure) {
            log.warn("Telemetry {} query failed: {} -> {}", backend, uri, failure.getMessage());
            throw failure;
        }
    }

    private static String preview(String body) {
        return body.length() <= PREVIEW_LIMIT
                ? body
                : body.substring(0, PREVIEW_LIMIT) + "...(" + body.length() + " chars total)";
    }

    private static String backendOf(URI uri) {
        return switch (uri.getPort()) {
            case 9090 -> "prometheus";
            case 3100 -> "loki";
            case 3200 -> "tempo";
            default -> uri.getHost() == null ? "unknown" : uri.getHost();
        };
    }
}
