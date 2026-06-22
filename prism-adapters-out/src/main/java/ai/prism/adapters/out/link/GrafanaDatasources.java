package ai.prism.adapters.out.link;

/**
 * The Grafana datasource UIDs that {@link GrafanaDashboardLinkAdapter} points its
 * deep links at, one per telemetry source. A blank UID disables links for that
 * source (the adapter returns no link rather than an invalid one).
 */
public record GrafanaDatasources(String prometheusUid, String lokiUid, String tempoUid) {
}
