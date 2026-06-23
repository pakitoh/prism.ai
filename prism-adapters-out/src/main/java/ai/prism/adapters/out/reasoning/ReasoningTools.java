package ai.prism.adapters.out.reasoning;

import java.util.List;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Declares the tools the model may call, with their input schemas derived from
 * the record types below.
 *
 * <p>The {@code Function} bodies exist only to satisfy the builder API — they
 * are never called because the application loop dispatches the model's tool
 * choice, not the framework.
 */
final class ReasoningTools {

    record QueryMetricsInput(String promQl, String from, String to) {
    }

    record SearchLogsInput(String logQl, String from, String to) {
    }

    record GetTraceInput(String traceId) {
    }

    record SearchTracesInput(String traceQl, String from, String to) {
    }

    record ListLogLabelValuesInput(String label) {
    }

    record ListTraceTagValuesInput(String tag) {
    }

    /** Input type for tools that take no arguments (yields an empty object schema). */
    record NoArgs() {
    }

    record SearchPastInvestigationsInput(String query) {
    }

    record ConcludeInput(String rootCause, String evidence, String recommendedAction, String confidence,
                         Integer keySignalIndex) {
    }

    private ReasoningTools() {
    }

    static List<ToolCallback> all() {
        return List.of(
                tool(ReasoningToolNames.QUERY_METRICS,
                        "Run a PromQL range query against Prometheus over the given time window "
                                + "(from/to are ISO-8601 UTC instants). Use to inspect metrics such as "
                                + "error rates, latency, saturation.",
                        QueryMetricsInput.class),
                tool(ReasoningToolNames.SEARCH_LOGS,
                        "Run a LogQL query against Loki over the given time window. Use to read logs "
                                + "for a service once a metric points at a suspect. A LogQL query is a "
                                + "stream selector followed by optional filters, e.g. "
                                + "{service=\"checkout-service\"} |~ \"error|exception\" (the |~ regex "
                                + "filter matches either word). LogQL has no 'or' keyword between line "
                                + "filters: {service=\"checkout-service\"} |= \"error\" or |= \"exception\" "
                                + "is INVALID — combine alternatives in a single |~ regex instead.",
                        SearchLogsInput.class),
                tool(ReasoningToolNames.GET_TRACE,
                        "Fetch a single distributed trace from Tempo by its trace id. Use to inspect "
                                + "the span tree of a specific failing request.",
                        GetTraceInput.class),
                tool(ReasoningToolNames.SEARCH_TRACES,
                        "Search Tempo for traces matching a TraceQL query over the given time window. "
                                + "TraceQL selects spans by attribute, e.g. "
                                + "{ resource.service.name = \"checkout\" } or "
                                + "{ resource.service.name = \"checkout\" && status = error }. Discover "
                                + "attribute names first with list_trace_tags. Use to find an exemplar "
                                + "trace for a symptom.",
                        SearchTracesInput.class),
                tool(ReasoningToolNames.LIST_LOG_LABELS,
                        "List the available Loki log label names. Use this BEFORE search_logs to discover "
                                + "how streams are labelled rather than assuming a label name. Takes no arguments.",
                        NoArgs.class),
                tool(ReasoningToolNames.LIST_LOG_LABEL_VALUES,
                        "List the values of one Loki log label (e.g. which services exist). Pass the label "
                                + "name exactly as returned by list_log_labels.",
                        ListLogLabelValuesInput.class),
                tool(ReasoningToolNames.LIST_METRIC_NAMES,
                        "List the available Prometheus metric names. Use this BEFORE query_metrics to "
                                + "confirm a metric exists rather than assuming one. Takes no arguments.",
                        NoArgs.class),
                tool(ReasoningToolNames.LIST_TRACE_TAGS,
                        "List the available Tempo trace tags, grouped by scope (resource, span, intrinsic). "
                                + "Use this BEFORE search_traces to find the correct attribute name and scope "
                                + "— e.g. a service is 'resource.service.name', not bare 'service.name'. "
                                + "Takes no arguments.",
                        NoArgs.class),
                tool(ReasoningToolNames.LIST_TRACE_TAG_VALUES,
                        "List the values of one Tempo trace tag. Pass the scoped tag name exactly as "
                                + "returned by list_trace_tags (e.g. 'resource.service.name').",
                        ListTraceTagValuesInput.class),
                tool(ReasoningToolNames.SEARCH_PAST_INVESTIGATIONS,
                        "Recall past investigations similar to the query. Use early to check whether "
                                + "this symptom has been seen and resolved before.",
                        SearchPastInvestigationsInput.class),
                tool(ReasoningToolNames.CONCLUDE,
                        "End the investigation. Call this once the evidence supports a root cause. "
                                + "confidence must be one of LOW, MEDIUM, HIGH. Optionally set "
                                + "keySignalIndex to the number ([N]) of the single gathered signal "
                                + "from 'Evidence gathered so far' that best demonstrates the root "
                                + "cause; omit it if no single signal stands out.",
                        ConcludeInput.class));
    }

    private static <I> ToolCallback tool(String name, String description, Class<I> inputType) {
        Function<I, String> notExecutedHere = input -> "";
        return FunctionToolCallback.builder(name, notExecutedHere)
                .description(description)
                .inputType(inputType)
                .build();
    }
}
