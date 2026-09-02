package dev.agenticcommerce.gateway.demo;

import dev.agenticcommerce.gateway.agentization.execution.EnvironmentMerchantCredentialProvider;
import dev.agenticcommerce.gateway.agentization.execution.MerchantCredentialProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Application-level machine authentication for the public demo merchant surface. */
@Component
public class DemoMerchantApiAuthenticationFilter extends OncePerRequestFilter {

    private static final String DEMO_PATH_PREFIX = "/api/demo-merchants/";
    private final MerchantCredentialProvider credentials;

    public DemoMerchantApiAuthenticationFilter(MerchantCredentialProvider credentials) {
        this.credentials = credentials;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(DEMO_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected;
        try {
            expected = credentials.require(EnvironmentMerchantCredentialProvider.DEMO_CREDENTIAL_REFERENCE)
                    .headerValue();
        } catch (RuntimeException unavailable) {
            reject(response);
            return;
        }
        String supplied = request.getHeader("Authorization");
        if (supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"demo_merchant_authentication_required\"}");
    }
}
