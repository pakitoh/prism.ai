package ai.prism.boot.configuration;

import ai.prism.adapters.out.http.HttpExecutor;
import ai.prism.adapters.out.http.JdkHttpExecutor;
import ai.prism.adapters.out.http.ObservedHttpExecutor;
import ai.prism.adapters.out.logs.LokiAdapter;
import ai.prism.adapters.out.metrics.PrometheusAdapter;
import ai.prism.adapters.out.tracing.TempoAdapter;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.TracingPort;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the outbound telemetry ports (Prometheus, Loki, Tempo) over a single
 * instrumented HTTP executor.
 */
@Configuration
class TelemetryConfiguration {

    @Bean
    HttpExecutor httpExecutor(ObservationRegistry observationRegistry) {
        // Instrument every outbound telemetry query at the single choke point.
        return new ObservedHttpExecutor(JdkHttpExecutor.create(), observationRegistry);
    }

    @Bean
    MetricsPort metricsPort(HttpExecutor http, Clock clock,
                            @Value("${prism.telemetry.prometheus-url}") String baseUrl) {
        return new PrometheusAdapter(http, baseUrl, clock);
    }

    @Bean
    LogsPort logsPort(HttpExecutor http, Clock clock,
                      @Value("${prism.telemetry.loki-url}") String baseUrl) {
        return new LokiAdapter(http, baseUrl, clock);
    }

    @Bean
    TracingPort tracingPort(HttpExecutor http, Clock clock,
                            @Value("${prism.telemetry.tempo-url}") String baseUrl) {
        return new TempoAdapter(http, baseUrl, clock);
    }
}
