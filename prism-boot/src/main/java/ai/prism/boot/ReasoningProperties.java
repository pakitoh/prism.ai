package ai.prism.boot;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reasoning configuration: the ordered list of model ids and the selection
 * strategy. With {@code strategy: fallback}, the first model is primary and the
 * rest are tried in order on failure.
 *
 * @param strategy selection strategy (currently only {@code fallback})
 * @param models   ordered model ids; first is primary
 */
@ConfigurationProperties(prefix = "prism.reasoning")
public record ReasoningProperties(String strategy, List<String> models) {
}
