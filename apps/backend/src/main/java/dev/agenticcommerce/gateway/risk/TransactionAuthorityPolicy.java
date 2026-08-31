package dev.agenticcommerce.gateway.risk;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Central typed P0 transaction-authority thresholds. No model may change these values. */
@Component
public class TransactionAuthorityPolicy {
    private final long autoExecuteMaximumAmountMinor;
    private final Duration proposalTtl;
    private final Duration authorizationTtl;
    private final Duration availabilityMaximumAge;
    private final Duration serviceabilityMaximumAge;
    private final Duration minimumQuoteRemaining;

    public TransactionAuthorityPolicy(
            @Value("${commerce.transaction-authority.auto-execute-maximum-amount-minor:25000}") long autoExecuteMaximumAmountMinor,
            @Value("${commerce.transaction-authority.proposal-ttl:PT5M}") Duration proposalTtl,
            @Value("${commerce.transaction-authority.authorization-ttl:PT5M}") Duration authorizationTtl,
            @Value("${commerce.transaction-authority.availability-maximum-age:PT2M}") Duration availabilityMaximumAge,
            @Value("${commerce.transaction-authority.serviceability-maximum-age:PT5M}") Duration serviceabilityMaximumAge,
            @Value("${commerce.transaction-authority.minimum-quote-remaining:PT30S}") Duration minimumQuoteRemaining) {
        if (autoExecuteMaximumAmountMinor < 0 || proposalTtl.isNegative() || proposalTtl.isZero()
                || authorizationTtl.isNegative() || authorizationTtl.isZero()
                || availabilityMaximumAge.isNegative() || availabilityMaximumAge.isZero()
                || serviceabilityMaximumAge.isNegative() || serviceabilityMaximumAge.isZero()
                || minimumQuoteRemaining.isNegative()) {
            throw new IllegalArgumentException("Transaction authority policy values are invalid");
        }
        this.autoExecuteMaximumAmountMinor = autoExecuteMaximumAmountMinor;
        this.proposalTtl = proposalTtl;
        this.authorizationTtl = authorizationTtl;
        this.availabilityMaximumAge = availabilityMaximumAge;
        this.serviceabilityMaximumAge = serviceabilityMaximumAge;
        this.minimumQuoteRemaining = minimumQuoteRemaining;
    }

    public long autoExecuteMaximumAmountMinor() { return autoExecuteMaximumAmountMinor; }
    public Duration proposalTtl() { return proposalTtl; }
    public Duration authorizationTtl() { return authorizationTtl; }
    public Duration availabilityMaximumAge() { return availabilityMaximumAge; }
    public Duration serviceabilityMaximumAge() { return serviceabilityMaximumAge; }
    public Duration minimumQuoteRemaining() { return minimumQuoteRemaining; }
    public String version() { return "p0-transaction-authority-v1"; }
}
