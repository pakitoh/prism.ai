package ai.prism.application.port.out;

import ai.prism.domain.investigation.Signal;
import java.net.URI;
import java.util.Optional;

/**
 * Outbound port that turns a gathered {@link Signal} into a clickable link to the
 * datasource UI (e.g. Grafana Explore), so a human can open the underlying data
 * directly from an investigation's result.
 *
 * <p>Pure presentation enrichment: implementations construct the link from data
 * the signal already carries (datasource type, query, time window) and must
 * <strong>not</strong> perform any network call. The method never throws — a
 * signal with no representable link (e.g. a memory recall, or an unconfigured
 * datasource) returns {@link Optional#empty()}.
 */
public interface DashboardLinkPort {

    Optional<URI> dashboardLink(Signal signal);
}
