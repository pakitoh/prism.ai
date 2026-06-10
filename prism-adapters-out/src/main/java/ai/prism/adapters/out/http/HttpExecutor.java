package ai.prism.adapters.out.http;

import java.net.URI;

/**
 * Minimal seam over HTTP GET, so the telemetry adapters can be tested without a
 * live server. Implementations return the response body on a 2xx and throw
 * {@link HttpRequestException} on any other status or transport failure.
 */
public interface HttpExecutor {

    String get(URI uri);
}
