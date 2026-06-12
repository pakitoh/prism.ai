package ai.prism.adapters.out.knowledge;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.application.port.out.InvestigationKnowledgeBase;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that stores each concluded investigation in the knowledge base, so
 * later investigations can recall it. Keeps the use case itself unaware of
 * memory — composes alongside the observability decorator at the composition root.
 *
 * <p>Remembering is best-effort: a failure to store (e.g. embedding rate limit or
 * DB error) is logged but never fails an otherwise-successful investigation.
 */
public class RememberingInvestigateUseCase implements InvestigateUseCase {

    private static final Logger log = LoggerFactory.getLogger(RememberingInvestigateUseCase.class);

    private final InvestigateUseCase delegate;
    private final InvestigationKnowledgeBase knowledgeBase;

    public RememberingInvestigateUseCase(InvestigateUseCase delegate, InvestigationKnowledgeBase knowledgeBase) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase must not be null");
    }

    @Override
    public Investigation handle(InvestigationRequest request) {
        Investigation investigation = delegate.handle(request);
        if (investigation.status() == InvestigationStatus.CONCLUDED) {
            try {
                knowledgeBase.remember(investigation);
            } catch (RuntimeException failure) {
                log.warn("Failed to remember investigation {}: {}", investigation.id(), failure.toString());
            }
        }
        return investigation;
    }
}
