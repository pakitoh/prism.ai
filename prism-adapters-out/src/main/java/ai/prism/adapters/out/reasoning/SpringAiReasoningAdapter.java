package ai.prism.adapters.out.reasoning;

import ai.prism.domain.reasoning.InvestigationContext;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.domain.reasoning.ReasoningStep;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>The framework's agentic tool-execution loop is not used: each call asks the
 * model for the single next step and returns its tool choice to the application
 * loop rather than executing it here. Each instance targets one model id (supplied
 * by configuration, overriding the provider default per call); compose several
 * instances with {@link RetryingReasoningPort}, which retries and rotates models
 * round-robin on error.
 */
public class SpringAiReasoningAdapter implements ReasoningPort {

    private static final String SYSTEM_PROMPT = """
            You are a production observability investigator. Given a problem and the
            evidence gathered so far, decide the single next step.

            You MUST respond by calling exactly one tool:
              - search_past_investigations to recall whether this symptom was seen and resolved before
              - query_metrics / search_logs / get_trace / search_traces to gather more evidence
              - list_metric_names / list_log_labels / list_log_label_values / list_trace_tags /
                list_trace_tag_values to discover the schema before querying
              - conclude once the evidence supports a root cause

            Work like an SRE: start from the symptom, correlate across metrics, logs and
            traces, and narrow to the failing component. Time arguments (from/to) are
            ISO-8601 UTC instants. Do not ask the user questions; gather evidence and conclude.

            Do not assume label, metric or tag names — they vary by stack. When unsure, discover
            them first with the list_* tools, then query. An empty result usually means the name
            was wrong, not that the service is absent: verify the name before inferring anything is
            down, and never conclude an outage from empty results alone.
            """;

    private static final Logger log = LoggerFactory.getLogger(SpringAiReasoningAdapter.class);

    private final ChatModel chatModel;
    private final JsonMapper jsonMapper;
    private final String model;
    private final ReasoningStepMapper stepMapper;
    private final List<ToolCallback> tools;
    private final String toolNames;

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
        this.toolNames = tools.stream()
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.joining(", "));
    }

    @Override
    public ReasoningStep nextStep(InvestigationContext context) {
        // Start from the model's own default options so that the concrete subtype
        // (e.g. GoogleGenAiChatOptions) is preserved — each provider casts the prompt
        // options back to its specific type inside call().
        ToolCallingChatOptions.Builder<?> optionsBuilder =
                ((ToolCallingChatOptions) chatModel.getOptions())
                        .mutate()
                        .toolCallbacks(tools);
        if (model != null && !model.isBlank()) {
            optionsBuilder.model(model);
        }
        String userMessage = renderUser(context);
        // DEBUG shows exactly what we send each step: model id, available tools and the
        // rendered user message (problem + evidence so far). The system prompt is a
        // constant in this class, so it is logged at TRACE to avoid repeating it per call.
        log.trace("Reasoning system prompt for model '{}':\n{}", model, SYSTEM_PROMPT);
        log.debug("Reasoning request to model '{}' (tools: {}):\n{}", model, toolNames, userMessage);
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userMessage)),
                optionsBuilder.build());

        ChatResponse response = chatModel.call(prompt);
        if (response == null || !response.hasToolCalls()) {
            throw new ReasoningException("The model returned no tool call; cannot determine the next step.");
        }

        AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
        log.debug("Reasoning response from model '{}': tool '{}' with arguments {}",
                model, call.name(), call.arguments());
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

    /** How many of the most recent signals are shown with their full (truncated) content. */
    private static final int RECENT_FULL = 6;
    /** Per-signal content cap; older bodies are dropped entirely (see {@link #RECENT_FULL}). */
    private static final int CONTENT_CAP = 800;

    private static String renderUser(InvestigationContext context) {
        StringBuilder out = new StringBuilder();
        out.append("Problem: ").append(context.request().query()).append('\n');
        context.request().service().ifPresent(s -> out.append("Service: ").append(s).append('\n'));
        context.request().window().ifPresent(w ->
                out.append("Window: ").append(w.from()).append(" to ").append(w.to()).append('\n'));

        out.append("\nYou are on step ").append(context.step()).append(" of at most ")
                .append(context.maxSteps()).append(". Gather only the evidence you still need and ")
                .append("conclude as soon as the root cause is supported — do not exhaust the budget exploring.\n");

        List<Signal> signals = context.priorSignals();
        if (signals.isEmpty()) {
            out.append("\nNo evidence gathered yet. Decide the first step.");
        } else {
            // Every signal is listed by number and query so the model recalls what it already
            // tried (and stops repeating queries); only the most recent few carry full bodies,
            // keeping the prompt from ballooning as evidence accumulates.
            int firstFull = Math.max(0, signals.size() - RECENT_FULL);
            out.append("\nEvidence gathered so far (referenceable by the leading number):\n");
            for (int i = 0; i < signals.size(); i++) {
                Signal signal = signals.get(i);
                out.append("- [").append(i + 1).append("] [").append(signal.type()).append("] ")
                        .append(signal.query());
                if (i >= firstFull) {
                    out.append(" =>\n").append(render(signal)).append('\n');
                } else {
                    out.append(" => [earlier result omitted]\n");
                }
            }
            out.append("\nDecide the next step.");
        }
        return out.toString();
    }

    /**
     * Renders a signal's content for the prompt. SCHEMA signals (seeded label/metric/tag names)
     * are shown in full — they are reference data the model needs entire; truncating a long
     * metric-name list would defeat the point. Everything else is capped at {@link #CONTENT_CAP}.
     */
    private static String render(Signal signal) {
        String content = signal.content();
        if (signal.type() == SignalType.SCHEMA || content.length() <= CONTENT_CAP) {
            return content;
        }
        return content.substring(0, CONTENT_CAP) + "…[" + content.length() + " chars total]";
    }
}
