package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalHintMatcherTest {
    @Test void freeFormCategoryHintsUseBoundedProductTypeEquivalence() {
        Product buds = product("Auralink", "Auralink Buds Bluetooth Earphones", "Earphones");
        assertThat(RetrievalHintMatcher.categoryCompatible("bluetooth earphones", buds)).isTrue();
        assertThat(RetrievalHintMatcher.categoryCompatible("wireless in-ear audio", buds)).isTrue();
        assertThat(RetrievalHintMatcher.categoryCompatible("gaming console", buds)).isFalse();
    }

    @Test void brandHintOnlyAllowsSpacingAndPunctuationEquivalence() {
        assertThat(RetrievalHintMatcher.brandEquivalent("Aura Link", "Auralink")).isTrue();
        assertThat(RetrievalHintMatcher.brandEquivalent("Auralinc", "Auralink")).isFalse();
        assertThat(RetrievalHintMatcher.brandEquivalent("Aura", "Auralink")).isFalse();
    }

    private static Product product(String brand, String name, String category) {
        UUID merchant = UUID.randomUUID(), version = UUID.randomUUID();
        return new Product(UUID.randomUUID(), merchant, version, "AMZ-AUDIO-032", null, brand, name,
                CatalogueService.normalizeText(name), "Buds Pro", null, null, category, null, true,
                "fixture", 299900L, "INR", 10L, Availability.IN_STOCK, Instant.parse("2026-09-05T00:00:00Z"));
    }
}
