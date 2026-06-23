package ai.prism.adapters.out.reasoning;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.GetTrace;
import ai.prism.domain.reasoning.ListLogLabels;
import ai.prism.domain.reasoning.ListLogLabelValues;
import ai.prism.domain.reasoning.ListMetricNames;
import ai.prism.domain.reasoning.ListTraceTags;
import ai.prism.domain.reasoning.ListTraceTagValues;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.reasoning.SearchLogs;
import ai.prism.domain.reasoning.SearchPastInvestigations;
import ai.prism.domain.reasoning.SearchTraces;
import ai.prism.domain.investigation.TimeWindow;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments each reasoning step: a {@code prism.reasoning.step} span and timer
 * tagged by the kind of step the model chose. Wrapping the composed
 * {@link ReasoningPort} (after fallback) gives one observation per loop iteration.
 */
public class ObservedReasoningPort implements ReasoningPort {

    private static final Logger log = LoggerFactory.getLogger(ObservedReasoningPort.class);

    private final ReasoningPort delegate;
    private final ObservationRegistry registry;

    public ObservedReasoningPort(ReasoningPort delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        Observation observation = Observation.createNotStarted("prism.reasoning.step", registry);
        return observation.observe(() -> {
            ReasoningStep step = delegate.nextStep(context);
            observation.lowCardinalityKeyValue("step.kind", kindOf(step));
            log.debug("Next tool: {}", describe(step));
            return step;
        });
    }

    private static String kindOf(ReasoningStep step) {
        return switch (step) {
            case QueryMetrics ignored -> "query_metrics";
            case SearchLogs ignored -> "search_logs";
            case GetTrace ignored -> "get_trace";
            case SearchTraces ignored -> "search_traces";
            case ListLogLabels ignored -> "list_log_labels";
            case ListLogLabelValues ignored -> "list_log_label_values";
            case ListMetricNames ignored -> "list_metric_names";
            case ListTraceTags ignored -> "list_trace_tags";
            case ListTraceTagValues ignored -> "list_trace_tag_values";
            case SearchPastInvestigations ignored -> "search_past_investigations";
            case Conclusion ignored -> "conclusion";
        };
    }

    /** A one-line summary of the step the loop is about to execute, with its key arguments. */
    private static String describe(ReasoningStep step) {
        return switch (step) {
            case QueryMetrics m -> "query_metrics promQl=\"" + m.promQl() + "\" window=" + window(m.window());
            case SearchLogs l -> "search_logs logQl=\"" + l.logQl() + "\" window=" + window(l.window());
            case GetTrace t -> "get_trace traceId=" + t.traceId();
            case SearchTraces s -> "search_traces traceQl=\"" + s.traceQl() + "\" window=" + window(s.window());
            case ListLogLabels ignored -> "list_log_labels";
            case ListLogLabelValues v -> "list_log_label_values label=" + v.label();
            case ListMetricNames ignored -> "list_metric_names";
            case ListTraceTags ignored -> "list_trace_tags";
            case ListTraceTagValues v -> "list_trace_tag_values tag=" + v.tag();
            case SearchPastInvestigations p -> "search_past_investigations query=\"" + p.query() + "\"";
            case Conclusion c -> "conclude rootCause=\"" + c.finding().rootCause()
                    + "\" confidence=" + c.finding().confidence();
        };
    }

    private static String window(TimeWindow window) {
        return window.from() + ".." + window.to();
    }
}
