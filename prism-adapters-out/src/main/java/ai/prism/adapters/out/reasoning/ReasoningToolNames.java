package ai.prism.adapters.out.reasoning;

/**
 * The tool names the model uses to express a {@link ai.prism.domain.reasoning.ReasoningStep}.
 * Shared between the tool declarations sent to the model and the mapper that
 * interprets the model's tool call.
 */
final class ReasoningToolNames {

    static final String QUERY_METRICS = "query_metrics";
    static final String SEARCH_LOGS = "search_logs";
    static final String GET_TRACE = "get_trace";
    static final String SEARCH_TRACES = "search_traces";
    static final String LIST_LOG_LABELS = "list_log_labels";
    static final String LIST_LOG_LABEL_VALUES = "list_log_label_values";
    static final String LIST_METRIC_NAMES = "list_metric_names";
    static final String LIST_TRACE_TAGS = "list_trace_tags";
    static final String LIST_TRACE_TAG_VALUES = "list_trace_tag_values";
    static final String SEARCH_PAST_INVESTIGATIONS = "search_past_investigations";
    static final String CONCLUDE = "conclude";

    private ReasoningToolNames() {
    }
}
