package ai.prism.application.port.out;

import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.Signal;

/**
 * Outbound port for the growing memory of past investigations.
 *
 * <p>Concluded investigations are {@link #remember(Investigation) remembered}, and
 * during a later investigation the model can {@link #findSimilar(String) recall}
 * the most similar ones — surfacing recurring failure patterns without anyone
 * authoring runbooks by hand.
 */
public interface InvestigationKnowledgeBase {

    /** Stores a concluded investigation so future ones can recall it. No-op if not concluded. */
    void remember(Investigation investigation);

    /** Returns the most similar past investigations as a single {@code MEMORY} {@link Signal}. */
    Signal findSimilar(String query);
}
