package ai.prism.boot;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reasoning configuration: the ordered list of (Gemini) model ids, the retry
 * budget, the selection strategy, and an optional cross-provider Groq fallback.
 * Each reasoning step retries up to {@code maxAttempts}, rotating to a different
 * model on each error; the Groq fallback (when a key is set) joins the rotation
 * after the Gemini models.
 *
 * @param strategy    selection strategy (currently only {@code fallback})
 * @param maxAttempts retries per reasoning step; defaults to the model count if unset
 * @param models      ordered Gemini model ids; first is primary
 * @param groq        optional Groq (OpenAI-compatible) fallback; inactive without an api key
 */
@ConfigurationProperties(prefix = "prism.reasoning")
public record ReasoningProperties(String strategy, Integer maxAttempts, List<String> models, Groq groq) {

    /**
     * Groq fallback config. Groq exposes an OpenAI-compatible API, so it is reached
     * via Spring AI's OpenAI client pointed at {@code baseUrl}.
     *
     * @param apiKey  Groq API key; the fallback is inactive when blank
     * @param model   Groq model id (e.g. {@code llama-3.3-70b-versatile})
     * @param baseUrl OpenAI-compatible base URL (e.g. {@code https://api.groq.com/openai})
     */
    public record Groq(String apiKey, String model, String baseUrl) {
    }
}
