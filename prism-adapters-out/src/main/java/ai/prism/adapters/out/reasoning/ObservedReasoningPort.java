package ai.prism.adapters.out.reasoning;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.GetTrace;
import ai.prism.domain.reasoning.ListLogLabelValues;
import ai.prism.domain.reasoning.ListTraceTagValues;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.reasoning.SearchLogs;
import ai.prism.domain.reasoning.SearchPastInvestigations;
import ai.prism.domain.reasoning.SearchTraces;
import ai.prism.domain.investigation.TimeWindow;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments each reasoning step: a {@code prism.reasoning.step} span (created via the
 * OpenTelemetry API, so it nests under the current {@code prism.investigation} span) tagged
 * by the kind of step the model chose. Wrapping the composed {@link ReasoningPort} (after
 * fallback) gives one span per loop iteration — i.e. one per model decision/LLM call.
 */
public class ObservedReasoningPort implements ReasoningPort {

    private static final Logger log = LoggerFactory.getLogger(ObservedReasoningPort.class);

    private static final AttributeKey<String> STEP_KIND = AttributeKey.stringKey("step.kind");

    private final ReasoningPort delegate;
    private final Tracer tracer;
    private final LongHistogram duration;

    public ObservedReasoningPort(ReasoningPort delegate, OpenTelemetry openTelemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.tracer = openTelemetry.getTracer("ai.prism.reasoning");
        this.duration = openTelemetry.getMeter("ai.prism.reasoning")
                .histogramBuilder("prism.reasoning.step.duration").ofLongs().setUnit("ms").build();
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        Span span = tracer.spanBuilder("prism.reasoning.step").startSpan();
        long start = System.nanoTime();
        String kind = "error";
        try (Scope ignored = span.makeCurrent()) {
            ReasoningStep step = delegate.nextStep(context);
            kind = kindOf(step);
            span.setAttribute("step.kind", kind);
            log.debug("Next tool: {}", describe(step));
            return step;
        } catch (RuntimeException failure) {
            span.setStatus(StatusCode.ERROR, failure.getMessage() != null ? failure.getMessage() : failure.toString());
            throw failure;
        } finally {
            duration.record((System.nanoTime() - start) / 1_000_000L, Attributes.of(STEP_KIND, kind));
            span.end();
        }
    }

    private static String kindOf(ReasoningStep step) {
        return switch (step) {
            case QueryMetrics ignored -> "query_metrics";
            case SearchLogs ignored -> "search_logs";
            case GetTrace ignored -> "get_trace";
            case SearchTraces ignored -> "search_traces";
            case ListLogLabelValues ignored -> "list_log_label_values";
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
            case ListLogLabelValues v -> "list_log_label_values label=" + v.label();
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
