package ai.prism.adapters.in.rest;

import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;

/**
 * REST view of a completed investigation.
 */
public record InvestigationResponse(
        String id,
        String status,
        FindingResponse finding,
        String failureReason,
        int signalsGathered) {

    record FindingResponse(String rootCause, String evidence, String recommendedAction, String confidence) {

        static FindingResponse from(Finding finding) {
            return new FindingResponse(
                    finding.rootCause(),
                    finding.evidence(),
                    finding.recommendedAction(),
                    finding.confidence().name());
        }
    }

    static InvestigationResponse from(Investigation investigation) {
        FindingResponse finding = investigation.finding().map(FindingResponse::from).orElse(null);
        return new InvestigationResponse(
                investigation.id().toString(),
                investigation.status().name(),
                finding,
                investigation.failureReason().orElse(null),
                investigation.signals().size());
    }
}
