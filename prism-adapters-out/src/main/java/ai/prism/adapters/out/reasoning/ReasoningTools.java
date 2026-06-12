package ai.prism.adapters.out.reasoning;

import java.util.List;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Declares the tools the model may call, with their input schemas derived from
 * the record types below.
 *
 * <p>The tool functions are never invoked: the adapter runs with Spring AI's
 * internal tool execution disabled, so the model's choice is returned to the
 * application loop rather than executed here. The {@code Function} bodies exist
 * only to satisfy the builder.
 */
final class ReasoningTools {

    record QueryMetricsInput(String promQl, String from, String to) {
    }

    record SearchLogsInput(String logQl, String from, String to) {
    }

    record GetTraceInput(String traceId) {
    }

    record SearchTracesInput(String service, String from, String to) {
    }

    record SearchPastInvestigationsInput(String query) {
    }

    record ConcludeInput(String rootCause, String evidence, String recommendedAction, String confidence) {
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
                                + "for a service once a metric points at a suspect.",
                        SearchLogsInput.class),
                tool(ReasoningToolNames.GET_TRACE,
                        "Fetch a single distributed trace from Tempo by its trace id. Use to inspect "
                                + "the span tree of a specific failing request.",
                        GetTraceInput.class),
                tool(ReasoningToolNames.SEARCH_TRACES,
                        "Search Tempo for traces of a service over the given time window. Use to find "
                                + "an exemplar trace for a symptom.",
                        SearchTracesInput.class),
                tool(ReasoningToolNames.SEARCH_PAST_INVESTIGATIONS,
                        "Recall past investigations similar to the query. Use early to check whether "
                                + "this symptom has been seen and resolved before.",
                        SearchPastInvestigationsInput.class),
                tool(ReasoningToolNames.CONCLUDE,
                        "End the investigation. Call this once the evidence supports a root cause. "
                                + "confidence must be one of LOW, MEDIUM, HIGH.",
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
