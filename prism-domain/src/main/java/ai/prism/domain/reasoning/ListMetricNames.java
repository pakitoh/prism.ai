package ai.prism.domain.reasoning;

/** Discover which metric names exist in Prometheus, before assuming one. */
public record ListMetricNames() implements ReasoningStep {
}
