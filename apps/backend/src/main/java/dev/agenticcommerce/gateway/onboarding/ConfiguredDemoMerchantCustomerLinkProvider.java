package dev.agenticcommerce.gateway.onboarding;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.nio.CharBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional trusted-demo exchange; production merchants replace this with OAuth/delegated adapters. */
@Component
public class ConfiguredDemoMerchantCustomerLinkProvider implements MerchantCustomerLinkProvider {
    private final boolean enabled;
    private final String expectedUsername;
    private final String expectedPassword;
    private final CanonicalJsonService canonical;

    public ConfiguredDemoMerchantCustomerLinkProvider(
            @Value("${merchant-link.demo.enabled:false}") boolean enabled,
            @Value("${merchant-link.demo.username:}") String expectedUsername,
            @Value("${merchant-link.demo.password:}") String expectedPassword,
            CanonicalJsonService canonical) {
        this.enabled = enabled;
        this.expectedUsername = expectedUsername;
        this.expectedPassword = expectedPassword;
        this.canonical = canonical;
    }

    @Override
    public LinkResult exchange(UUID merchantId, String username, char[] password) {
        boolean valid = enabled && username != null && username.equals(expectedUsername)
                && expectedPassword.contentEquals(CharBuffer.wrap(password));
        if (!valid) return new LinkResult(false, null, null, "TRUSTED_DEMO", null,
                "MERCHANT_CREDENTIALS_INVALID");
        String identity = canonical.hashText("demo-customer-v1|" + merchantId + "|" + username);
        return new LinkResult(true, "customer_" + identity.substring(0, 24),
                "credential://trusted-demo/" + identity.substring(0, 32), "TRUSTED_DEMO",
                Instant.now().plus(30, ChronoUnit.DAYS), null);
    }
}
