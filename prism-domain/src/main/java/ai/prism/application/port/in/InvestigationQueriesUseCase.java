package ai.prism.application.port.in;

import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import java.util.List;
import java.util.Optional;

/**
 * Inbound query port: reads investigations for polling and pattern-spotting.
 * Separate from {@link InvestigationCommandsUseCase} (the command side) so inbound adapters
 * (REST polling, MCP {@code get_investigation} / {@code list_recent_investigations})
 * depend only on what they use.
 */
public interface InvestigationQueriesUseCase {

    /** Looks up a single investigation by id, for status/result polling. */
    Optional<Investigation> findById(InvestigationId id);

    /** The most recently created investigations, newest first, capped at {@code limit}. */
    List<Investigation> recent(int limit);
}
