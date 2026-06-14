package ai.prism.domain.reasoning;

import ai.prism.domain.investigation.TimeWindow;
import java.util.Objects;

/** Gather evidence from metrics: run a range query. */
public record QueryMetrics(String promQl, TimeWindow window) implements ReasoningStep {

    public QueryMetrics {
        Objects.requireNonNull(promQl, "promQl must not be null");
        if (promQl.isBlank()) {
            throw new IllegalArgumentException("promQl must not be blank");
        }
        Objects.requireNonNull(window, "window must not be null");
    }
}
