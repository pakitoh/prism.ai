package ai.prism.adapters.out.reasoning;

/**
 * The tool names the model uses to express a {@link ai.prism.application.reasoning.ReasoningStep}.
 * Shared between the tool declarations sent to the model and the mapper that
 * interprets the model's tool call.
 */
final class ReasoningToolNames {

    static final String QUERY_METRICS = "query_metrics";
    static final String SEARCH_LOGS = "search_logs";
    static final String GET_TRACE = "get_trace";
    static final String SEARCH_TRACES = "search_traces";
    static final String CONCLUDE = "conclude";

    private ReasoningToolNames() {
    }
}
