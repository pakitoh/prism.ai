package ai.prism.adapters.out.http;

import java.net.URI;

/**
 * Test double that records the requested URI and returns a canned body, so the
 * telemetry adapters can be tested without a live server.
 */
public class RecordingHttpExecutor implements HttpExecutor {

    public URI lastUri;
    public String response = "{\"status\":\"success\"}";
    RuntimeException toThrow;

    @Override
    public String get(URI uri) {
        this.lastUri = uri;
        if (toThrow != null) {
            throw toThrow;
        }
        return response;
    }

    public static RecordingHttpExecutor failing(String message) {
        RecordingHttpExecutor executor = new RecordingHttpExecutor();
        executor.toThrow = new HttpRequestException(message);
        return executor;
    }
}
