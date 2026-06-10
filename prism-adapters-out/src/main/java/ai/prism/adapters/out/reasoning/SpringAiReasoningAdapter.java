package ai.prism.adapters.out.reasoning;

import ai.prism.application.port.out.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Signal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * {@link ReasoningPort} backed by Spring AI.
 *
 * <p>Internal tool execution is disabled, so each call asks the model for the
 * single next step and returns its tool choice to the application loop rather
 * than executing it here. The provider and model are chosen by configuration —
 * no provider or model name appears in this class.
 */
public class SpringAiReasoningAdapter implements ReasoningPort {

    private static final String SYSTEM_PROMPT = """
            You are a production observability investigator. Given a problem and the
            evidence gathered so far, decide the single next step.

            You MUST respond by calling exactly one tool:
              - query_metrics / search_logs / get_trace / search_traces to gather more evidence
              - conclude once the evidence supports a root cause

            Work like an SRE: start from the symptom, correlate across metrics, logs and
            traces, and narrow to the failing component. Time arguments (from/to) are
            ISO-8601 UTC instants. Do not ask the user questions; gather evidence and conclude.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ReasoningStepMapper stepMapper;
    private final List<ToolCallback> tools;

    public SpringAiReasoningAdapter(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stepMapper = new ReasoningStepMapper();
        this.tools = ReasoningTools.all();
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(renderUser(context))),
                ToolCallingChatOptions.builder()
                        .toolCallbacks(tools)
                        .internalToolExecutionEnabled(false)
                        .build());

        ChatResponse response = chatModel.call(prompt);
        if (response == null || !response.hasToolCalls()) {
            throw new ReasoningException("The model returned no tool call; cannot determine the next step.");
        }

        AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
        return stepMapper.map(call.name(), parseArguments(call.arguments()));
    }

    private Map<String, Object> parseArguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
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
