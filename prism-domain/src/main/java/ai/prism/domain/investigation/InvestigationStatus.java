package ai.prism.domain.investigation;

/**
 * Lifecycle states of an {@link Investigation}.
 *
 * <pre>
 *   PENDING --start()--> IN_PROGRESS --conclude()--> CONCLUDED
 *      |                      |
 *      +------- fail() -------+--> FAILED
 * </pre>
 */
public enum InvestigationStatus {
    PENDING,
    IN_PROGRESS,
    CONCLUDED,
    FAILED
}
