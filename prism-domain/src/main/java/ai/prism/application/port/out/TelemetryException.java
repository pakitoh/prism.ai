package ai.prism.application.port.out;

/**
 * Raised by a telemetry port ({@link MetricsPort}, {@link LogsPort}, {@link TracingPort})
 * when a query cannot be completed. The {@link Kind} tells the investigation loop whether
 * the query is the model's to fix or the datasource was simply unreachable — an
 * infrastructure problem the model must not mistake for evidence.
 */
public class TelemetryException extends RuntimeException {

    public enum Kind {
        /** Connection refused, DNS failure, or timeout — the datasource could not be reached. */
        DATASOURCE_UNREACHABLE,
        /** The datasource rejected the query (HTTP 4xx) — the query is likely malformed. */
        QUERY_REJECTED,
        /** The datasource was reached but failed to serve the request (HTTP 5xx). */
        DATASOURCE_ERROR
    }

    private final Kind kind;

    public TelemetryException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
