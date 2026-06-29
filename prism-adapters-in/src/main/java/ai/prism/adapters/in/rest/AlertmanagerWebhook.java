package ai.prism.adapters.in.rest;

import java.util.List;
import java.util.Map;

/**
 * The Alertmanager (v4) webhook payload — the same shape Grafana's built-in alerting POSTs to a
 * webhook contact point. One delivery carries a group of {@link Alert}s. Only the fields prism
 * consumes are declared; Spring Boot's Jackson ignores the rest of the (rich) payload.
 */
public record AlertmanagerWebhook(String status, List<Alert> alerts) {

    /** One alert in the group. {@code status} is {@code "firing"} or {@code "resolved"}. */
    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String startsAt,
            String endsAt,
            String fingerprint,
            String generatorURL) {

        boolean isFiring() {
            return "firing".equalsIgnoreCase(status);
        }
    }
}
