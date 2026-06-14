package ai.prism.application.service;

import ai.prism.domain.investigation.Investigation;

/**
 * Runs a single, already-opened {@link Investigation} to completion — driving the
 * reasoning loop until a conclusion or the step bound, then persisting the result.
 *
 * <p>This is the unit the cross-cutting decorators wrap (observability, memory), so
 * those concerns apply identically whether the investigation is run synchronously
 * (the request thread) or asynchronously (a background worker). Entry-point concerns
 * — opening the aggregate, returning an id, scheduling — live in
 * {@link InvestigationCommandsService}, not here.
 */
public interface InvestigationRunner {

    /**
     * Runs a {@code PENDING} investigation to completion and returns it, either
     * {@code CONCLUDED} with a finding or {@code FAILED} with a reason.
     */
    Investigation run(Investigation investigation);
}
