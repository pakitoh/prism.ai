package ai.prism.adapters.out.memory;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Instruments the embedding model so each call is visible: a {@code prism.embedding}
 * span and timer (tagged with input count and vector dimension), plus a log line per
 * call. Wraps the autoconfigured {@link EmbeddingModel} so the Gemini embedding call
 * made during memory store/recall shows up in both Tempo and the application logs.
 *
 * <p>Only the call paths the application actually uses are instrumented
 * ({@code call}, {@code embed(String)}, {@code embed(Document)}); the remaining
 * convenience overloads delegate straight through to preserve the delegate's behaviour.
 */
public class ObservedEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ObservedEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final ObservationRegistry registry;

    public ObservedEmbeddingModel(EmbeddingModel delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return observe(request.getInstructions().size(), () -> delegate.call(request),
                response -> response.getResults().isEmpty()
                        ? 0
                        : response.getResults().get(0).getOutput().length);
    }

    @Override
    public float[] embed(String text) {
        return observe(1, () -> delegate.embed(text), vector -> vector.length);
    }

    @Override
    public float[] embed(Document document) {
        return observe(1, () -> delegate.embed(document), vector -> vector.length);
    }

    private <T> T observe(int inputs, Supplier<T> call, ToIntFunction<T> dimensions) {
        Observation observation = Observation.createNotStarted("prism.embedding", registry)
                .lowCardinalityKeyValue("inputs", Integer.toString(inputs));
        long start = System.nanoTime();
        try {
            T result = observation.observe(() -> {
                T value = call.get();
                observation.lowCardinalityKeyValue("dimensions", Integer.toString(dimensions.applyAsInt(value)));
                return value;
            });
            log.info("Embedded {} input(s) -> vector dim {} in {} ms",
                    inputs, dimensions.applyAsInt(result), millisSince(start));
            return result;
        } catch (RuntimeException failure) {
            log.warn("Embedding failed for {} input(s) after {} ms: {}",
                    inputs, millisSince(start), failure.toString());
            throw failure;
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // --- Unused convenience overloads: delegate straight through. ---

    @Override
    public List<float[]> embed(List<String> texts) {
        return delegate.embed(texts);
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        return delegate.embed(documents, options, batchingStrategy);
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        return delegate.embedForResponse(texts);
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
