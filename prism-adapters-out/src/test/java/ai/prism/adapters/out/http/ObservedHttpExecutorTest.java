package ai.prism.adapters.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ObservedHttpExecutorTest {

    // A no-op OpenTelemetry exercises the span lifecycle without an SDK; the actual
    // prism.telemetry.query span (backend/uri attributes, nested under prism.investigation)
    // is verified live in Tempo.
    private final OpenTelemetry openTelemetry = OpenTelemetry.noop();

    @Test
    void wrapsTheCallAndReturnsTheBody() {
        HttpExecutor http = new ObservedHttpExecutor(uri -> "{\"ok\":true}", openTelemetry);

        String body = http.get(URI.create("http://prometheus:9090/api/v1/query_range?query=up"));

        assertThat(body).isEqualTo("{\"ok\":true}");
    }

    @Test
    void propagatesFailuresFromTheDelegate() {
        HttpExecutor http = new ObservedHttpExecutor(uri -> {
            throw new RuntimeException("boom");
        }, openTelemetry);

        assertThatThrownBy(() -> http.get(URI.create("http://loki:3100/loki/api/v1/query_range?query=x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }
}
