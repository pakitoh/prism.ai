package ai.prism.adapters.out.http;

/**
 * Raised when an HTTP query against a telemetry backend fails (non-2xx status or
 * transport error). Propagates out of the telemetry adapters; the investigation
 * loop catches it and fails the investigation.
 */
public class HttpRequestException extends RuntimeException {

    public HttpRequestException(String message) {
        super(message);
    }

    public HttpRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
