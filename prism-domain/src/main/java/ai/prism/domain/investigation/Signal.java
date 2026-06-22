package ai.prism.domain.investigation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A single telemetry observation gathered during an investigation: the query
 * that produced it, the raw result returned by the source, and the time window
 * it was queried over (when the observation has one — a trace fetched by id or a
 * memory recall does not).
 */
public record Signal(
        SignalType type,
        String query,
        String content,
        Instant observedAt,
        Optional<TimeWindow> window) {

    public Signal {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(window, "window must not be null");
    }

    /** A signal gathered over a known time window. */
    public static Signal over(SignalType type, String query, String content, Instant observedAt, TimeWindow window) {
        return new Signal(type, query, content, observedAt, Optional.of(window));
    }

    /** A signal with no associated time window (e.g. a trace fetched by id or a memory recall). */
    public static Signal of(SignalType type, String query, String content, Instant observedAt) {
        return new Signal(type, query, content, observedAt, Optional.empty());
    }
}
