package ai.prism.domain.investigation;

import java.util.Objects;

/**
 * The conclusion of an investigation: the model's interpretation of the
 * gathered signals, with supporting evidence and a recommended action.
 */
public record Finding(
        String rootCause,
        String evidence,
        String recommendedAction,
        Confidence confidence) {

    public Finding {
        Objects.requireNonNull(rootCause, "rootCause must not be null");
        if (rootCause.isBlank()) {
            throw new IllegalArgumentException("rootCause must not be blank");
        }
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(recommendedAction, "recommendedAction must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
    }
}
