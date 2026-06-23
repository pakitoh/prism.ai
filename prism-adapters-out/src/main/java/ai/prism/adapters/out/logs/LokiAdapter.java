package ai.prism.adapters.out.logs;

import ai.prism.application.port.out.LogsPort;
import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.HttpUris;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LogsPort} backed by the Loki HTTP API (`/loki/api/v1/query_range`).
 * Loki expects nanosecond Unix timestamps. The query response is reduced to just
 * the log lines (grouped by stream labels) for the {@link Signal} — Loki's large
 * {@code stats} block is execution metadata the reasoning model does not need.
 */
public class LokiAdapter implements LogsPort {

    private static final int LIMIT = 100;

    private final HttpExecutor http;
    private final String baseUrl;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    public LokiAdapter(HttpExecutor http, String baseUrl, Clock clock, JsonMapper jsonMapper) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.baseUrl = HttpUris.normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    @Override
    public Signal search(String logQl, TimeWindow window) {
        URI uri = URI.create(baseUrl + "/loki/api/v1/query_range"
                + "?query=" + HttpUris.encode(logQl)
                + "&start=" + epochNanos(window.from())
                + "&end=" + epochNanos(window.to())
                + "&limit=" + LIMIT
                + "&direction=backward");
        String body = http.get(uri);
        return Signal.over(SignalType.LOG, logQl, renderLogs(body, window), clock.instant(), window);
    }

    /**
     * Reduces a Loki streams response to its log lines: a header, then per stream its label set
     * and each {@code [timestamp, line]} pair. Drops {@code data.stats}. Best-effort — anything
     * unexpected (a non-streams result type, or unparseable body) falls back to the raw body so
     * no evidence is ever lost.
     */
    private String renderLogs(String body, TimeWindow window) {
        try {
            JsonNode data = jsonMapper.readTree(body).path("data");
            if (!"streams".equals(data.path("resultType").asText())) {
                return body;
            }
            JsonNode result = data.path("result");
            if (!result.isArray() || result.isEmpty()) {
                return "No log lines matched in " + window.from() + "–" + window.to() + ".";
            }
            StringBuilder lines = new StringBuilder();
            int lineCount = 0;
            for (JsonNode stream : result) {
                lines.append(renderLabels(stream.path("stream"))).append('\n');
                for (JsonNode entry : stream.path("values")) {
                    lines.append("  ").append(toTimestamp(entry.path(0).asText()))
                            .append(' ').append(entry.path(1).asText()).append('\n');
                    lineCount++;
                }
            }
            return result.size() + " stream(s), " + lineCount + " line(s):\n" + lines;
        } catch (RuntimeException malformed) {
            return body;
        }
    }

    private static String renderLabels(JsonNode labels) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonNode> label : labels.properties()) {
            if (!first) {
                out.append(", ");
            }
            out.append(label.getKey()).append("=\"").append(label.getValue().asText()).append('"');
            first = false;
        }
        return out.append('}').toString();
    }

    private static String toTimestamp(String epochNanos) {
        try {
            long ns = Long.parseLong(epochNanos);
            return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L).toString();
        } catch (NumberFormatException notANumber) {
            return epochNanos;
        }
    }

    @Override
    public Signal listLabelNames() {
        String body = http.get(URI.create(baseUrl + "/loki/api/v1/labels"));
        return Signal.of(SignalType.SCHEMA, "log labels", body, clock.instant());
    }

    @Override
    public Signal listLabelValues(String label) {
        URI uri = URI.create(baseUrl + "/loki/api/v1/label/" + HttpUris.encode(label) + "/values");
        return Signal.of(SignalType.SCHEMA, "log label values: " + label, http.get(uri), clock.instant());
    }

    private static long epochNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
