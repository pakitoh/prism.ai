package ai.prism.adapters.out.http;

import ai.prism.application.port.out.TelemetryException;
import java.net.URI;

/**
 * Minimal seam over HTTP GET, so the telemetry adapters can be tested without a
 * live server. Implementations return the response body on a 2xx and throw a
 * {@link TelemetryException} (classified by {@link TelemetryException.Kind}) on
 * any other status or transport failure.
 */
public interface HttpExecutor {

    String get(URI uri);
}
