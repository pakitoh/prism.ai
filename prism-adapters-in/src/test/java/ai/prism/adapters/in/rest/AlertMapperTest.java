package ai.prism.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.adapters.in.rest.AlertmanagerWebhook.Alert;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertMapperTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    private final AlertMapper mapper = new AlertMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void mapsAFiringAlertToAnAlertSourcedRequest() {
        Alert alert = new Alert(
                "firing",
                Map.of("alertname", "HighErrorRate", "service", "checkout"),
                Map.of("summary", "5xx rate above 10%"),
                "2026-06-16T11:30:00Z",
                "2026-06-16T11:45:00Z",
                "abc123",
                "http://prometheus/graph");

        InvestigationRequest request = mapper.toRequest(alert);

        assertThat(request.query()).isEqualTo("HighErrorRate: 5xx rate above 10%");
        assertThat(request.service()).contains("checkout");
        assertThat(request.source()).isEqualTo(RequestSource.ALERT);
        assertThat(request.window()).hasValueSatisfying(w -> {
            assertThat(w.from()).isEqualTo(Instant.parse("2026-06-16T11:30:00Z"));
            assertThat(w.to()).isEqualTo(Instant.parse("2026-06-16T11:45:00Z"));
        });
    }

    @Test
    void fallsBackToTheJobLabelWhenServiceIsAbsent() {
        Alert alert = new Alert("firing",
                Map.of("alertname", "Latency", "job", "payments"),
                Map.of("summary", "p99 high"),
                "2026-06-16T11:30:00Z", "2026-06-16T11:45:00Z", "f", "g");

        assertThat(mapper.toRequest(alert).service()).contains("payments");
    }

    @Test
    void hasNoServiceWhenNeitherServiceNorJobLabelIsPresent() {
        Alert alert = new Alert("firing",
                Map.of("alertname", "Latency"),
                Map.of("summary", "p99 high"),
                "2026-06-16T11:30:00Z", "2026-06-16T11:45:00Z", "f", "g");

        assertThat(mapper.toRequest(alert).service()).isEmpty();
    }

    @Test
    void resolvesAStillFiringZeroEndsAtToNow() {
        // Alertmanager sends a zero (year-0001) endsAt while an alert is still firing.
        Alert alert = new Alert("firing",
                Map.of("alertname", "HighErrorRate"),
                Map.of("summary", "still firing"),
                "2026-06-16T11:30:00Z", "0001-01-01T00:00:00Z", "f", "g");

        assertThat(mapper.toRequest(alert).window()).hasValueSatisfying(w -> {
            assertThat(w.from()).isEqualTo(Instant.parse("2026-06-16T11:30:00Z"));
            assertThat(w.to()).isEqualTo(NOW);
        });
    }

    @Test
    void usesADescriptionOrPlaceholderWhenSummaryIsMissingAndNeverBlanksTheQuery() {
        Alert withDescription = new Alert("firing",
                Map.of("alertname", "DiskFull"),
                Map.of("description", "node-1 disk at 95%"),
                "2026-06-16T11:30:00Z", "0001-01-01T00:00:00Z", "f", "g");
        assertThat(mapper.toRequest(withDescription).query()).isEqualTo("DiskFull: node-1 disk at 95%");

        Alert bare = new Alert("firing", Map.of(), Map.of(), null, null, null, null);
        InvestigationRequest request = mapper.toRequest(bare);
        assertThat(request.query()).isEqualTo("alert: (no summary)");
        assertThat(request.window()).isEmpty();
    }
}
