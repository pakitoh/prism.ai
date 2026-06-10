package ai.prism.adapters.out.telemetry;

import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.HttpRequestException;
import java.net.URI;

/**
 * Test double that records the requested URI and returns a canned body, so the
 * telemetry adapters can be tested without a live server.
 */
class RecordingHttpExecutor implements HttpExecutor {

    URI lastUri;
    String response = "{\"status\":\"success\"}";
    RuntimeException toThrow;

    @Override
    public String get(URI uri) {
        this.lastUri = uri;
        if (toThrow != null) {
            throw toThrow;
        }
        return response;
    }

    static RecordingHttpExecutor failing(String message) {
        RecordingHttpExecutor executor = new RecordingHttpExecutor();
        executor.toThrow = new HttpRequestException(message);
        return executor;
    }
}
