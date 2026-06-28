package ai.prism.adapters.out.http;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments every outbound telemetry HTTP query at the single {@link HttpExecutor}
 * choke point: a {@code prism.telemetry.query} span (created via the OpenTelemetry API, so it
 * nests under the current {@code prism.investigation} span) tagged by backend. One decorator
 * covers the Prometheus, Loki and Tempo adapters, making each evidence-gathering call a child
 * span of the investigation trace.
 */
public class ObservedHttpExecutor implements HttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(ObservedHttpExecutor.class);

    private static final int PREVIEW_LIMIT = 1000;

    private final HttpExecutor delegate;
    private final Tracer tracer;

    public ObservedHttpExecutor(HttpExecutor delegate, OpenTelemetry openTelemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.tracer = openTelemetry.getTracer("ai.prism.telemetry");
    }

    @Override
    public String get(URI uri) {
        String backend = backendOf(uri);
        Span span = tracer.spanBuilder("prism.telemetry.query")
                .setAttribute("backend", backend)
                .setAttribute("uri", uri.toString())
                .startSpan();
        log.debug("Telemetry {} query: {}", backend, uri);
        try (Scope ignored = span.makeCurrent()) {
            String body = delegate.get(uri);
            if (log.isTraceEnabled()) {
                log.trace("Telemetry {} result ({} chars): {}", backend, body.length(), body);
            } else if (log.isDebugEnabled()) {
                log.debug("Telemetry {} result ({} chars): {}", backend, body.length(), preview(body));
            }
            return body;
        } catch (RuntimeException failure) {
            span.setStatus(StatusCode.ERROR, failure.getMessage() != null ? failure.getMessage() : failure.toString());
            log.warn("Telemetry {} query failed: {} -> {}", backend, uri, failure.getMessage());
            throw failure;
        } finally {
            span.end();
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
