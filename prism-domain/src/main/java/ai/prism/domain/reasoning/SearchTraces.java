package ai.prism.domain.reasoning;

import ai.prism.domain.investigation.TimeWindow;
import java.util.Objects;

/** Gather evidence from tracing: search for traces matching a TraceQL query in a window. */
public record SearchTraces(String traceQl, TimeWindow window) implements ReasoningStep {

    public SearchTraces {
        Objects.requireNonNull(traceQl, "traceQl must not be null");
        if (traceQl.isBlank()) {
            throw new IllegalArgumentException("traceQl must not be blank");
        }
        Objects.requireNonNull(window, "window must not be null");
    }
}
