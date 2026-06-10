package ai.prism.domain.investigation;

/**
 * How confident the model is in a {@link Finding}. Used later to surface
 * low-confidence conclusions for human review.
 */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
}
