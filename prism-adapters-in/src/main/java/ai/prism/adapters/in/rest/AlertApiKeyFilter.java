package ai.prism.adapters.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the alert webhook endpoint with a static shared secret. When no key is configured the
 * filter is a pass-through (local dev); when one is set, requests must carry a matching
 * {@code X-API-Key} header or receive {@code 401}. Mirrors the MCP endpoint's key guard; register
 * it scoped to {@code /alerts} at the composition root.
 */
public class AlertApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final String expectedApiKey;

    public AlertApiKeyFilter(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedApiKey != null && !expectedApiKey.isBlank()
                && !expectedApiKey.equals(request.getHeader(API_KEY_HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", API_KEY_HEADER);
            return;
        }
        chain.doFilter(request, response);
    }
}
