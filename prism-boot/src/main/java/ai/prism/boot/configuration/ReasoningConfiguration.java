package ai.prism.boot.configuration;

import ai.prism.adapters.out.reasoning.ObservedReasoningPort;
import ai.prism.adapters.out.reasoning.RetryingReasoningPort;
import ai.prism.adapters.out.reasoning.SpringAiReasoningAdapter;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.boot.ReasoningProperties;
import com.openai.client.OpenAIClient;
import com.openai.credential.BearerTokenCredential;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelCompletionObservationHandler;
import org.springframework.ai.chat.observation.ChatModelPromptContentObservationHandler;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the reasoning outbound port: one {@link SpringAiReasoningAdapter} per
 * configured model, composed with retry/rotation and observability. Also registers
 * the optional chat prompt/completion logging handlers.
 */
@Configuration
@EnableConfigurationProperties(ReasoningProperties.class)
class ReasoningConfiguration {

    // Spring AI 2.0 builds OpenAI models on the official com.openai SDK client.
    // Retry policy: the HTTP client retries up to HTTP_MAX_RETRIES times for transient
    // errors (429, 5xx) before surfacing an exception to RetryingReasoningPort, which
    // then rotates to the next model. One "attempt" in RetryingReasoningPort may
    // therefore represent up to (1 + HTTP_MAX_RETRIES) HTTP calls.
    private static final int HTTP_MAX_RETRIES = 4;

    @Bean
    @ConfigurationPropertiesBinding
    Converter<String, ReasoningProperties.ModelConfig> modelConfigConverter() {
        return id -> new ReasoningProperties.ModelConfig(id, null, null);
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
                new RetryingReasoningPort(delegates, properties.maxAttempts()),
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
}
