package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.catalogue.CatalogueService;
import dev.agenticcommerce.gateway.demo.DemoMerchantRepository;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import dev.agenticcommerce.gateway.intent.CandidateCartService;
import dev.agenticcommerce.gateway.intent.ExactProductIdentityResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class DemoMerchantInventoryRefreshIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;
    @Autowired CatalogueService catalogues;
    @Autowired DemoMerchantRepository inventory;
    @Autowired ExactProductIdentityResolver identities;
    @Autowired CandidateCartService candidateCarts;
    @Autowired JdbcClient jdbc;

    @Test
    void catalogueRefreshRebindsExistingSkuWithoutResettingStockAndAddsNewSkuIdempotently() {
        var merchant = merchants.create("inventory-refresh", "Inventory Refresh");
        var admin = actors.create("inventory-refresh-admin@example.test", PlatformRole.MERCHANT_ADMIN);
        memberships.create(merchant.id(), admin.id());

        var v1 = catalogues.ingest(admin.id(), merchant.id(), "JSON", catalogue("""
                {"merchantSku":"AMZ-AUDIO-032","brand":"Auralink","canonicalName":"Auralink Buds Bluetooth Earphones",
                 "variant":"Buds Pro","category":"Earphones","sourceRecordId":"same-v1",
                 "priceMinor":10000,"currency":"INR","stockQuantity":10,"availability":"IN_STOCK",
                 "observationSource":"TEST_FIXTURE","sourceVersion":"v1","observedAt":"2026-09-05T00:00:00Z"}
                """)).version();
        inventory.initializeInventory(merchant.id(), v1.id());
        assertThat(candidateSearch(merchant.id(), v1.id()).results().getFirst().response().matches())
                .singleElement().extracting(hit -> hit.product().merchantSku()).isEqualTo("AMZ-AUDIO-032");
        InventoryBinding original = binding(merchant.id(), "AMZ-AUDIO-032");
        inventory.decrement(merchant.id(), original.productId(), 3);
        InventoryBinding depleted = binding(merchant.id(), "AMZ-AUDIO-032");
        assertThat(depleted.availableQuantity()).isEqualTo(7);

        var v2 = catalogues.ingest(admin.id(), merchant.id(), "JSON", catalogue("""
                {"merchantSku":"AMZ-AUDIO-032","brand":"Auralink","canonicalName":"Auralink Buds Bluetooth Earphones",
                 "variant":"Buds Pro","category":"Earphones","sourceRecordId":"same-v2",
                 "priceMinor":12000,"currency":"INR","stockQuantity":99,"availability":"IN_STOCK",
                 "observationSource":"TEST_FIXTURE","sourceVersion":"v2","observedAt":"2026-09-05T01:00:00Z",
                 "facts":[{"type":"IMAGE","value":"/demo/products/auralink-buds.svg","sourceVersion":"v2",
                 "observedAt":"2026-09-05T01:00:00Z"}]},
                {"merchantSku":"SKU-NEW","canonicalName":"New version two product","sourceRecordId":"new-v2",
                 "priceMinor":8000,"currency":"INR","stockQuantity":4,"availability":"IN_STOCK",
                 "observationSource":"TEST_FIXTURE","sourceVersion":"v2","observedAt":"2026-09-05T01:00:00Z"}
                """)).version();
        assertThat(v2.sourceHash()).isNotEqualTo(v1.sourceHash());
        inventory.initializeInventory(merchant.id(), v2.id());

        InventoryBinding refreshed = binding(merchant.id(), "AMZ-AUDIO-032");
        assertThat(refreshed.catalogueVersionId()).isEqualTo(v2.id());
        assertThat(refreshed.productId()).isNotEqualTo(original.productId());
        assertThat(refreshed.availableQuantity()).isEqualTo(depleted.availableQuantity());
        assertThat(refreshed.inventoryVersion()).isEqualTo(depleted.inventoryVersion());
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM product_external_fact
                WHERE merchant_id=:merchant AND catalogue_version_id=:version AND product_id=:product
                  AND fact_type='IMAGE' AND authority_tier='PRIMARY' AND resolution_state='ACTIVE'
                """).param("merchant", merchant.id()).param("version", v2.id()).param("product", refreshed.productId())
                .query(Integer.class).single()).isOne();

        var resolution = identities.resolve(compiledIntent());
        assertThat(resolution.outcome()).isEqualTo(ExactProductIdentityResolver.ResolutionOutcome.UNIQUE);
        assertThat(resolution.intent().exactBrand()).isEqualTo("Auralink");
        assertThat(resolution.intent().exactVariant()).isEqualTo("Buds Pro");
        assertThat(candidateSearch(merchant.id(), v2.id()).results().getFirst().response().matches())
                .singleElement().satisfies(hit -> {
                    assertThat(hit.product().id()).isEqualTo(refreshed.productId());
                    assertThat(hit.product().merchantSku()).isEqualTo("AMZ-AUDIO-032");
                });

        InventoryBinding added = binding(merchant.id(), "SKU-NEW");
        assertThat(added.catalogueVersionId()).isEqualTo(v2.id());
        assertThat(added.availableQuantity()).isEqualTo(4);

        inventory.initializeInventory(merchant.id(), v2.id());
        assertThat(binding(merchant.id(), "AMZ-AUDIO-032")).isEqualTo(refreshed);
        assertThat(binding(merchant.id(), "SKU-NEW")).isEqualTo(added);
        assertThat(jdbc.sql("SELECT count(*)::int FROM demo_merchant_inventory WHERE merchant_id=:merchant")
                .param("merchant", merchant.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::int FROM catalogue_version WHERE merchant_id=:merchant AND status='PUBLISHED'")
                .param("merchant", merchant.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::int FROM merchant_product WHERE merchant_id=:merchant AND catalogue_version_id=:version AND product_id=:product")
                .param("merchant", merchant.id()).param("version", v1.id()).param("product", original.productId())
                .query(Integer.class).single()).isOne();
    }

    private CandidateCartService.MerchantSearch candidateSearch(UUID merchantId, UUID catalogueVersionId) {
        CompiledIntent compiled = compiledIntent();
        UUID buyer = UUID.randomUUID();
        BuyerIntent intent = new BuyerIntent(UUID.randomUUID(), UUID.randomUUID(), buyer, 1, UUID.randomUUID(), compiled,
                "model-hash", "intent-hash", Instant.parse("2026-09-05T02:00:00Z"));
        MerchantCandidate merchant = new MerchantCandidate(merchantId, "Inventory Refresh", UUID.randomUUID(), 1,
                catalogueVersionId, "test", UUID.randomUUID(), true);
        MerchantDiscovery discovery = new MerchantDiscovery(UUID.randomUUID(), intent.threadId(), buyer, intent.intentId(), 1,
                DiscoveryOutcome.ELIGIBLE, List.of("SEARCH_PRODUCTS:READY:ADVERTISED"), List.of(merchant), List.of(),
                "discovery-hash", Instant.parse("2026-09-05T02:00:00Z"));
        return candidateCarts.search(intent, discovery);
    }

    private static CompiledIntent compiledIntent() {
        UUID source = UUID.randomUUID();
        EvidenceSpan evidence = new EvidenceSpan(source, 0, 8);
        return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT, "Earphones", null, null, null, null,
                "Auralink", "Buds Pro", null, null, null, null, 1, null, SubstitutionPolicy.UNKNOWN, null,
                List.of(), List.of(
                        new MaterialField("CATEGORY", ConstraintClassification.HARD, evidence, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("BRAND", ConstraintClassification.HARD, evidence, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("VARIANT", ConstraintClassification.HARD, evidence, BigDecimal.ONE, AmbiguityState.CLEAR)),
                AmbiguityState.CLEAR, null, "TEST", "test");
    }

    private InventoryBinding binding(UUID merchantId, String sku) {
        return jdbc.sql("""
                SELECT catalogue_version_id, product_id, available_quantity, inventory_version, updated_at
                FROM demo_merchant_inventory
                WHERE merchant_id=:merchant AND merchant_sku=:sku
                """).param("merchant", merchantId).param("sku", sku)
                .query((rs, row) -> new InventoryBinding(
                        rs.getObject("catalogue_version_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getLong("available_quantity"),
                        rs.getLong("inventory_version"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .single();
    }

    private static String catalogue(String products) {
        return "{\"products\":[" + products + "]}";
    }

    private record InventoryBinding(
            UUID catalogueVersionId,
            UUID productId,
            long availableQuantity,
            long inventoryVersion,
            OffsetDateTime updatedAt) {}
}
