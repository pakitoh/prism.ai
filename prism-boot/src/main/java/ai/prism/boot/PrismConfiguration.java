package ai.prism.boot;

import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.JdkHttpExecutor;
import ai.prism.adapters.out.observability.ObservedHttpExecutor;
import ai.prism.adapters.out.observability.ObservedInvestigateUseCase;
import ai.prism.adapters.out.observability.ObservedReasoningPort;
import ai.prism.adapters.out.persistence.PostgresInvestigationRepository;
import ai.prism.adapters.out.reasoning.FallbackReasoningPort;
import ai.prism.adapters.out.reasoning.SpringAiReasoningAdapter;
import ai.prism.adapters.out.telemetry.LokiAdapter;
import ai.prism.adapters.out.telemetry.PrometheusAdapter;
import ai.prism.adapters.out.telemetry.TempoAdapter;
import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TracingPort;
import ai.prism.application.service.InvestigationService;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
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
        // One adapter per configured model id; an empty list means "provider default".
        List<String> models = properties.models() == null || properties.models().isEmpty()
                ? Collections.singletonList(null)
                : properties.models();
        List<ReasoningPort> delegates = models.stream()
                .map(model -> (ReasoningPort) new SpringAiReasoningAdapter(chatModel, jsonMapper, model))
                .toList();
        return new ObservedReasoningPort(new FallbackReasoningPort(delegates), observationRegistry);
    }

    @Bean
    InvestigationRepository investigationRepository(DataSource dataSource, JsonMapper jsonMapper) {
        return new PostgresInvestigationRepository(dataSource, jsonMapper);
    }

    @Bean
    InvestigateUseCase investigateUseCase(ReasoningPort reasoningPort,
                                          MetricsPort metricsPort,
                                          LogsPort logsPort,
                                          TracingPort tracingPort,
                                          InvestigationRepository repository,
                                          @Value("${prism.investigation.max-steps}") int maxSteps,
                                          ObservationRegistry observationRegistry) {
        InvestigationService service =
                new InvestigationService(reasoningPort, metricsPort, logsPort, tracingPort, repository, maxSteps);
        return new ObservedInvestigateUseCase(service, observationRegistry);
    }
}
