package dev.agenticcommerce.gateway.demo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DemoMerchantModels {
    private DemoMerchantModels() {}

    public record Profile(UUID merchantId, String merchantKey, String displayName, String profileCode,
            boolean cancellationAllowed, boolean returnsAllowed, boolean perishableReturnsAllowed,
            int deliveryMinutes) {}
    public record InventoryProduct(UUID productId, UUID catalogueVersionId, String merchantSku,
            String canonicalName, String variant, String category, long priceMinor, String currency,
            long availableQuantity) {}
    public record Order(UUID id, UUID merchantId, String operationId, String merchantOrderId,
            String requestHash, String customerReference, tools.jackson.databind.JsonNode lineItems,
            long totalMinor, String currency, String state, boolean stockReleased, Instant createdAt) {}
    public record OrderLine(UUID productId, String merchantSku, int quantity, long unitAmountMinor,
            long lineAmountMinor) {}
    public record BootstrapSummary(boolean reused, boolean buyerCreated, UUID buyerActorId,
            int merchants, int merchantsCreated, int merchantsReused, int amazingProducts,
            int freshBasketProducts, int primaryFacts, int embeddingsReady, int lexicalFallbacks,
            int capabilitiesMapped, int capabilitiesReady, int manifests, int buyerLinks,
            String merchantPublicBaseUrl, String deploymentPrecondition, List<String> blockers) {}
}
