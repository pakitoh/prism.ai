package ai.prism.domain.reasoning;

import java.util.Objects;

/** Discover the values of one log label — e.g. which services are present. */
public record ListLogLabelValues(String label) implements ReasoningStep {

    public ListLogLabelValues {
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}
