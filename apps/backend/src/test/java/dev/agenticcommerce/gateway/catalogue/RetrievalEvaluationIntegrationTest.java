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
        assertThat(dataset.path("cases").size()).isEqualTo(80);
        var fixture = mapper.readTree(Files.readString(EvidenceSupport.repositoryRoot()
                .resolve(dataset.path("catalogueFixture").asText())));
        var merchant = merchants.create("retrieval-eval", "Amazing retrieval evaluation");
        var admin = actors.create("retrieval-eval@example.test", PlatformRole.MERCHANT_ADMIN);
        memberships.create(merchant.id(), admin.id());
        CatalogueVersion version = catalogues.ingest(admin.id(), merchant.id(), "JSON", mapper.writeValueAsString(fixture)).version();
        CatalogueHealth health = repository.health(merchant.id(), version.id(), version.version());
        List<Evaluated> baseline = new ArrayList<>();
        List<Evaluated> postFix = new ArrayList<>(), hybrid = new ArrayList<>();
        List<Evaluated> previousHybrid = new ArrayList<>(), previousValid = new ArrayList<>(), currentValid = new ArrayList<>();
        Map<String, List<Float>> queryVectors = new LinkedHashMap<>();
        EmbeddingProvider cached = new EmbeddingProvider() {
            @Override public List<Float> embed(String input) { return embeddings.embed(input); }
            @Override public List<Float> embedQuery(String input) { return queryVectors.computeIfAbsent(input, embeddings::embedQuery); }
            @Override public boolean available() { return embeddings.available(); }
        };
        var v2 = new HybridV2RetrievalSnapshot(repository, catalogues, cached, canonical, mapper);
        var v3 = new HybridCatalogueRetrievalService(repository, catalogues, cached, canonical, mapper);
        Map<Double, List<Evaluated>> gridValid = new LinkedHashMap<>(), gridDiscovery = new LinkedHashMap<>();
        // Initial .65-.95 sweep found relevant scores below .70; refine around that observed distribution.
        for (double threshold : new double[]{.50, .55, .60, .625, .65, .675, .70, .75, .80, .85, .90, .95}) {
            gridValid.put(threshold, new ArrayList<>()); gridDiscovery.put(threshold, new ArrayList<>());
        }
        ArrayNode qualificationDetails = mapper.createArrayNode();
        int actualFallbacks = 0;
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
                SearchResponse oldResponse = v2.search(merchant.id(), request);
                SearchResponse hybridResponse = v3.search(merchant.id(), request);
                if (oldResponse.vectorFallback() || hybridResponse.vectorFallback()) actualFallbacks++;
                previousHybrid.add(evaluate(labelled, candidates(oldResponse, false), fixtureSkus));
                previousValid.add(evaluate(labelled, candidates(oldResponse, true), fixtureSkus));
                currentValid.add(evaluate(labelled, candidates(hybridResponse, true), fixtureSkus));
                var detail = qualificationDetails.addObject().put("caseId", labelled.path("id").asText());
                var vectorEvidence = detail.putArray("vectorCandidates");
                for (var vector : repository.vectorCandidates(merchant.id(), version.id(),
                        cached.embedQuery(CatalogueService.normalizeText(request.query())), RetrievalThresholds.MAX_CANDIDATES)) {
                    var product = repository.findProduct(merchant.id(), version.id(), vector.productId()).orElseThrow();
                    vectorEvidence.addObject().put("sku", product.merchantSku()).put("similarity", vector.score())
                            .put("identityGate", v3.identityGate(merchant.id(), version.id(), product, request).name());
                }
                detail.set("v2Valid", mapper.valueToTree(candidates(oldResponse, true)));
                detail.set("v3Valid", mapper.valueToTree(candidates(hybridResponse, true)));
                detail.set("v3Evidence", mapper.valueToTree(hybridResponse.evidence()));
                var hits = detail.putArray("v3Hits");
                java.util.stream.Stream.concat(hybridResponse.matches().stream(), hybridResponse.relatedAlternatives().stream())
                        .forEach(hit -> hits.addObject().put("sku", hit.product().merchantSku()).put("score", hit.score())
                                .put("identityGate", hit.identityGate().name()).set("scoreEvidence", mapper.valueToTree(hit.scoreEvidence())));
                for (double threshold : gridValid.keySet()) {
                    var candidateRanker = new HybridCatalogueRetrievalService(repository, catalogues, cached, canonical, mapper,
                            new SemanticMatchQualification(threshold));
                    var result = candidateRanker.search(merchant.id(), request);
                    if (result.vectorFallback()) actualFallbacks++;
                    gridValid.get(threshold).add(evaluate(labelled, candidates(result, true), fixtureSkus));
                    gridDiscovery.get(threshold).add(evaluate(labelled, candidates(result, false), fixtureSkus));
                    detail.withObject("/gridValid").set(Double.toString(threshold), mapper.valueToTree(candidates(result, true)));
                }
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
            summary.set("previousHybridV2", metrics(previousHybrid));
            summary.set("previousHybridV2ValidMatches", metrics(previousValid));
            summary.set("hybridV3ValidMatches", metrics(currentValid));
            summary.put("ranker", "hybrid-v3");
            summary.put("semanticMinimumSimilarity", RetrievalThresholds.SEMANTIC_MINIMUM_SIMILARITY);
            var grid = summary.putArray("semanticThresholdGrid");
            for (double threshold : gridValid.keySet()) {
                grid.addObject().put("threshold", threshold)
                        .set("validMatches", metrics(gridValid.get(threshold)))
                        .set("discovery", metrics(gridDiscovery.get(threshold)))
                        .put("newUnexpectedValidProducts", newUnexpectedProducts(previousValid, gridValid.get(threshold)))
                        .put("safetyNonRegression", safetyNonRegression(previousValid, gridValid.get(threshold))
                                && safetyNonRegression(previousHybrid, gridDiscovery.get(threshold)));
            }
            var wirelessRequest = new SearchRequest("wireless earphones", null, null, null, null, null, null,
                    "wireless earphones", null, 350000L, null, null, 5);
            var wireless = v3.search(merchant.id(), wirelessRequest);
            summary.set("wirelessEarphonesUnder3500", mapper.valueToTree(wireless));
            summary.put("safetyNonRegression", safetyNonRegression(previousValid, currentValid)
                    && safetyNonRegression(previousHybrid, hybrid));
            summary.put("newUnexpectedValidProducts", newUnexpectedProducts(previousValid, currentValid));
            if (!summary.path("safetyNonRegression").asBoolean() || actualFallbacks > 0) summary.put("status", "FAIL");
            summary.put("hybridVsLexicalRecallAt1Delta", round(hybridMetrics.path("recallAt1").asDouble() - metricsAfter.path("recallAt1").asDouble()));
            summary.put("hybridVsLexicalRecallAt5Delta", round(hybridMetrics.path("recallAt5").asDouble() - metricsAfter.path("recallAt5").asDouble()));
        } else {
            summary.put("hybridStatus", "NOT_RUN_VECTOR_PROVIDER_INACTIVE_OR_NO_READY_VECTORS");
            summary.putNull("hybridVsLexicalRecallAt1Delta"); summary.putNull("hybridVsLexicalRecallAt5Delta");
        }
        ObjectNode embedding = summary.putObject("embeddings");
        embedding.put("ready", health.readyEmbeddings()).put("failed", health.failedEmbeddings())
                .put("vectorProviderActive", embeddings.available()).put("queriesFellBackToLexical", hybridReady ? actualFallbacks : postFix.size());
        embedding.put("model", EmbeddingProvider.MODEL).put("dimensions", EmbeddingProvider.OUTPUT_DIMENSIONS);

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
                                "Hybrid metrics use live Gemini embeddings and can vary with provider/model revisions.",
                                "Historical discovery metrics concatenate valid matches and related alternatives. Valid-match metrics measure Buyer qualification separately.",
                                "Threshold grid is calibration on these 80 labels, not held-out validation or production-scale benchmarking. Query embeddings are cached within the run for paired comparisons.")
                        : List.of("Baseline is a test-source snapshot of the former strict free-form category/brand gate over the same lexical candidate generator.",
                                "The offline run deliberately disables embeddings; hybrid comparison remains NOT_RUN until a provider-backed run has READY vectors."),
                "This labelled fixture does not prove quality on unlabelled traffic, other catalogues, or future provider/model revisions.");
        artifact.set("qualificationDetails", qualificationDetails);
        artifact.put("datasetSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(EvidenceSupport.repositoryRoot().resolve("evaluation/retrieval/amazing-labelled-v1.json")))));
        // Offline CI must not erase the most recent provider-backed measurement.
        EvidenceSupport.write(mapper, hybridReady ? "retrieval.json" : "retrieval-offline.json",
                hybridReady ? "RETRIEVAL_EVAL.md" : "RETRIEVAL_OFFLINE.md", artifact, markdown(artifact));
        if (hybridReady) {
            assertThat(actualFallbacks).as("all paired/grid calls must use vectors").isZero();
            assertThat(summary.path("safetyNonRegression").asBoolean()).as("selected threshold safety regression").isTrue();
        }
    }

    private static List<String> candidates(SearchResponse response, boolean validOnly) {
        return (validOnly ? response.matches().stream() : java.util.stream.Stream.concat(
                response.matches().stream(), response.relatedAlternatives().stream()))
                .map(hit -> hit.product().merchantSku()).toList();
    }

    private boolean safetyNonRegression(List<Evaluated> before, List<Evaluated> after) {
        var a = metrics(before); var b = metrics(after);
        return b.path("noMatchAccuracy").asDouble() >= a.path("noMatchAccuracy").asDouble()
                && b.path("exactIdentityPrecision").asDouble() >= a.path("exactIdentityPrecision").asDouble()
                && b.path("fabricatedProductCount").asInt() <= a.path("fabricatedProductCount").asInt()
                && b.path("wrongVariantSubstitutionCount").asInt() <= a.path("wrongVariantSubstitutionCount").asInt()
                && newUnexpectedProducts(before, after) == 0;
    }

    private static long newUnexpectedProducts(List<Evaluated> before, List<Evaluated> after) {
        long count = 0;
        for (int i = 0; i < before.size(); i++) {
            var old = before.get(i); var current = after.get(i);
            count += current.candidates().stream().filter(sku -> !current.expected().contains(sku)
                    && !old.candidates().contains(sku)).count();
        }
        return count;
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
        result.put("semanticSuccessRate", subsetRate(values, "PARAPHRASE_SEMANTIC", Evaluated::recallAt5));
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
                + "Historical discovery definition: valid matches followed by related alternatives.\n\n"
                + "| Metric | Legacy lexical | Post-fix lexical | Hybrid v3 discovery |\n|---|---:|---:|---:|\n"
                + metricRow("Recall@1", before, after, hybrid, "recallAt1", hybridValue)
                + metricRow("Recall@5", before, after, hybrid, "recallAt5", hybridValue)
                + metricRow("Exact-identity precision", before, after, hybrid, "exactIdentityPrecision", hybridValue)
                + metricRow("Generic category success", before, after, hybrid, "genericCategorySuccessRate", hybridValue)
                + metricRow("Semantic success", before, after, hybrid, "semanticSuccessRate", hybridValue)
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
                + "; wrong-variant substitutions=" + hybrid.path("wrongVariantSubstitutionCount").asText("NOT_RUN") + ".\n"
                + qualificationMarkdown(summary);
    }

    private String qualificationMarkdown(JsonNode summary) {
        if (!summary.has("previousHybridV2")) return "\nSemantic calibration: NOT_RUN (provider inactive).\n";
        StringBuilder out = new StringBuilder("\n## Paired hybrid-v2 / hybrid-v3 comparison\n\n")
                .append("Same 80 labels, catalogue, PostgreSQL candidate queries and cached live Gemini query vectors.\n")
                .append("Valid matches measure Buyer eligibility; related alternatives cannot build a cart.\n\n")
                .append("| Metric | v2 discovery | v3 discovery | v2 valid only | v3 valid only |\n|---|---:|---:|---:|---:|\n");
        for (String field : List.of("recallAt1", "recallAt5", "exactIdentityPrecision", "genericCategorySuccessRate",
                "semanticSuccessRate", "typoAsrSuccessRate", "multilingualSuccessRate", "noMatchAccuracy",
                "fabricatedProductRate", "wrongVariantSubstitutionRate")) {
            out.append("| ").append(field);
            for (String mode : List.of("previousHybridV2", "hybrid", "previousHybridV2ValidMatches", "hybridV3ValidMatches"))
                out.append(" | ").append(summary.path(mode).path(field).asText());
            out.append(" |\n");
        }
        out.append("\n## Semantic threshold calibration\n\n")
                .append("Initial 0.65–0.95 sweep found relevant similarities below 0.70; refinement adds 0.50–0.675.\n")
                .append("Choose the highest threshold with maximum valid recall among safety-preserving rows. This is calibration, not held-out validation.\n\n")
                .append("| Threshold | Valid R@1 | Valid semantic | Valid category | Valid no-match | Valid wrong variants | New unexpected valid products | Safety preserved |\n|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (JsonNode row : summary.path("semanticThresholdGrid")) {
            var valid = row.path("validMatches"); out.append("| ").append(row.path("threshold").asText());
            for (String field : List.of("recallAt1", "semanticSuccessRate", "genericCategorySuccessRate", "noMatchAccuracy", "wrongVariantSubstitutionRate"))
                out.append(" | ").append(valid.path(field).asText());
            out.append(" | ").append(row.path("newUnexpectedValidProducts").asText())
                    .append(" | ").append(row.path("safetyNonRegression").asText()).append(" |\n");
        }
        out.append("\nSelected threshold: ").append(summary.path("semanticMinimumSimilarity").asText())
                .append(". Safety non-regression: ").append(summary.path("safetyNonRegression").asText())
                .append(". Wireless earphones under 3500: ")
                .append(summary.path("wirelessEarphonesUnder3500").path("classification").asText()).append(".\n");
        return out.toString();
    }

    private static String metricRow(String label, JsonNode before, JsonNode after, JsonNode hybrid, String field, String missing) {
        return "| " + label + " | " + before.path(field).asDouble() + " | " + after.path(field).asDouble() + " | "
                + (hybrid.isMissingNode() ? missing : hybrid.path(field).asText()) + " |\n";
    }

    private record Evaluated(String id, String language, List<String> classes, List<String> expected,
            List<String> candidates, boolean recallAt1, boolean recallAt5, boolean noMatchCorrect,
            boolean fabricated, boolean wrongVariant, boolean exactIdentity) {}
}
