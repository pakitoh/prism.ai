package ai.prism.adapters.out.telemetry;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Small helpers shared by the telemetry adapters for building query URLs.
 */
final class TelemetryUris {

    private TelemetryUris() {
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Strips a single trailing slash so paths can be appended cleanly. */
    static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
