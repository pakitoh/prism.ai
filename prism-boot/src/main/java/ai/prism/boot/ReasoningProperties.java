package ai.prism.boot;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reasoning configuration: an ordered list of models tried in sequence.
 *
 * <p>Each reasoning step tries the first model, rotates to the next on error, and retries
 * up to {@code maxAttempts} across the pool. Position determines priority. Between attempts
 * it waits with exponential backoff + jitter, growing from {@code retryBackoff} up to
 * {@code retryBackoffMax}, so a provider rate-limit/overload spike can clear instead of being
 * hammered.
 *
 * @param maxAttempts     retries per reasoning step; defaults to the total model count when unset
 * @param retryBackoff    initial wait before the first retry; defaults to {@link #DEFAULT_RETRY_BACKOFF}
 * @param retryBackoffMax cap on the backoff wait; defaults to {@link #DEFAULT_RETRY_BACKOFF_MAX}
 * @param models          ordered model configurations; first entry has highest priority
 */
@ConfigurationProperties(prefix = "prism.reasoning")
public record ReasoningProperties(Integer maxAttempts, Duration retryBackoff, Duration retryBackoffMax,
                                  List<ModelConfig> models) {

    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(500);
    public static final Duration DEFAULT_RETRY_BACKOFF_MAX = Duration.ofSeconds(8);

    /** The configured initial backoff, or {@link #DEFAULT_RETRY_BACKOFF} when unset. */
    public Duration retryBackoffOrDefault() {
        return retryBackoff != null ? retryBackoff : DEFAULT_RETRY_BACKOFF;
    }

    /** The configured max backoff, or {@link #DEFAULT_RETRY_BACKOFF_MAX} when unset. */
    public Duration retryBackoffMaxOrDefault() {
        return retryBackoffMax != null ? retryBackoffMax : DEFAULT_RETRY_BACKOFF_MAX;
    }

    /**
     * One model in the pool.
     *
     * <p>Entries with only {@code id} use the auto-configured ChatModel from the active Spring AI
     * starter. Entries with {@code baseUrl} target that OpenAI-compatible endpoint and require a
     * non-blank {@code apiKey} — having one without the other fails at startup.
     *
     * @param id      model id passed to the provider; may be null to use the provider default
     * @param apiKey  API key for an explicit OpenAI-compatible endpoint; required when baseUrl is set
     * @param baseUrl base URL of an OpenAI-compatible endpoint; required when apiKey is set
     */
    public record ModelConfig(String id, String apiKey, String baseUrl) {
        public ModelConfig {
            boolean hasBaseUrl = baseUrl != null && !baseUrl.isBlank();
            boolean hasApiKey  = apiKey  != null && !apiKey.isBlank();
            if (hasBaseUrl && !hasApiKey)
                throw new IllegalArgumentException(
                        "model '%s' has base-url but no api-key".formatted(id));
            if (hasApiKey && !hasBaseUrl)
                throw new IllegalArgumentException(
                        "model '%s' has api-key but no base-url".formatted(id));
        }
    }
}
