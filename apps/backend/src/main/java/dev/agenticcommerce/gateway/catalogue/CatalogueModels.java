package dev.agenticcommerce.gateway.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class CatalogueModels {
    private CatalogueModels() {}

    public enum VersionStatus { DRAFT, PUBLISHED, REJECTED }
    public enum Availability { IN_STOCK, OUT_OF_STOCK, UNKNOWN }
    public enum IdentityOutcome { EXACT, PROBABLE, UNRESOLVED, CONFLICT }
    public enum MatchClassification { VALID_MATCH, RELATED_ALTERNATIVES, NO_TRUSTWORTHY_MATCH }
    public enum GateOutcome { PASS, FAIL, UNKNOWN }
    public enum AllergenState { PRESENT, ABSENT, UNKNOWN, CONFLICT }

    public record CatalogueVersion(UUID id, UUID merchantId, int version, VersionStatus status,
            String sourceFormat, String sourceHash, String contentHash, int accepted, int rejected,
            int enriched, int unresolved, JsonNode evidence, Instant createdAt, Instant publishedAt) {}

    public record ProductInput(String merchantSku, String gtin, String brand, String canonicalName,
            String variant, String sizeStorage, String colour, String category, String description,
            Boolean active, String sourceRecordId, Long priceMinor, String currency,
            Long stockQuantity, Availability availability, String observationSource,
            String sourceVersion, Instant observedAt) {}

    public record Product(UUID id, UUID merchantId, UUID catalogueVersionId, String merchantSku,
            String gtin, String brand, String canonicalName, String normalizedName, String variant,
            String sizeStorage, String colour, String category, String description, boolean active,
            String sourceRecordId, Long priceMinor, String currency, Long stockQuantity,
            Availability availability, Instant observedAt) {}

    public record IdentityResolution(UUID id, IdentityOutcome outcome, JsonNode matchedFields,
            JsonNode conflictingFields, String evidenceHash) {}

    public record SearchRequest(String query, String merchantSku, String gtin, String brand,
            String variant, String sizeStorage, String colour, String category,
            Long minimumPriceMinor, Long maximumPriceMinor, Boolean vegetarian,
            String prohibitedAllergen, int limit) {}

    public record SearchHit(Product product, double score, GateOutcome identityGate,
            Map<String, Double> scoreEvidence, AllergenState prohibitedAllergenState) {}

    public record SearchResponse(MatchClassification classification, List<SearchHit> matches,
            List<SearchHit> relatedAlternatives, boolean vectorFallback, String catalogueVersion,
            List<String> evidence) {}

    public record IngestionResult(CatalogueVersion version, List<RowRejection> rejections) {}
    public record RowRejection(int row, String code, String message) {}

    public record CatalogueHealth(UUID merchantId, UUID catalogueVersionId, int version,
            int products, int activeProducts, int exactIdentities, int unresolvedIdentities,
            int enrichedProducts, int readyEmbeddings, int failedEmbeddings,
            int staleFacts, int conflictingFacts) {}

    public record InspectionSample(String catalogueVersion, CatalogueHealth health,
            List<Product> products, List<String> provenanceSummary) {}
}
