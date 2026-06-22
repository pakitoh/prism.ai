package ai.prism.boot.configuration;

import ai.prism.adapters.out.link.GrafanaDashboardLinkAdapter;
import ai.prism.adapters.out.link.GrafanaDatasources;
import ai.prism.application.port.out.DashboardLinkPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbound {@link DashboardLinkPort} that turns a signal into a Grafana
 * Explore deep link. Pure URL construction — no HTTP executor, no network — so it
 * needs only the Grafana base URL and the datasource UIDs.
 */
@Configuration
class LinkConfiguration {

    @Bean
    DashboardLinkPort dashboardLinkPort(
            @Value("${prism.grafana.url}") String grafanaUrl,
            @Value("${prism.grafana.prometheus-uid}") String prometheusUid,
            @Value("${prism.grafana.loki-uid}") String lokiUid,
            @Value("${prism.grafana.tempo-uid}") String tempoUid,
            JsonMapper jsonMapper) {
        return new GrafanaDashboardLinkAdapter(
                grafanaUrl, new GrafanaDatasources(prometheusUid, lokiUid, tempoUid), jsonMapper);
    }
}
