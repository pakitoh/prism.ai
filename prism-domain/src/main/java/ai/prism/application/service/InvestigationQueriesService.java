package ai.prism.application.service;

import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-side application service: serves investigation lookups from the
 * {@link InvestigationRepository}, keeping inbound adapters off the outbound port.
 */
public class InvestigationQueriesService implements InvestigationQueriesUseCase {

    private final InvestigationRepository repository;

    public InvestigationQueriesService(InvestigationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<Investigation> findById(InvestigationId id) {
        return repository.findById(id);
    }

    @Override
    public List<Investigation> recent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return repository.recent(limit);
    }
}
