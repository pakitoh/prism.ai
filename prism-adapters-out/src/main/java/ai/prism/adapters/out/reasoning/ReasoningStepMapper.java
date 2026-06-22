package ai.prism.adapters.out.reasoning;

import ai.prism.domain.reasoning.Conclusion;
import ai.prism.domain.reasoning.GetTrace;
import ai.prism.domain.reasoning.QueryMetrics;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.reasoning.SearchLogs;
import ai.prism.domain.reasoning.SearchPastInvestigations;
import ai.prism.domain.reasoning.SearchTraces;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Translates a model tool call — a tool name plus its decoded JSON arguments —
 * into a domain {@link ReasoningStep}.
 *
 * <p>Pure and provider-agnostic: it knows nothing about Spring AI or any model
 * SDK, which is what makes the investigation's most delicate logic fully
 * unit-testable without a network call.
 */
final class ReasoningStepMapper {

    ReasoningStep map(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case ReasoningToolNames.QUERY_METRICS ->
                    new QueryMetrics(string(arguments, "promQl"), window(arguments));
            case ReasoningToolNames.SEARCH_LOGS ->
                    new SearchLogs(string(arguments, "logQl"), window(arguments));
            case ReasoningToolNames.GET_TRACE ->
                    new GetTrace(string(arguments, "traceId"));
            case ReasoningToolNames.SEARCH_TRACES ->
                    new SearchTraces(string(arguments, "service"), window(arguments));
            case ReasoningToolNames.SEARCH_PAST_INVESTIGATIONS ->
                    new SearchPastInvestigations(string(arguments, "query"));
            case ReasoningToolNames.CONCLUDE -> new Conclusion(new Finding(
                    string(arguments, "rootCause"),
                    string(arguments, "evidence"),
                    string(arguments, "recommendedAction"),
                    confidence(arguments),
                    keySignalIndex(arguments)));
            default -> throw new IllegalStateException("Unknown reasoning tool: " + toolName);
        };
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required tool argument: " + key);
        }
        return value.toString();
    }

    private static TimeWindow window(Map<String, Object> arguments) {
        return new TimeWindow(
                Instant.parse(string(arguments, "from")),
                Instant.parse(string(arguments, "to")));
    }

    /**
     * Reads the optional key-signal nomination. The model sees signals numbered from 1
     * (see SpringAiReasoningAdapter#renderUser), so a 1-based value is converted to the
     * 0-based index into the investigation's signal list. Absent or unparseable → empty,
     * as is a non-positive number (no valid nomination).
     */
    private static OptionalInt keySignalIndex(Map<String, Object> arguments) {
        Object value = arguments.get("keySignalIndex");
        if (value == null || value.toString().isBlank()) {
            return OptionalInt.empty();
        }
        try {
            int oneBased = (int) Math.round(Double.parseDouble(value.toString().trim()));
            return oneBased >= 1 ? OptionalInt.of(oneBased - 1) : OptionalInt.empty();
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }

    private static Confidence confidence(Map<String, Object> arguments) {
        String raw = string(arguments, "confidence").trim().toUpperCase(Locale.ROOT);
        try {
            return Confidence.valueOf(raw);
        } catch (IllegalArgumentException unrecognised) {
            return Confidence.MEDIUM;
        }
    }
}
