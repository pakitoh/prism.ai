package ai.prism.adapters.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.adapters.out.http.HttpExecutor;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ObservedHttpExecutorTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void recordsAQueryObservationTaggedByBackendAndReturnsTheBody() {
        HttpExecutor delegate = uri -> "{\"ok\":true}";
        HttpExecutor http = new ObservedHttpExecutor(delegate, registry);

        String body = http.get(URI.create("http://prometheus:9090/api/v1/query_range?query=up"));

        assertThat(body).isEqualTo("{\"ok\":true}");
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.telemetry.query")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("backend", "prometheus");
    }

    @Test
    void tagsLokiAndTempoByPort() {
        HttpExecutor http = new ObservedHttpExecutor(uri -> "{}", registry);

        http.get(URI.create("http://loki:3100/loki/api/v1/query_range?query=x"));

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("prism.telemetry.query")
                .that()
                .hasLowCardinalityKeyValue("backend", "loki");
    }
}
