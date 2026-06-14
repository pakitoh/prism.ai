package ai.prism.adapters.out.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Small URI helpers shared by the HTTP-backed outbound adapters: encoding query
 * parameter values and normalizing a base URL before appending paths.
 */
public final class HttpUris {

    private HttpUris() {
    }

    /** URL-encodes a query parameter value (UTF-8). */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Strips a single trailing slash so paths can be appended cleanly. */
    public static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
