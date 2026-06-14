package ai.prism.adapters.out.investigation;

import ai.prism.application.port.out.MemoryPort;
import ai.prism.application.service.InvestigationRunner;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationStatus;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that stores each concluded investigation in the knowledge base, so
 * later investigations can recall it. Keeps the runner itself unaware of memory —
 * composes alongside the observability decorator at the composition root, and applies
 * equally to synchronous and background (async) runs.
 *
 * <p>Remembering is best-effort: a failure to store (e.g. embedding rate limit or
 * DB error) is logged but never fails an otherwise-successful investigation.
 */
public class RememberingInvestigationRunner implements InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(RememberingInvestigationRunner.class);

    private final InvestigationRunner delegate;
    private final MemoryPort knowledgeBase;

    public RememberingInvestigationRunner(InvestigationRunner delegate, MemoryPort knowledgeBase) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase must not be null");
    }

    @Override
    public Investigation run(Investigation investigation) {
        Investigation result = delegate.run(investigation);
        if (result.status() == InvestigationStatus.CONCLUDED) {
            try {
                knowledgeBase.remember(result);
            } catch (RuntimeException failure) {
                log.warn("Failed to remember investigation {}: {}", result.id(), failure.toString());
            }
        }
        return result;
    }
}
