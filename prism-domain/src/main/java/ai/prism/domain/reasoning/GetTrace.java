package ai.prism.domain.reasoning;

import java.util.Objects;

/** Gather evidence from tracing: fetch a single trace by id. */
public record GetTrace(String traceId) implements ReasoningStep {

    public GetTrace {
        Objects.requireNonNull(traceId, "traceId must not be null");
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
    }
}
