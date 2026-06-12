package ai.prism.domain.investigation;

/**
 * Where an {@link InvestigationRequest} originated.
 *
 * <ul>
 *   <li>{@code MANUAL} — a developer asked, via the REST API or the MCP server.</li>
 *   <li>{@code ALERT}  — an alert arrived to the system.</li>
 * </ul>
 */
public enum RequestSource {
    MANUAL,
    ALERT
}
