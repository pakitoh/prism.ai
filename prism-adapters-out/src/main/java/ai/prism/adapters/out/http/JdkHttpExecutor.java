package ai.prism.adapters.out.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link HttpExecutor} backed by the JDK's {@link HttpClient}.
 */
public class JdkHttpExecutor implements HttpExecutor {

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpExecutor(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    }

    public static JdkHttpExecutor create() {
        return new JdkHttpExecutor(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(30));
    }

    @Override
    public String get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new HttpRequestException(
                        "GET " + uri + " returned HTTP " + status + ": " + truncate(response.body()));
            }
            return response.body();
        } catch (IOException transport) {
            throw new HttpRequestException("GET " + uri + " failed: " + transport.getMessage(), transport);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HttpRequestException("GET " + uri + " was interrupted", interrupted);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
