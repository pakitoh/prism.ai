package ai.prism.adapters.in.mcp;

import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import ai.prism.domain.investigation.RequestSource;
import ai.prism.domain.investigation.TimeWindow;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Inbound MCP adapter: exposes prism.ai's investigation use cases as MCP tools.
 * Each {@code @Tool} method is converted to an MCP tool by the Spring AI MCP server
 * and served over Streamable HTTP. Translation only — it drives the same command and
 * query ports the REST adapter uses, and holds no business logic.
 */
public class InvestigationMcpTools {

    private static final int DEFAULT_RECENT_LIMIT = 20;

    private final InvestigationCommandsUseCase commands;
    private final InvestigationQueriesUseCase queries;

    public InvestigationMcpTools(InvestigationCommandsUseCase commands, InvestigationQueriesUseCase queries) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.queries = Objects.requireNonNull(queries, "queries must not be null");
    }

    @Tool(name = "investigate", description = """
            Start a root-cause investigation. Runs asynchronously and returns immediately with an
            id; poll get_investigation with that id for status and the final finding.""")
    public AcceptedInvestigation investigate(
            @ToolParam(description = "What to investigate, e.g. 'why is checkout-service erroring?'") String query,
            @ToolParam(required = false, description = "Service to scope the investigation to") String service,
            @ToolParam(required = false, description = "Window start as an ISO-8601 UTC instant") String from,
            @ToolParam(required = false, description = "Window end as an ISO-8601 UTC instant") String to) {
        InvestigationRequest request = new InvestigationRequest(
                query,
                Optional.ofNullable(blankToNull(service)),
                window(from, to),
                RequestSource.MANUAL);
        InvestigationId id = commands.submit(request);
        return new AcceptedInvestigation(id.toString(), InvestigationStatus.PENDING.name());
    }

    @Tool(name = "get_investigation",
            description = "Fetch the current status and result of an investigation by its id.")
    public InvestigationView getInvestigation(
            @ToolParam(description = "Investigation id returned by investigate") String id) {
        return queries.findById(InvestigationId.of(id))
                .map(InvestigationView::from)
                .orElseGet(() -> InvestigationView.notFound(id));
    }

    @Tool(name = "list_recent_investigations",
            description = "List the most recent investigations, newest first — useful for spotting patterns.")
    public List<InvestigationView> listRecentInvestigations(
            @ToolParam(required = false, description = "Maximum number to return (default 20)") Integer limit) {
        int effective = limit == null || limit < 1 ? DEFAULT_RECENT_LIMIT : limit;
        return queries.recent(effective).stream().map(InvestigationView::from).toList();
    }

    private static Optional<TimeWindow> window(String from, String to) {
        if (blankToNull(from) == null || blankToNull(to) == null) {
            return Optional.empty();
        }
        return Optional.of(new TimeWindow(Instant.parse(from), Instant.parse(to)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** MCP result of {@code investigate}: the id to poll and the initial status. */
    public record AcceptedInvestigation(String id, String status) {
    }

    /** MCP view of an investigation's status and (if any) finding. */
    public record InvestigationView(
            String id,
            String status,
            String rootCause,
            String recommendedAction,
            String confidence,
            int signalsGathered,
            String failureReason) {

        static InvestigationView from(Investigation investigation) {
            Finding finding = investigation.finding().orElse(null);
            return new InvestigationView(
                    investigation.id().toString(),
                    investigation.status().name(),
                    finding != null ? finding.rootCause() : null,
                    finding != null ? finding.recommendedAction() : null,
                    finding != null ? finding.confidence().name() : null,
                    investigation.signals().size(),
                    investigation.failureReason().orElse(null));
        }

        static InvestigationView notFound(String id) {
            return new InvestigationView(id, "NOT_FOUND", null, null, null, 0, null);
        }
    }
}
