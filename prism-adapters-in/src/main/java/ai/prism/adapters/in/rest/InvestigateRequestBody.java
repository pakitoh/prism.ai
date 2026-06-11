package ai.prism.adapters.in.rest;

import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Instant;
import java.util.Optional;

/**
 * REST request body for triggering an investigation. {@code service}, {@code from}
 * and {@code to} are optional; {@code from}/{@code to} are ISO-8601 instants and
 * must be supplied together to scope a time window.
 */
public record InvestigateRequestBody(String query, String service, String from, String to) {

    InvestigationRequest toDomain() {
        return new InvestigationRequest(
                query,
                Optional.ofNullable(service),
                window(),
                RequestSource.MANUAL);
    }

    private Optional<TimeWindow> window() {
        if (from == null || to == null) {
            return Optional.empty();
        }
        return Optional.of(new TimeWindow(Instant.parse(from), Instant.parse(to)));
    }
}
