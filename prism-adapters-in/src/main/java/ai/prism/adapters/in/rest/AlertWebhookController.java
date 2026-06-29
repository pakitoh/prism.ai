package ai.prism.adapters.in.rest;

import ai.prism.adapters.in.rest.AlertmanagerWebhook.Alert;
import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound REST adapter for alert-driven investigations. Accepts an Alertmanager-format webhook
 * (Prometheus Alertmanager, or Grafana's built-in alerting with a webhook contact point), maps
 * each firing alert to an {@link InvestigationRequest} and submits it through the same async
 * {@link InvestigationCommandsUseCase} the REST and MCP adapters drive — so the loop, tracing and
 * memory all apply unchanged. Resolved alerts are skipped. Translation only; no business logic.
 */
@RestController
@RequestMapping("/alerts")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    private final InvestigationCommandsUseCase commands;
    private final AlertMapper mapper;

    public AlertWebhookController(InvestigationCommandsUseCase commands, Clock clock) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.mapper = new AlertMapper(Objects.requireNonNull(clock, "clock must not be null"));
    }

    @PostMapping
    public ResponseEntity<AlertsAcceptedResponse> receive(@RequestBody AlertmanagerWebhook webhook) {
        List<Alert> alerts = webhook.alerts() != null ? webhook.alerts() : List.of();
        List<String> accepted = alerts.stream()
                .filter(Alert::isFiring)
                .map(this::submit)
                .toList();
        log.info("Alert webhook: {} alert(s), {} firing -> {} investigation(s) spawned",
                alerts.size(), accepted.size(), accepted.size());
        return ResponseEntity.accepted().body(new AlertsAcceptedResponse(accepted));
    }

    private String submit(Alert alert) {
        InvestigationRequest request = mapper.toRequest(alert);
        InvestigationId id = commands.submit(request);
        log.debug("Alert accepted: id={} service={} query=\"{}\"",
                id, request.service().orElse("-"), request.query());
        return id.toString();
    }
}
