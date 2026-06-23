package ai.prism.domain.reasoning;

import java.util.Objects;

/** Discover the values of one trace tag — e.g. which services emit spans. */
public record ListTraceTagValues(String tag) implements ReasoningStep {

    public ListTraceTagValues {
        Objects.requireNonNull(tag, "tag must not be null");
        if (tag.isBlank()) {
            throw new IllegalArgumentException("tag must not be blank");
        }
    }
}
