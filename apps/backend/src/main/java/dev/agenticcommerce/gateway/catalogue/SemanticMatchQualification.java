package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;

/** Relevance qualification only. Call after authoritative identity, price and food-safety filtering. */
record SemanticMatchQualification(double minimumSimilarity) {
    SemanticMatchQualification {
        if (!Double.isFinite(minimumSimilarity) || minimumSimilarity < 0 || minimumSimilarity > 1)
            throw new IllegalArgumentException("Semantic similarity threshold must be in [0,1]");
    }

    boolean qualifies(SearchRequest request, Product product, GateOutcome gate,
            boolean vectorReady, double similarity) {
        return vectorReady && product.active() && gate == GateOutcome.PASS
                && Double.isFinite(similarity) && similarity >= minimumSimilarity && similarity <= 1
                // Exact identity is deliberately ineligible even when its fields happen to match.
                && request.merchantSku() == null && request.gtin() == null
                && request.brand() == null && request.variant() == null
                && request.sizeStorage() == null && request.colour() == null
                && RetrievalHintMatcher.categoryCompatible(request.category(), product);
    }
}
