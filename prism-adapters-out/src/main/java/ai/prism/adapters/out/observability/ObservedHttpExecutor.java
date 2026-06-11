package ai.prism.adapters.out.observability;

import ai.prism.adapters.out.http.HttpExecutor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.util.Objects;

/**
 * Instruments every outbound telemetry HTTP query at the single {@link HttpExecutor}
 * choke point: a {@code prism.telemetry.query} span and timer tagged by backend.
 * One decorator covers the Prometheus, Loki and Tempo adapters, and makes each
 * tool call a child span of the investigation trace.
 */
public class ObservedHttpExecutor implements HttpExecutor {

    private final HttpExecutor delegate;
    private final ObservationRegistry registry;

    public ObservedHttpExecutor(HttpExecutor delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public String get(URI uri) {
        Observation observation = Observation.createNotStarted("prism.telemetry.query", registry)
                .lowCardinalityKeyValue("backend", backendOf(uri))
                .highCardinalityKeyValue("uri", uri.toString());
        return observation.observe(() -> delegate.get(uri));
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
