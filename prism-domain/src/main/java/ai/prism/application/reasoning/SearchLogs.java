package ai.prism.application.reasoning;

import ai.prism.domain.investigation.TimeWindow;
import java.util.Objects;

/** Gather evidence from logs: run a log query. */
public record SearchLogs(String logQl, TimeWindow window) implements ReasoningStep {

    public SearchLogs {
        Objects.requireNonNull(logQl, "logQl must not be null");
        if (logQl.isBlank()) {
            throw new IllegalArgumentException("logQl must not be blank");
        }
        Objects.requireNonNull(window, "window must not be null");
    }
}
