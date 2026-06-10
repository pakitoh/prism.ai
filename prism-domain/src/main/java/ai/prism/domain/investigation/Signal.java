package ai.prism.domain.investigation;

import java.time.Instant;
import java.util.Objects;

/**
 * A single telemetry observation gathered during an investigation: the query
 * that produced it and the raw result returned by the source.
 */
public record Signal(
        SignalType type,
        String query,
        String content,
        Instant observedAt) {

    public Signal {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
