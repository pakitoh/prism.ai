package ai.prism.domain.investigation;

import java.util.Objects;
import java.util.Optional;

/**
 * The starting point of an investigation: what to look into, optionally scoped
 * to a service and a time window, and where the request came from.
 */
public record InvestigationRequest(
        String query,
        Optional<String> service,
        Optional<TimeWindow> window,
        RequestSource source) {

    public InvestigationRequest {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        Objects.requireNonNull(service, "service must not be null (use Optional.empty())");
        Objects.requireNonNull(window, "window must not be null (use Optional.empty())");
        Objects.requireNonNull(source, "source must not be null");
    }

    /** A free-text request from a developer, with no service or window scope. */
    public static InvestigationRequest manual(String query) {
        return new InvestigationRequest(query, Optional.empty(), Optional.empty(), RequestSource.MANUAL);
    }
}
