package ai.prism.adapters.out.reasoning;

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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Instruments each LLM call so the reasoning is observable and scoreable: a
 * {@code prism.reasoning.llm} span carrying the OpenTelemetry GenAI semantic-convention
 * attributes (model id + token usage) that Langfuse reads to turn the span into a costed
 * "generation", plus a {@code prism.tokens} histogram (tagged by model and input/output).
 *
 * <p>Mirrors {@link ai.prism.adapters.out.memory.ObservedEmbeddingModel}: wraps the autoconfigured
 * {@link ChatModel}, instruments the one path the reasoning adapter uses ({@code call(Prompt)}) and
 * delegates the rest. The span nests under the current {@code prism.reasoning.step} span, so one
 * investigation forms a single trace tree (investigation → step → llm). Cost is not computed here —
 * Langfuse derives it downstream from the model id and token counts.
 *
 * <p>When {@code captureIo} is set it also records the prompt and the chosen tool call as
 * {@code gen_ai.prompt} / {@code gen_ai.completion} (bounded), giving Langfuse the generation's I/O
 * for later evaluation; it can be turned off where prompts may carry sensitive data.
 */
public class ObservedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ObservedChatModel.class);

    private static final AttributeKey<String> MODEL = AttributeKey.stringKey("model");
    private static final AttributeKey<String> TOKEN_TYPE = AttributeKey.stringKey("token.type");

    /** Cap on captured prompt/completion text, so span attributes stay bounded. */
    private static final int IO_CAP = 8000;

    private final ChatModel delegate;
    private final Tracer tracer;
    private final LongHistogram tokens;
    private final boolean captureIo;

    public ObservedChatModel(ChatModel delegate, OpenTelemetry openTelemetry, boolean captureIo) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.tracer = openTelemetry.getTracer("ai.prism.reasoning");
        this.tokens = openTelemetry.getMeter("ai.prism.reasoning")
                .histogramBuilder("prism.tokens").ofLongs().build();
        this.captureIo = captureIo;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Span span = tracer.spanBuilder("prism.reasoning.llm")
                .setAttribute("gen_ai.operation.name", "chat")
                .startSpan();
        long start = System.nanoTime();
        if (captureIo) {
            span.setAttribute("gen_ai.prompt", truncate(prompt.getContents()));
        }
        try (Scope ignored = span.makeCurrent()) {
            ChatResponse response = delegate.call(prompt);
            record(span, response);
            return response;
        } catch (RuntimeException failure) {
            span.setStatus(StatusCode.ERROR, failure.getMessage() != null ? failure.getMessage() : failure.toString());
            throw failure;
        } finally {
            log.debug("LLM call completed in {} ms", (System.nanoTime() - start) / 1_000_000L);
            span.end();
        }
    }

    private void record(Span span, ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        String model = response.getMetadata().getModel();
        if (model != null && !model.isBlank()) {
            span.setAttribute("gen_ai.request.model", model);
            span.setAttribute("gen_ai.response.model", model);
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            recordTokens(span, model, "input", usage.getPromptTokens(), "gen_ai.usage.input_tokens");
            recordTokens(span, model, "output", usage.getCompletionTokens(), "gen_ai.usage.output_tokens");
            Integer total = usage.getTotalTokens();
            if (total != null) {
                span.setAttribute("gen_ai.usage.total_tokens", total.longValue());
            }
        }
        if (captureIo) {
            completion(response).ifPresent(text -> span.setAttribute("gen_ai.completion", truncate(text)));
        }
    }

    private void recordTokens(Span span, String model, String type, Integer count, String attribute) {
        if (count == null) {
            return;
        }
        span.setAttribute(attribute, count.longValue());
        tokens.record(count, Attributes.of(MODEL, model == null ? "unknown" : model, TOKEN_TYPE, type));
    }

    /** The chosen tool call (reasoning always answers with one) as a compact "name args" string. */
    private static java.util.Optional<String> completion(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return java.util.Optional.empty();
        }
        AssistantMessage output = response.getResult().getOutput();
        if (!output.hasToolCalls() || output.getToolCalls().isEmpty()) {
            return java.util.Optional.empty();
        }
        AssistantMessage.ToolCall call = output.getToolCalls().get(0);
        return java.util.Optional.of(call.name() + " " + call.arguments());
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= IO_CAP ? text : text.substring(0, IO_CAP) + "…[" + text.length() + " chars]";
    }

    // --- Delegated paths: the reasoning adapter only calls call(Prompt) and getOptions(). ---

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }
}
