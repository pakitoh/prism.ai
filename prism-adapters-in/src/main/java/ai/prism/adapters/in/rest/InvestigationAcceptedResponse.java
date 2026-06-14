package ai.prism.adapters.in.rest;

import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationStatus;

/**
 * REST body for an accepted (async) investigation: the id to poll, and the initial
 * {@code PENDING} status.
 */
public record InvestigationAcceptedResponse(String id, String status) {

    static InvestigationAcceptedResponse of(InvestigationId id) {
        return new InvestigationAcceptedResponse(id.toString(), InvestigationStatus.PENDING.name());
    }
}
