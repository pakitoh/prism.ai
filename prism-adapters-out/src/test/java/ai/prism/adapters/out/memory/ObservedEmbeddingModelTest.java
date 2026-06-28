package ai.prism.adapters.out.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class ObservedEmbeddingModelTest {

    // A no-op OpenTelemetry exercises the span lifecycle without an SDK; the prism.embedding
    // span (inputs/dimensions attributes) is verified live in Tempo.
    private final OpenTelemetry openTelemetry = OpenTelemetry.noop();

    @Test
    void embedsAndReturnsTheVector() {
        EmbeddingModel model = new ObservedEmbeddingModel(
                new StubEmbeddingModel(new float[] {0.1f, 0.2f, 0.3f}), openTelemetry);

        assertThat(model.embed("checkout-service erroring")).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void propagatesFailures() {
        EmbeddingModel model = new ObservedEmbeddingModel(new StubEmbeddingModel(null), openTelemetry);

        assertThatThrownBy(() -> model.embed("boom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding unavailable");
    }

    /** Minimal {@link EmbeddingModel}: a non-null vector embeds successfully; null throws. */
    private record StubEmbeddingModel(float[] vector) implements EmbeddingModel {

        @Override
        public float[] embed(String text) {
            if (vector == null) {
                throw new IllegalStateException("embedding unavailable");
            }
            return vector;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
