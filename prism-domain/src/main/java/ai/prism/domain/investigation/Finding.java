package ai.prism.domain.investigation;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The conclusion of an investigation: the model's interpretation of the
 * gathered signals, with supporting evidence and a recommended action.
 *
 * <p>{@code keySignalIndex} optionally points at the single gathered signal (by
 * its position in the investigation's signal list) that best evidences the root
 * cause — the "headline" the user should look at first. Empty when no signal
 * stands out.
 */
public record Finding(
        String rootCause,
        String evidence,
        String recommendedAction,
        Confidence confidence,
        OptionalInt keySignalIndex) {

    public Finding {
        Objects.requireNonNull(rootCause, "rootCause must not be null");
        if (rootCause.isBlank()) {
            throw new IllegalArgumentException("rootCause must not be blank");
        }
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(recommendedAction, "recommendedAction must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        Objects.requireNonNull(keySignalIndex, "keySignalIndex must not be null");
        if (keySignalIndex.isPresent() && keySignalIndex.getAsInt() < 0) {
            throw new IllegalArgumentException("keySignalIndex must not be negative");
        }
    }

    /** A finding with no nominated key signal. */
    public static Finding of(String rootCause, String evidence, String recommendedAction, Confidence confidence) {
        return new Finding(rootCause, evidence, recommendedAction, confidence, OptionalInt.empty());
    }
}
