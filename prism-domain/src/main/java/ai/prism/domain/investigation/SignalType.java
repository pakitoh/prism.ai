package ai.prism.domain.investigation;

/**
 * The kind of telemetry a {@link Signal} was gathered from.
 */
public enum SignalType {
    METRIC,
    LOG,
    TRACE,
    MEMORY
}
