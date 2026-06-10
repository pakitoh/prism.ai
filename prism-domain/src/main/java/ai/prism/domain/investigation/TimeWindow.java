package ai.prism.domain.investigation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A closed time interval over which telemetry is queried. {@code from} must
 * not be after {@code to}; a zero-length window is permitted.
 */
public record TimeWindow(Instant from, Instant to) {

    public TimeWindow {
        Objects.requireNonNull(from, "TimeWindow from must not be null");
        Objects.requireNonNull(to, "TimeWindow to must not be null");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "TimeWindow from (" + from + ") must not be after to (" + to + ")");
        }
    }

    public Duration duration() {
        return Duration.between(from, to);
    }
}
