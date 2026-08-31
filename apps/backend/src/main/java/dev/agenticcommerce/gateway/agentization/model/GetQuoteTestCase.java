package dev.agenticcommerce.gateway.agentization.model;

public record GetQuoteTestCase(
        String testCaseId,
        int testVersion,
        long expectedAmountPaise,
        String expectedCurrency,
        boolean quoteIdentityRequired,
        String sourceMoneyUnit) {

    public static GetQuoteTestCase canonicalRupeesFixture() {
        return new GetQuoteTestCase(
                "GET_QUOTE_RUPEES_499", 1, 49_900L, "INR", false, "rupees");
    }
}
