package ai.prism.adapters.in.rest;

import ai.prism.application.port.out.DashboardLinkPort;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.Signal;
import java.util.List;

/**
 * REST view of a completed investigation. Each gathered signal is exposed with a
 * deep link into Grafana, and {@code primaryLink} is the single link the model
 * nominated as best evidencing the root cause (the "headline" to look at first).
 */
public record InvestigationResponse(
        String id,
        String status,
        FindingResponse finding,
        String failureReason,
        int signalsGathered,
        List<SignalLinkResponse> signals,
        String primaryLink) {

    record FindingResponse(String rootCause, String evidence, String recommendedAction, String confidence) {

        static FindingResponse from(Finding finding) {
            return new FindingResponse(
                    finding.rootCause(),
                    finding.evidence(),
                    finding.recommendedAction(),
                    finding.confidence().name());
        }
    }

    /** A gathered signal with its Grafana deep link ({@code link} is null when none exists). */
    record SignalLinkResponse(String type, String query, String link) {
    }

    static InvestigationResponse from(Investigation investigation, DashboardLinkPort links) {
        FindingResponse finding = investigation.finding().map(FindingResponse::from).orElse(null);
        List<SignalLinkResponse> signals = investigation.signals().stream()
                .map(signal -> new SignalLinkResponse(signal.type().name(), signal.query(), linkOf(signal, links)))
                .toList();
        return new InvestigationResponse(
                investigation.id().toString(),
                investigation.status().name(),
                finding,
                investigation.failureReason().orElse(null),
                signals.size(),
                signals,
                primaryLink(investigation, signals));
    }

    private static String linkOf(Signal signal, DashboardLinkPort links) {
        return links.dashboardLink(signal).map(java.net.URI::toString).orElse(null);
    }

    /** The link of the model-nominated key signal, if any and within range. */
    private static String primaryLink(Investigation investigation, List<SignalLinkResponse> signals) {
        return investigation.finding()
                .flatMap(f -> f.keySignalIndex().stream().boxed().findFirst())
                .filter(i -> i >= 0 && i < signals.size())
                .map(i -> signals.get(i).link())
                .orElse(null);
    }
}
