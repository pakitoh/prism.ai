package ai.prism.adapters.out.reasoning;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Signal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ReasoningPort} backed by Spring AI.
 *
 * <p>Internal tool execution is disabled, so each call asks the model for the
 * single next step and returns its tool choice to the application loop rather
 * than executing it here. Each instance targets one model id (supplied by
 * configuration, overriding the provider default per call); compose several
 * instances with {@link RetryingReasoningPort} for primary/fallback selection.
 */
public class SpringAiReasoningAdapter implements ReasoningPort {

    private static final String SYSTEM_PROMPT = """
            You are a production observability investigator. Given a problem and the
            evidence gathered so far, decide the single next step.

            You MUST respond by calling exactly one tool:
              - search_past_investigations to recall whether this symptom was seen and resolved before
              - query_metrics / search_logs / get_trace / search_traces to gather more evidence
              - conclude once the evidence supports a root cause

            Work like an SRE: start from the symptom, correlate across metrics, logs and
            traces, and narrow to the failing component. Time arguments (from/to) are
            ISO-8601 UTC instants. Do not ask the user questions; gather evidence and conclude.
            """;

    private final ChatModel chatModel;
    private final JsonMapper jsonMapper;
    private final String model;
    private final ReasoningStepMapper stepMapper;
    private final List<ToolCallback> tools;

    /**
     * @param model the model id to target per call, or {@code null}/blank to use
     *              the provider's configured default
     */
    public SpringAiReasoningAdapter(ChatModel chatModel, JsonMapper jsonMapper, String model) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "objectMapper must not be null");
        this.model = model;
        this.stepMapper = new ReasoningStepMapper();
        this.tools = ReasoningTools.all();
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        var optionsBuilder = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false);
        if (model != null && !model.isBlank()) {
            optionsBuilder.model(model);
        }
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(renderUser(context))),
                optionsBuilder.build());

        ChatResponse response = chatModel.call(prompt);
        if (response == null || !response.hasToolCalls()) {
            throw new ReasoningException("The model returned no tool call; cannot determine the next step.");
        }

        AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
        return stepMapper.map(call.name(), parseArguments(call.arguments()));
    }

    private Map<String, Object> parseArguments(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception malformed) {
            throw new ReasoningException("Could not parse tool arguments: " + json, malformed);
        }
    }

    private static String renderUser(InvestigationContext context) {
        StringBuilder out = new StringBuilder();
        out.append("Problem: ").append(context.request().query()).append('\n');
        context.request().service().ifPresent(s -> out.append("Service: ").append(s).append('\n'));
        context.request().window().ifPresent(w ->
                out.append("Window: ").append(w.from()).append(" to ").append(w.to()).append('\n'));

        List<Signal> signals = context.priorSignals();
        if (signals.isEmpty()) {
            out.append("\nNo evidence gathered yet. Decide the first step.");
        } else {
            out.append("\nEvidence gathered so far:\n");
            for (Signal signal : signals) {
                out.append("- [").append(signal.type()).append("] ")
                        .append(signal.query()).append(" =>\n")
                        .append(signal.content()).append('\n');
            }
            out.append("\nDecide the next step.");
        }
        return out.toString();
    }
}
