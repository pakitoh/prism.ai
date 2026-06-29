package ai.prism.boot.configuration;

import ai.prism.adapters.in.rest.AlertApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the inbound REST adapter's cross-cutting concerns. The investigation and alert
 * controllers are component-scanned; this adds the shared-secret guard on the externally
 * facing alert webhook endpoint (blank key = open, for local dev — like the MCP key).
 */
@Configuration
class RestConfiguration {

    @Bean
    FilterRegistrationBean<AlertApiKeyFilter> alertApiKeyFilter(@Value("${prism.alerts.api-key:}") String apiKey) {
        FilterRegistrationBean<AlertApiKeyFilter> registration =
                new FilterRegistrationBean<>(new AlertApiKeyFilter(apiKey));
        registration.addUrlPatterns("/alerts", "/alerts/*");
        return registration;
    }
}
