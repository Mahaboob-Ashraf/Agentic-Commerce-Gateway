package dev.agenticcommerce.gateway.onboarding;

import java.time.Instant;
import java.util.UUID;

/** Merchant-specific delegated account-link boundary. Raw credentials are input-only. */
public interface MerchantCustomerLinkProvider {
    LinkResult exchange(UUID merchantId, String username, char[] password);

    record LinkResult(boolean valid, String externalCustomerReference,
            String delegatedCredentialReference, String method, Instant expiresAt, String failureCode) {}
}
