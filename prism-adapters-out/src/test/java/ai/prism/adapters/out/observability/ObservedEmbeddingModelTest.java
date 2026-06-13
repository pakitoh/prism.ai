package ai.prism.adapters.out.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class ObservedEmbeddingModelTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void recordsAnEmbeddingObservationTaggedWithInputsAndDimensions() {
        EmbeddingModel delegate = new StubEmbeddingModel(new float[] {0.1f, 0.2f, 0.3f});
        EmbeddingModel model = new ObservedEmbeddingModel(delegate, registry);

        float[] vector = model.embed("checkout-service erroring");

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.embedding")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("inputs", "1")
                .hasLowCardinalityKeyValue("dimensions", "3");
    }

    @Test
    void propagatesAndStopsObservationOnFailure() {
        EmbeddingModel delegate = new StubEmbeddingModel(null); // throws on embed
        EmbeddingModel model = new ObservedEmbeddingModel(delegate, registry);

        assertThatThrownBy(() -> model.embed("boom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding unavailable");
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.embedding")
                .that()
                .hasBeenStopped();
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
