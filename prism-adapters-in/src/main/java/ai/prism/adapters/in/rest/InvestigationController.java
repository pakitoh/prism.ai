package ai.prism.adapters.in.rest;

import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.application.port.out.DashboardLinkPort;
import ai.prism.domain.investigation.InvestigationId;
import io.opentelemetry.api.trace.Span;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound REST adapter. Investigations run asynchronously: {@code POST} accepts the
 * request and returns {@code 202 Accepted} with the id, clients poll
 * {@code GET /investigations/{id}} for status and result, and {@code GET /investigations}
 * lists recent ones. Translation only — the use case and query ports do the work.
 */
@RestController
@RequestMapping("/investigations")
public class InvestigationController {

    private static final Logger log = LoggerFactory.getLogger(InvestigationController.class);

    private static final int DEFAULT_RECENT_LIMIT = 20;

    private final InvestigationCommandsUseCase investigateCommands;
    private final InvestigationQueriesUseCase investigationQueries;
    private final DashboardLinkPort dashboardLinks;

    public InvestigationController(InvestigationCommandsUseCase investigateCommands,
                                   InvestigationQueriesUseCase investigationQueries,
                                   DashboardLinkPort dashboardLinks) {
        this.investigateCommands = Objects.requireNonNull(investigateCommands, "investigateCommands must not be null");
        this.investigationQueries = Objects.requireNonNull(investigationQueries, "investigationQueries must not be null");
        this.dashboardLinks = Objects.requireNonNull(dashboardLinks, "dashboardLinks must not be null");
    }

    @PostMapping
    public ResponseEntity<InvestigationAcceptedResponse> investigate(@RequestBody InvestigateRequestBody body) {
        InvestigationId id = investigateCommands.submit(body.toDomain());
        // Stamp the spawned id on the current request span so the request trace links to the
        // (async) investigation trace (queryable by investigation.id, which both spans carry).
        Span.current().setAttribute("investigation.id", id.toString());
        log.debug("POST /investigations accepted: id={} service={} query=\"{}\"",
                id, body.service() == null ? "-" : body.service(), body.query());
        return ResponseEntity.accepted().body(InvestigationAcceptedResponse.of(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvestigationResponse> get(@PathVariable String id) {
        return investigationQueries.findById(InvestigationId.of(id))
                .map(investigation -> InvestigationResponse.from(investigation, dashboardLinks))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<InvestigationResponse> recent(@RequestParam(defaultValue = "" + DEFAULT_RECENT_LIMIT) int limit) {
        return investigationQueries.recent(limit).stream()
                .map(investigation -> InvestigationResponse.from(investigation, dashboardLinks))
                .toList();
    }
}
