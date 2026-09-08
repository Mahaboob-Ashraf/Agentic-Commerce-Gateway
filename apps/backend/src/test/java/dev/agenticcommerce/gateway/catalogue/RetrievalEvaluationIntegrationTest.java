package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import dev.agenticcommerce.gateway.proof.EvidenceSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Labelled retrieval evaluation. The legacy gate is test-source-only and cannot be enabled in production. */
@Testcontainers
@SpringBootTest(properties = "buyer.gemini.enabled=false")
class RetrievalEvaluationIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;
    @Autowired CatalogueService catalogues;
    @Autowired CatalogueRepository repository;
    @Autowired HybridCatalogueRetrievalService retrieval;
    @Autowired EmbeddingProvider embeddings;
    @Autowired CanonicalJsonService canonical;
    @Autowired ObjectMapper mapper;

    @Test
    void recordsLegacyGateBaselineAndPostFixLexicalMetrics() throws Exception {
        var dataset = mapper.readTree(Files.readString(EvidenceSupport.repositoryRoot()
                .resolve("evaluation/retrieval/amazing-labelled-v1.json")));
        var fixture = mapper.readTree(Files.readString(EvidenceSupport.repositoryRoot()
                .resolve(dataset.path("catalogueFixture").asText())));
        var merchant = merchants.create("retrieval-eval", "Amazing retrieval evaluation");
        var admin = actors.create("retrieval-eval@example.test", PlatformRole.MERCHANT_ADMIN);
        memberships.create(merchant.id(), admin.id());
        CatalogueVersion version = catalogues.ingest(admin.id(), merchant.id(), "JSON", mapper.writeValueAsString(fixture)).version();
        CatalogueHealth health = repository.health(merchant.id(), version.id(), version.version());
        List<Evaluated> baseline = new ArrayList<>();
        List<Evaluated> postFix = new ArrayList<>(), hybrid = new ArrayList<>();
        EmbeddingProvider unavailable = new EmbeddingProvider() {
            @Override public List<Float> embed(String input) { throw new IllegalStateException("INTENTIONALLY_DISABLED_FOR_LEXICAL_EVALUATION"); }
            @Override public boolean available() { return false; }
        };
        HybridCatalogueRetrievalService lexicalOnly = new HybridCatalogueRetrievalService(repository, catalogues, unavailable, canonical, mapper);
        boolean hybridReady = embeddings.available() && health.readyEmbeddings() > 0;
        Set<String> fixtureSkus = new HashSet<>();
        fixture.path("products").forEach(product -> fixtureSkus.add(product.path("merchantSku").asText()));
        for (JsonNode labelled : dataset.path("cases")) {
            SearchRequest request = request(labelled);
            baseline.add(evaluate(labelled, legacyCandidates(merchant.id(), version, request), fixtureSkus));
            SearchResponse response = lexicalOnly.search(merchant.id(), request);
            List<String> candidates = new ArrayList<>();
            response.matches().forEach(hit -> candidates.add(hit.product().merchantSku()));
            response.relatedAlternatives().forEach(hit -> candidates.add(hit.product().merchantSku()));
            postFix.add(evaluate(labelled, candidates, fixtureSkus));
            if (hybridReady) {
                SearchResponse hybridResponse = retrieval.search(merchant.id(), request);
                List<String> hybridCandidates = new ArrayList<>();
                hybridResponse.matches().forEach(hit -> hybridCandidates.add(hit.product().merchantSku()));
                hybridResponse.relatedAlternatives().forEach(hit -> hybridCandidates.add(hit.product().merchantSku()));
                hybrid.add(evaluate(labelled, hybridCandidates, fixtureSkus));
            }
        }

        ObjectNode metricsBefore = metrics(baseline);
        ObjectNode metricsAfter = metrics(postFix);
        ObjectNode summary = mapper.createObjectNode();
        summary.put("status", "PASS");
        summary.set("baseline", metricsBefore);
        summary.set("postFixLexicalOnly", metricsAfter);
        if (hybridReady) {
            ObjectNode hybridMetrics = metrics(hybrid); summary.set("hybrid", hybridMetrics);
            summary.put("hybridStatus", "READY");
            summary.put("hybridVsLexicalRecallAt1Delta", round(hybridMetrics.path("recallAt1").asDouble() - metricsAfter.path("recallAt1").asDouble()));
            summary.put("hybridVsLexicalRecallAt5Delta", round(hybridMetrics.path("recallAt5").asDouble() - metricsAfter.path("recallAt5").asDouble()));
        } else {
            summary.put("hybridStatus", "NOT_RUN_VECTOR_PROVIDER_INACTIVE_OR_NO_READY_VECTORS");
            summary.putNull("hybridVsLexicalRecallAt1Delta"); summary.putNull("hybridVsLexicalRecallAt5Delta");
        }
        ObjectNode embedding = summary.putObject("embeddings");
        embedding.put("ready", health.readyEmbeddings()).put("failed", health.failedEmbeddings())
                .put("vectorProviderActive", embeddings.available()).put("queriesFellBackToLexical", hybridReady ? 0 : postFix.size());

        ArrayNode details = mapper.createArrayNode();
        for (int index = 0; index < postFix.size(); index++) {
            Evaluated before = baseline.get(index), after = postFix.get(index);
            details.add(mapper.createObjectNode().put("caseId", after.id()).put("language", after.language())
                    .set("classes", mapper.valueToTree(after.classes())).set("expectedSkus", mapper.valueToTree(after.expected()))
                    .set("baselineCandidates", mapper.valueToTree(before.candidates()))
                    .set("postFixCandidates", mapper.valueToTree(after.candidates()))
                    .put("baselineRecallAt5", before.recallAt5()).put("postFixRecallAt5", after.recallAt5())
                    .set("hybridCandidates", mapper.valueToTree(hybridReady ? hybrid.get(index).candidates() : List.of())));
        }
        ObjectNode artifact = EvidenceSupport.envelope(mapper, "amana-retrieval-evidence-v1", postFix.size(),
                "pnpm proof:evaluate:retrieval", summary, details,
                hybridReady
                        ? List.of("Baseline is a test-source snapshot of the former strict free-form category/brand gate over the same lexical candidate generator.",
                                "Hybrid metrics use live Gemini embeddings and can vary with provider/model revisions.")
                        : List.of("Baseline is a test-source snapshot of the former strict free-form category/brand gate over the same lexical candidate generator.",
                                "The offline run deliberately disables embeddings; hybrid comparison remains NOT_RUN until a provider-backed run has READY vectors."),
                "This labelled fixture does not prove quality on unlabelled traffic, other catalogues, or future provider/model revisions.");
        EvidenceSupport.write(mapper, "retrieval.json", "RETRIEVAL_EVAL.md", artifact, markdown(artifact));
    }

    private List<String> legacyCandidates(UUID merchantId, CatalogueVersion version, SearchRequest request) {
        String query = CatalogueService.normalizeText(request.query());
        return repository.lexicalCandidates(merchantId, version.id(), query, blank(request.merchantSku()), blank(request.gtin()),
                        blank(request.category()), request.minimumPriceMinor(), request.maximumPriceMinor(), RetrievalThresholds.MAX_CANDIDATES)
                .stream().filter(row -> legacyGate(request, row.product()))
                .map(row -> Map.entry(row.product(), row.exact() > 0 ? 1.0 : clamp(row.fts()) * .35 + clamp(row.trigram()) * .30 + completeness(row.product()) * .05))
                .filter(row -> row.getValue() >= RetrievalThresholds.RELATED_ALTERNATIVE)
                .sorted(Map.Entry.<Product, Double>comparingByValue().reversed().thenComparing(row -> row.getKey().merchantSku()))
                .limit(request.limit()).map(row -> row.getKey().merchantSku()).toList();
    }

    private static boolean legacyGate(SearchRequest request, Product product) {
        return same(request.merchantSku(), product.merchantSku()) && same(request.gtin(), product.gtin())
                && same(request.brand(), product.brand()) && same(request.variant(), product.variant())
                && same(request.sizeStorage(), product.sizeStorage()) && same(request.colour(), product.colour())
                && same(request.category(), product.category())
                && (request.minimumPriceMinor() == null || product.priceMinor() != null && product.priceMinor() >= request.minimumPriceMinor())
                && (request.maximumPriceMinor() == null || product.priceMinor() != null && product.priceMinor() <= request.maximumPriceMinor());
    }

    private SearchRequest request(JsonNode labelled) {
        JsonNode filters = labelled.path("filters");
        return new SearchRequest(labelled.path("query").asText(), text(filters, "merchantSku"), text(filters, "gtin"),
                text(filters, "brand"), text(filters, "variant"), text(filters, "sizeStorage"), text(filters, "colour"),
                text(filters, "category"), number(filters, "minimumPriceMinor"), number(filters, "maximumPriceMinor"),
                bool(filters, "vegetarian"), text(filters, "prohibitedAllergen"), 5);
    }

    private Evaluated evaluate(JsonNode labelled, List<String> candidates, Set<String> fixtureSkus) {
        List<String> expected = new ArrayList<>();
        labelled.path("expectedSkus").forEach(value -> expected.add(value.asText()));
        List<String> classes = new ArrayList<>();
        labelled.path("classes").forEach(value -> classes.add(value.asText()));
        boolean noMatchExpected = "NO_TRUSTWORTHY_MATCH".equals(labelled.path("expectedOutcome").asText());
        boolean recall1 = !candidates.isEmpty() && expected.contains(candidates.getFirst());
        boolean recall5 = candidates.stream().limit(5).anyMatch(expected::contains);
        boolean noMatchCorrect = noMatchExpected && candidates.isEmpty();
        boolean fabricated = candidates.stream().anyMatch(candidate -> !fixtureSkus.contains(candidate));
        boolean wrongVariant = !noMatchExpected && !candidates.isEmpty() && !expected.contains(candidates.getFirst());
        return new Evaluated(labelled.path("id").asText(), labelled.path("language").asText(), classes, expected,
                List.copyOf(candidates), recall1, recall5, noMatchCorrect, fabricated, wrongVariant,
                labelled.path("filters").hasNonNull("merchantSku") || labelled.path("filters").hasNonNull("gtin"));
    }

    private ObjectNode metrics(List<Evaluated> values) {
        ObjectNode result = mapper.createObjectNode();
        List<Evaluated> matches = values.stream().filter(value -> !value.expected().isEmpty()).toList();
        result.put("recallAt1", rate(matches.stream().filter(Evaluated::recallAt1).count(), matches.size()));
        result.put("recallAt5", rate(matches.stream().filter(Evaluated::recallAt5).count(), matches.size()));
        result.put("exactIdentityPrecision", subsetRate(values, "EXACT_NEAR_EXACT", Evaluated::recallAt1));
        result.put("genericCategorySuccessRate", subsetRate(values, "GENERIC_CATEGORY_BUDGET", Evaluated::recallAt5));
        result.put("typoAsrSuccessRate", subsetRate(values, "TYPO_ASR", Evaluated::recallAt5));
        result.put("multilingualSuccessRate", subsetRate(values, "MULTILINGUAL", Evaluated::recallAt5));
        List<Evaluated> noMatch = values.stream().filter(value -> value.classes().contains("HONEST_NO_MATCH")).toList();
        result.put("noMatchAccuracy", rate(noMatch.stream().filter(Evaluated::noMatchCorrect).count(), noMatch.size()));
        result.put("fabricatedProductRate", rate(values.stream().filter(Evaluated::fabricated).count(), values.size()));
        result.put("fabricatedProductCount", values.stream().filter(Evaluated::fabricated).count());
        result.put("wrongVariantSubstitutionRate", rate(matches.stream().filter(Evaluated::wrongVariant).count(), matches.size()));
        result.put("wrongVariantSubstitutionCount", matches.stream().filter(Evaluated::wrongVariant).count());
        return result;
    }

    private double subsetRate(List<Evaluated> values, String name, java.util.function.Predicate<Evaluated> passed) {
        List<Evaluated> subset = values.stream().filter(value -> value.classes().contains(name)).toList();
        return rate(subset.stream().filter(passed).count(), subset.size());
    }

    private static double rate(long numerator, long denominator) { return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 10000.0; }
    private static double round(double value) { return Math.round(value * 10000.0) / 10000.0; }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double completeness(Product product) { int count = 0; if (product.brand() != null) count++; if (product.variant() != null) count++; if (product.sizeStorage() != null) count++; if (product.category() != null) count++; if (product.description() != null) count++; return count / 5.0; }
    private static boolean same(String expected, String actual) { return expected == null || expected.isBlank() || actual != null && CatalogueService.normalizeText(expected).equals(CatalogueService.normalizeText(actual)); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asText() : null; }
    private static Long number(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asLong() : null; }
    private static Boolean bool(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asBoolean() : null; }

    private String markdown(ObjectNode artifact) {
        JsonNode summary = artifact.path("summary");
        JsonNode before = summary.path("baseline"), after = summary.path("postFixLexicalOnly"), hybrid = summary.path("hybrid");
        String hybridValue = hybrid.isMissingNode() ? "NOT_RUN" : null;
        return "# Retrieval evaluation\n\nStatus: **" + artifact.path("summary").path("status").asText() + "**\n\n"
                + "The baseline preserves the former strict free-form category/brand gate. The post-fix run uses production lexical retrieval.\n\n"
                + "| Metric | Baseline | Post-fix lexical | Hybrid |\n|---|---:|---:|---:|\n"
                + metricRow("Recall@1", before, after, hybrid, "recallAt1", hybridValue)
                + metricRow("Recall@5", before, after, hybrid, "recallAt5", hybridValue)
                + metricRow("Exact-identity precision", before, after, hybrid, "exactIdentityPrecision", hybridValue)
                + metricRow("Generic category success", before, after, hybrid, "genericCategorySuccessRate", hybridValue)
                + metricRow("Typo/ASR success", before, after, hybrid, "typoAsrSuccessRate", hybridValue)
                + metricRow("Multilingual success", before, after, hybrid, "multilingualSuccessRate", hybridValue)
                + metricRow("No-match accuracy", before, after, hybrid, "noMatchAccuracy", hybridValue)
                + metricRow("Fabricated-product rate", before, after, hybrid, "fabricatedProductRate", hybridValue)
                + metricRow("Wrong-variant substitution rate", before, after, hybrid, "wrongVariantSubstitutionRate", hybridValue)
                + "\nHybrid comparison: **" + summary.path("hybridStatus").asText() + "**. READY=" + summary.path("embeddings").path("ready").asInt()
                + ", FAILED=" + summary.path("embeddings").path("failed").asInt()
                + ", lexical fallbacks=" + summary.path("embeddings").path("queriesFellBackToLexical").asInt() + ".\n"
                + "Hybrid versus lexical delta: Recall@1=" + summary.path("hybridVsLexicalRecallAt1Delta").asText("NOT_RUN")
                + ", Recall@5=" + summary.path("hybridVsLexicalRecallAt5Delta").asText("NOT_RUN") + ".\n"
                + "Hybrid fabricated products=" + hybrid.path("fabricatedProductCount").asText("NOT_RUN")
                + "; wrong-variant substitutions=" + hybrid.path("wrongVariantSubstitutionCount").asText("NOT_RUN") + ".\n";
    }

    private static String metricRow(String label, JsonNode before, JsonNode after, JsonNode hybrid, String field, String missing) {
        return "| " + label + " | " + before.path(field).asDouble() + " | " + after.path(field).asDouble() + " | "
                + (hybrid.isMissingNode() ? missing : hybrid.path(field).asText()) + " |\n";
    }

    private record Evaluated(String id, String language, List<String> classes, List<String> expected,
            List<String> candidates, boolean recallAt1, boolean recallAt5, boolean noMatchCorrect,
            boolean fabricated, boolean wrongVariant, boolean exactIdentity) {}
}
