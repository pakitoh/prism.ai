package ai.prism.application.reasoning;

import java.util.Objects;

/** Recall evidence from memory: search past investigations similar to a query. */
public record SearchPastInvestigations(String query) implements ReasoningStep {

    public SearchPastInvestigations {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
    }
}
