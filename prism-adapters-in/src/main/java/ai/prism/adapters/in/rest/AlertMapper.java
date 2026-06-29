package ai.prism.adapters.in.rest;

import ai.prism.adapters.in.rest.AlertmanagerWebhook.Alert;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps one firing Alertmanager alert to a domain {@link InvestigationRequest}. Pure translation:
 * the alertname plus summary becomes the investigation query, the {@code service} (or {@code job})
 * label scopes it, and {@code startsAt}/{@code endsAt} become the time window — with a still-firing
 * alert's zero {@code endsAt} resolved to "now" via the injected {@link Clock}.
 */
class AlertMapper {

    private final Clock clock;

    AlertMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    InvestigationRequest toRequest(Alert alert) {
        Map<String, String> labels = alert.labels() != null ? alert.labels() : Map.of();
        Map<String, String> annotations = alert.annotations() != null ? alert.annotations() : Map.of();

        String alertName = labels.getOrDefault("alertname", "alert");
        String summary = annotations.getOrDefault("summary",
                annotations.getOrDefault("description", "(no summary)"));
        String query = alertName + ": " + summary;

        Optional<String> service = Optional.ofNullable(
                labels.getOrDefault("service", labels.get("job")));

        return new InvestigationRequest(query, service, window(alert), RequestSource.ALERT);
    }

    private Optional<TimeWindow> window(Alert alert) {
        Instant from = parse(alert.startsAt());
        if (from == null) {
            return Optional.empty();
        }
        Instant to = parse(alert.endsAt());
        // A still-firing alert sends a zero (year-0001) endsAt, which lands before startsAt;
        // treat that — and any missing/invalid end — as "until now".
        if (to == null || to.isBefore(from)) {
            to = clock.instant();
        }
        return Optional.of(new TimeWindow(from, to));
    }

    private static Instant parse(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(rfc3339);
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
