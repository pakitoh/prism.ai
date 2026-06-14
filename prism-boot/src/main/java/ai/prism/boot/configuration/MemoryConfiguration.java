package ai.prism.boot.configuration;

import ai.prism.adapters.out.memory.PgVectorMemory;
import ai.prism.adapters.out.memory.TokenOverlapMemory;
import ai.prism.adapters.out.memory.ObservedEmbeddingModel;
import ai.prism.application.port.out.MemoryPort;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the memory of past investigations, selected by {@code prism.knowledge.store}:
 * pgvector semantic recall (default) or in-process token-overlap (dev). The
 * {@link EmbeddingModel} the pgvector store uses is contributed by the
 * spring-ai-google-genai-embedding starter's autoconfiguration.
 */
@Configuration
class MemoryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "prism.knowledge", name = "store", havingValue = "pgvector", matchIfMissing = true)
    MemoryPort pgVectorInvestigationMemory(EmbeddingModel embeddingModel, DataSource dataSource,
                                           Clock clock, ObservationRegistry observationRegistry) {
        // Decorate the autoconfigured embedding model so each call is logged and traced.
        EmbeddingModel observed = new ObservedEmbeddingModel(embeddingModel, observationRegistry);
        return new PgVectorMemory(observed, dataSource, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prism.knowledge", name = "store", havingValue = "memory")
    MemoryPort tokenOverlapInvestigationMemory(Clock clock) {
        return new TokenOverlapMemory(clock);
    }
}
