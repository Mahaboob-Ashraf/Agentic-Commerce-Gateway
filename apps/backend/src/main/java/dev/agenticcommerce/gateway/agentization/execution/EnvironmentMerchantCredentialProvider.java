package dev.agenticcommerce.gateway.agentization.execution;

import org.springframework.stereotype.Component;

/** Production credential boundary. Only the explicitly allow-listed environment secret is resolvable. */
@Component
public class EnvironmentMerchantCredentialProvider implements MerchantCredentialProvider {

    public static final String DEMO_CREDENTIAL_REFERENCE = "env:DEMO_MERCHANT_API_SECRET";
    private static final String DEMO_SECRET_ENVIRONMENT_VARIABLE = "DEMO_MERCHANT_API_SECRET";

    @Override
    public MerchantCredential require(String credentialReference) {
        if (!DEMO_CREDENTIAL_REFERENCE.equals(credentialReference)) {
            throw new MerchantExecutionException(
                    "ENDPOINT_CREDENTIAL_REFERENCE_UNSUPPORTED",
                    "Approved endpoint credential reference is unsupported");
        }
        String secret = System.getenv(DEMO_SECRET_ENVIRONMENT_VARIABLE);
        if (secret == null || secret.isBlank() || secret.length() > 4096) {
            throw new MerchantExecutionException(
                    "ENDPOINT_CREDENTIAL_UNAVAILABLE",
                    "Approved endpoint credential is unavailable");
        }
        return new MerchantCredential("Authorization", "Bearer " + secret);
    }
}
