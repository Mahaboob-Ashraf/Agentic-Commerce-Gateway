package dev.agenticcommerce.gateway.agentization.execution;

/** Resolves an approved, non-secret endpoint reference to an ephemeral request credential. */
public interface MerchantCredentialProvider {

    MerchantCredential require(String credentialReference);

    record MerchantCredential(String headerName, String headerValue) {}
}
