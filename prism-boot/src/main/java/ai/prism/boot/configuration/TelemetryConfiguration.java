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
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbound telemetry ports (Prometheus, Loki, Tempo) over a single
 * instrumented HTTP executor.
 */
@Configuration
class TelemetryConfiguration {

    @Bean
    HttpExecutor httpExecutor(OpenTelemetry openTelemetry) {
        // Instrument every outbound telemetry query at the single choke point.
        return new ObservedHttpExecutor(JdkHttpExecutor.create(), openTelemetry);
    }

    @Bean
    MetricsPort metricsPort(HttpExecutor http, Clock clock,
                            @Value("${prism.telemetry.prometheus-url}") String baseUrl) {
        return new PrometheusAdapter(http, baseUrl, clock);
    }

    @Bean
    LogsPort logsPort(HttpExecutor http, Clock clock, JsonMapper jsonMapper,
                      @Value("${prism.telemetry.loki-url}") String baseUrl) {
        return new LokiAdapter(http, baseUrl, clock, jsonMapper);
    }

    @Bean
    TracingPort tracingPort(HttpExecutor http, Clock clock,
                            @Value("${prism.telemetry.tempo-url}") String baseUrl) {
        return new TempoAdapter(http, baseUrl, clock);
    }
}
