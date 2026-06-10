package ai.prism.application.reasoning;

import ai.prism.domain.investigation.TimeWindow;
import java.util.Objects;

/** Gather evidence from tracing: search for traces of a service in a window. */
public record SearchTraces(String service, TimeWindow window) implements ReasoningStep {

    public SearchTraces {
        Objects.requireNonNull(service, "service must not be null");
        if (service.isBlank()) {
            throw new IllegalArgumentException("service must not be blank");
        }
        Objects.requireNonNull(window, "window must not be null");
    }
}
