package ai.prism.boot;

import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.JdkHttpExecutor;
import ai.prism.adapters.out.knowledge.InMemoryInvestigationKnowledgeBase;
import ai.prism.adapters.out.knowledge.PgVectorKnowledgeAdapter;
import ai.prism.adapters.out.knowledge.RememberingInvestigateUseCase;
import ai.prism.adapters.out.observability.ObservedHttpExecutor;
import ai.prism.adapters.out.observability.ObservedInvestigateUseCase;
import ai.prism.adapters.out.observability.ObservedReasoningPort;
import ai.prism.adapters.out.persistence.PostgresInvestigationRepository;
import ai.prism.adapters.out.reasoning.RetryingReasoningPort;
import ai.prism.adapters.out.reasoning.SpringAiReasoningAdapter;
import ai.prism.adapters.out.telemetry.LokiAdapter;
import ai.prism.adapters.out.telemetry.PrometheusAdapter;
import ai.prism.adapters.out.telemetry.TempoAdapter;
import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.application.port.out.InvestigationKnowledgeBase;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TracingPort;
import ai.prism.application.service.InvestigationService;
import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the hexagon: plain adapter classes become beans here, the only module
 * that knows Spring. The domain and application layers stay framework-free.
 */
@Configuration
@EnableConfigurationProperties(ReasoningProperties.class)
public class PrismConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpExecutor httpExecutor(ObservationRegistry observationRegistry) {
        // Instrument every outbound telemetry query at the single choke point.
        return new ObservedHttpExecutor(JdkHttpExecutor.create(), observationRegistry);
    }

    @Bean
    MetricsPort metricsPort(HttpExecutor http, Clock clock,
                            @Value("${prism.telemetry.prometheus-url}") String baseUrl) {
        return new PrometheusAdapter(http, baseUrl, clock);
    }

    @Bean
    LogsPort logsPort(HttpExecutor http, Clock clock,
                      @Value("${prism.telemetry.loki-url}") String baseUrl) {
        return new LokiAdapter(http, baseUrl, clock);
    }

    @Bean
    TracingPort tracingPort(HttpExecutor http, Clock clock,
                            @Value("${prism.telemetry.tempo-url}") String baseUrl) {
        return new TempoAdapter(http, baseUrl, clock);
    }

    @Bean
    ReasoningPort reasoningPort(ChatModel chatModel, JsonMapper jsonMapper,
                                ReasoningProperties properties, ObservationRegistry observationRegistry) {
        List<ReasoningPort> delegates = new ArrayList<>();

        List<ReasoningProperties.ModelConfig> models = properties.models();
        if (models == null || models.isEmpty()) {
            delegates.add(new SpringAiReasoningAdapter(chatModel, jsonMapper, null));
        } else {
            for (ReasoningProperties.ModelConfig config : models) {
                boolean explicit = config.baseUrl() != null && !config.baseUrl().isBlank();
                delegates.add(new SpringAiReasoningAdapter(
                        explicit ? buildOpenAiCompatibleChatModel(config) : chatModel,
                        jsonMapper, config.id()));
            }
        }

        int maxAttempts = properties.maxAttempts() != null && properties.maxAttempts() > 0
                ? properties.maxAttempts()
                : delegates.size();
        return new ObservedReasoningPort(new RetryingReasoningPort(delegates, maxAttempts), observationRegistry);
    }

    private static ChatModel buildOpenAiCompatibleChatModel(ReasoningProperties.ModelConfig config) {
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient(config.baseUrl(), config.apiKey()))
                .options(OpenAiChatOptions.builder().model(config.id()).build())
                .build();
    }

    // Spring AI 2.0 builds OpenAI models on the official com.openai SDK client. This
    // helper creates a sync client for any OpenAI-compatible endpoint (base URL + key).
    private static OpenAIClient openAiClient(String baseUrl, String apiKey) {
        return OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null,
                false, false, null, Duration.ofSeconds(60), 2, null, null,
                ObservationRegistry.NOOP, null, List.of());
    }

    @Bean
    InvestigationRepository investigationRepository(DataSource dataSource, JsonMapper jsonMapper) {
        return new PostgresInvestigationRepository(dataSource, jsonMapper);
    }

    @Bean
    EmbeddingModel embeddingModel(@Value("${prism.embedding.api-key:}") String apiKey,
                                  @Value("${prism.embedding.model}") String model,
                                  @Value("${prism.embedding.base-url}") String baseUrl) {
        // Spring AI's google-genai module has no Gemini EmbeddingModel, so we reach
        // Gemini's OpenAI-compatible /embeddings endpoint with the same key.
        return new OpenAiEmbeddingModel(openAiClient(baseUrl, apiKey), MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "prism.knowledge", name = "store", havingValue = "pgvector", matchIfMissing = true)
    InvestigationKnowledgeBase pgVectorKnowledgeBase(EmbeddingModel embeddingModel, DataSource dataSource, Clock clock) {
        return new PgVectorKnowledgeAdapter(embeddingModel, dataSource, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prism.knowledge", name = "store", havingValue = "memory")
    InvestigationKnowledgeBase inMemoryKnowledgeBase(Clock clock) {
        return new InMemoryInvestigationKnowledgeBase(clock);
    }

    @Bean
    InvestigateUseCase investigateUseCase(ReasoningPort reasoningPort,
                                          MetricsPort metricsPort,
                                          LogsPort logsPort,
                                          TracingPort tracingPort,
                                          InvestigationKnowledgeBase knowledgeBase,
                                          InvestigationRepository repository,
                                          @Value("${prism.investigation.max-steps}") int maxSteps,
                                          ObservationRegistry observationRegistry) {
        InvestigationService service = new InvestigationService(
                reasoningPort, metricsPort, logsPort, tracingPort, knowledgeBase, repository, maxSteps);
        InvestigateUseCase remembering = new RememberingInvestigateUseCase(service, knowledgeBase);
        return new ObservedInvestigateUseCase(remembering, observationRegistry);
    }
}
