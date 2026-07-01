package ai.prism.adapters.out.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ObservedChatModelTest {

    // A no-op OpenTelemetry exercises the span/metric lifecycle without an SDK; the gen_ai.* and
    // token attributes are verified live in Langfuse/Tempo.
    private final OpenTelemetry openTelemetry = OpenTelemetry.noop();

    @Test
    void callsTheDelegateAndReturnsItsResponse() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))));
        ChatModel model = new ObservedChatModel(new StubChatModel(response), openTelemetry, true);

        assertThat(model.call(new Prompt("why errors?"))).isSameAs(response);
    }

    @Test
    void toleratesAResponseWithNoUsageOrModel() {
        // A bare response (no usage / blank model) must not NPE while recording token attributes.
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))));
        ChatModel model = new ObservedChatModel(new StubChatModel(response), openTelemetry, true);

        assertThat(model.call(new Prompt("why errors?"))).isSameAs(response);
    }

    @Test
    void propagatesFailures() {
        ChatModel model = new ObservedChatModel(new StubChatModel(null), openTelemetry, false);

        assertThatThrownBy(() -> model.call(new Prompt("boom")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chat unavailable");
    }

    /** Minimal {@link ChatModel}: a non-null response is returned; null throws. */
    private record StubChatModel(ChatResponse response) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            if (response == null) {
                throw new IllegalStateException("chat unavailable");
            }
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
