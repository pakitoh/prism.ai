package ai.prism.application.port.out;

import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import java.util.Optional;

/**
 * Outbound port for persisting and loading {@link Investigation} aggregates.
 */
public interface InvestigationRepository {

    void save(Investigation investigation);

    Optional<Investigation> findById(InvestigationId id);
}
