package ai.prism.boot.configuration;

import ai.prism.adapters.in.mcp.InvestigationMcpTools;
import ai.prism.adapters.in.mcp.McpApiKeyFilter;
import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.application.port.out.DashboardLinkPort;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the inbound MCP server: the investigation tools (exposed over Streamable HTTP
 * by the Spring AI MCP server starter) and the API-key guard on the MCP endpoint.
 */
@Configuration
class McpConfiguration {

    @Bean
    InvestigationMcpTools investigationMcpTools(InvestigationCommandsUseCase commands,
                                                InvestigationQueriesUseCase queries,
                                                DashboardLinkPort dashboardLinks,
                                                ObservationRegistry observationRegistry) {
        return new InvestigationMcpTools(commands, queries, dashboardLinks, observationRegistry);
    }

    // The MCP server autoconfig converts ToolCallbackProvider beans into MCP tools.
    @Bean
    ToolCallbackProvider investigationToolCallbacks(InvestigationMcpTools investigationMcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(investigationMcpTools).build();
    }

    @Bean
    FilterRegistrationBean<McpApiKeyFilter> mcpApiKeyFilter(@Value("${prism.mcp.api-key:}") String apiKey) {
        FilterRegistrationBean<McpApiKeyFilter> registration =
                new FilterRegistrationBean<>(new McpApiKeyFilter(apiKey));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        return registration;
    }
}
