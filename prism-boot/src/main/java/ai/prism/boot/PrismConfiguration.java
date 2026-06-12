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
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
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
    ReasoningPort reasoningPort(ChatModel geminiChatModel, JsonMapper jsonMapper,
                                ReasoningProperties properties, ObservationRegistry observationRegistry) {
        List<ReasoningPort> delegates = new ArrayList<>();

        // Gemini delegates: one per configured model id (empty list = provider default).
        List<String> geminiModels = properties.models() == null || properties.models().isEmpty()
                ? Collections.singletonList(null)
                : properties.models();
        for (String model : geminiModels) {
            delegates.add(new SpringAiReasoningAdapter(geminiChatModel, jsonMapper, model));
        }

        // Cross-provider model: Groq (OpenAI-compatible) joins the rotation after the
        // Gemini models — so a step rotates onto it when Gemini errors (e.g. a 429).
        // Active only with a key.
        ReasoningProperties.Groq groq = properties.groq();
        if (groq != null && groq.apiKey() != null && !groq.apiKey().isBlank()) {
            delegates.add(new SpringAiReasoningAdapter(buildGroqChatModel(groq), jsonMapper, groq.model()));
        }

        // Retry budget per step; default to one pass over the models when unset.
        int maxAttempts = properties.maxAttempts() != null && properties.maxAttempts() > 0
                ? properties.maxAttempts()
                : delegates.size();
        return new ObservedReasoningPort(new RetryingReasoningPort(delegates, maxAttempts), observationRegistry);
    }

    private static ChatModel buildGroqChatModel(ReasoningProperties.Groq groq) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(groq.baseUrl())
                .apiKey(groq.apiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(groq.model())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    @Bean
    InvestigationRepository investigationRepository(DataSource dataSource, JsonMapper jsonMapper) {
        return new PostgresInvestigationRepository(dataSource, jsonMapper);
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
