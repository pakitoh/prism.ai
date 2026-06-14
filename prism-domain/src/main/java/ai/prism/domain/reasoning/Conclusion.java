package ai.prism.domain.reasoning;

import ai.prism.domain.investigation.Finding;
import java.util.Objects;

/** End the investigation with a {@link Finding}. */
public record Conclusion(Finding finding) implements ReasoningStep {

    public Conclusion {
        Objects.requireNonNull(finding, "finding must not be null");
    }
}
