package dev.agenticcommerce.gateway.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Typed boundary for bounded, read-only external catalogue enrichment. */
public interface CatalogueProvider {
    Optional<ExternalProduct> lookupExactBarcode(String barcode);

    record ExternalProduct(
            String sourceRecordId,
            String barcode,
            String brand,
            String name,
            String variant,
            String size,
            List<String> ingredients,
            List<String> allergens,
            Boolean vegetarian,
            Double proteinGramsPer100g,
            String imageUrl,
            String sourceVersion,
            Instant observedAt) {
        public ExternalProduct {
            ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
            allergens = allergens == null ? List.of() : List.copyOf(allergens);
        }
    }
}
