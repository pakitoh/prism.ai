package ai.prism.boot;

import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.JdkHttpExecutor;
import ai.prism.adapters.out.knowledge.InMemoryInvestigationKnowledgeBase;
import ai.prism.adapters.out.knowledge.PgVectorKnowledgeAdapter;
import ai.prism.adapters.out.knowledge.RememberingInvestigateUseCase;
import ai.prism.adapters.out.observability.ObservedEmbeddingModel;
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
import com.openai.credential.BearerTokenCredential;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelCompletionObservationHandler;
import org.springframework.ai.chat.observation.ChatModelPromptContentObservationHandler;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the hexagon: plain adapter classes become beans here, the only module
 * that knows Spring. The domain and application layers stay framework-free.
 */
@Configuration
@EnableConfigurationProperties(ReasoningProperties.class)
public class PrismConfiguration {

    @Bean
    @ConfigurationPropertiesBinding
    Converter<String, ReasoningProperties.ModelConfig> modelConfigConverter() {
        return id -> new ReasoningProperties.ModelConfig(id, null, null);
    }

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
    ReasoningPort reasoningPort(JsonMapper jsonMapper,
                                ReasoningProperties properties,
                                ObservationRegistry observationRegistry) {
        List<ReasoningProperties.ModelConfig> models = properties.models();
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("No models configured!!");
        }
        if (properties.maxAttempts() == null || properties.maxAttempts() < 0) {
            throw new IllegalArgumentException("No max attempts have been configured!!");
        }
        List<RetryingReasoningPort.Delegate> delegates = models.stream()
                .map(config -> new RetryingReasoningPort.Delegate(
                        config.id(),
                        new SpringAiReasoningAdapter(
                                buildOpenAiCompatibleChatModel(config, observationRegistry),
                                jsonMapper,
                                config.id())))
                .collect(Collectors.toUnmodifiableList());
        return new ObservedReasoningPort(
                new RetryingReasoningPort(
                        delegates,
                        properties.maxAttempts()),
                observationRegistry);
    }

    private static ChatModel buildOpenAiCompatibleChatModel(ReasoningProperties.ModelConfig config,
                                                            ObservationRegistry observationRegistry) {
        // OpenAiChatModel.Builder always creates an async client via the options when
        // openAiClientAsync is not provided. apiKey + baseUrl must be set in the options
        // so that async-client creation succeeds — without them it calls setupAsyncClient
        // with null credentials and throws at startup.
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient(config.baseUrl(), config.apiKey(), observationRegistry))
                .options(OpenAiChatOptions.builder()
                        .model(config.id())
                        .apiKey(config.apiKey())
                        .baseUrl(config.baseUrl())
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }

    // Spring AI 2.0 builds OpenAI models on the official com.openai SDK client.
    // Retry policy: the HTTP client retries up to HTTP_MAX_RETRIES times for transient
    // errors (429, 5xx) before surfacing an exception to RetryingReasoningPort, which
    // then rotates to the next model. One "attempt" in RetryingReasoningPort may
    // therefore represent up to (1 + HTTP_MAX_RETRIES) HTTP calls.
    private static final int HTTP_MAX_RETRIES = 2;

    private static OpenAIClient openAiClient(String baseUrl, String apiKey,
                                             ObservationRegistry observationRegistry) {
        return OpenAiSetup.setupSyncClient(
                baseUrl,
                null,
                BearerTokenCredential.create(apiKey),
                null,
                null,
                null,
                false,
                false,
                null,
                Duration.ofSeconds(60),
                HTTP_MAX_RETRIES,
                null,
                null,
                observationRegistry,
                null,
                List.of());
    }

    @Bean
    InvestigationRepository investigationRepository(DataSource dataSource, JsonMapper jsonMapper) {
        return new PostgresInvestigationRepository(dataSource, jsonMapper);
    }

    // Spring AI exposes spring.ai.chat.observations.log-prompt / log-completion, but its
    // 2.0-RC2 autoconfiguration only registers the meter handler — not these logging
    // handlers. Register them ourselves so the documented toggles work: when enabled they
    // log the full assembled prompt / completion (INFO, on the handler's own logger).
    // Off by default; the content may include sensitive data, so opt in deliberately.
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.chat.observations", name = "log-prompt", havingValue = "true")
    ChatModelPromptContentObservationHandler chatModelPromptContentObservationHandler() {
        return new ChatModelPromptContentObservationHandler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.chat.observations", name = "log-completion", havingValue = "true")
    ChatModelCompletionObservationHandler chatModelCompletionObservationHandler() {
        return new ChatModelCompletionObservationHandler();
    }

    // The EmbeddingModel bean is contributed by the spring-ai-google-genai-embedding
    // starter's autoconfiguration (GoogleGenAiTextEmbeddingModel), configured via
    // spring.ai.google.genai.* properties. PgVectorKnowledgeAdapter consumes it through
    // the EmbeddingModel port below.

    @Bean
    @ConditionalOnProperty(prefix = "prism.knowledge", name = "store", havingValue = "pgvector", matchIfMissing = true)
    InvestigationKnowledgeBase pgVectorKnowledgeBase(EmbeddingModel embeddingModel, DataSource dataSource, Clock clock,
                                                     ObservationRegistry observationRegistry) {
        // Decorate the autoconfigured embedding model so each call is logged and traced.
        EmbeddingModel observed = new ObservedEmbeddingModel(embeddingModel, observationRegistry);
        return new PgVectorKnowledgeAdapter(observed, dataSource, clock);
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
                                          Clock clock,
                                          @Value("${prism.investigation.max-steps}") int maxSteps,
                                          ObservationRegistry observationRegistry) {
        InvestigationService service = new InvestigationService(
                reasoningPort,
                metricsPort,
                logsPort,
                tracingPort,
                knowledgeBase,
                repository,
                clock,
                maxSteps);
        InvestigateUseCase remembering = new RememberingInvestigateUseCase(service, knowledgeBase);
        return new ObservedInvestigateUseCase(remembering, observationRegistry);
    }
}
