package ai.prism.domain.investigation;

/**
 * The kind of telemetry a {@link Signal} was gathered from.
 */
public enum SignalType {
    METRIC,
    LOG,
    TRACE,
    MEMORY,
    /** A schema-discovery result: available label/metric/tag names or values, not a telemetry observation. */
    SCHEMA
}
