package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.catalogue.CatalogueService;
import dev.agenticcommerce.gateway.catalogue.EmbeddingProvider;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import dev.agenticcommerce.gateway.proof.EvidenceSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

/** Provider-backed Buyer intent -> production search mapping -> PostgreSQL hybrid-v3 evaluation. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_MULTILINGUAL_E2E_EVALUATION", matches = "true")
@SpringBootTest(properties = {"buyer.gemini.enabled=false", "catalogue.embedding.enabled=true"})
class MultilingualBuyerRetrievalEvaluationIntegrationTest {
    private static final String MODEL = "gemini-3.1-flash-lite";
    private static final String COMMAND = "pnpm proof:evaluate:multilingual";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;
    @Autowired CatalogueService catalogues;
    @Autowired CatalogueRepository repository;
    @Autowired EmbeddingProvider embeddings;
    @Autowired CanonicalJsonService canonical;
    @Autowired ObjectMapper mapper;

    @Test
    void measuresTrueMultilingualBuyerRetrieval() throws Exception {
        String key = firstNonBlank(System.getenv("GEMINI_API_KEYLAND"), System.getenv("GEMINI_API_KEY"));
        assertThat(key).as("GEMINI_API_KEYLAND or GEMINI_API_KEY is required for real Gemini intent and embeddings").isNotBlank();

        var datasetPath = EvidenceSupport.repositoryRoot().resolve("evaluation/retrieval/multilingual-e2e-labelled-v1.json");
        JsonNode dataset = mapper.readTree(Files.readString(datasetPath));
        assertThat(dataset.path("cases").size()).isEqualTo(17);
        assertThat(java.util.stream.StreamSupport.stream(dataset.path("cases").spliterator(), false)
                .filter(value -> "RECALL".equals(value.path("cohort").asText())).count()).isEqualTo(12);

        var fixturePath = EvidenceSupport.repositoryRoot().resolve(dataset.path("catalogueFixture").asText());
        JsonNode fixture = mapper.readTree(Files.readString(fixturePath));
        Set<String> fixtureSkus = new HashSet<>();
        fixture.path("products").forEach(product -> fixtureSkus.add(product.path("merchantSku").asText()));

        var merchant = merchants.create("multilingual-e2e", "Amazing multilingual E2E evaluation");
        var admin = actors.create("multilingual-e2e@example.test", PlatformRole.MERCHANT_ADMIN);
        memberships.create(merchant.id(), admin.id());
        CatalogueVersion version = catalogues.ingest(admin.id(), merchant.id(), "JSON", mapper.writeValueAsString(fixture)).version();
        CatalogueHealth health = repository.health(merchant.id(), version.id(), version.version());

        GeminiBuyerIntentCompiler compiler = new GeminiBuyerIntentCompiler(key, MODEL, mapper);
        TimedEmbeddingProvider timedEmbeddings = new TimedEmbeddingProvider(embeddings);
        var retrieval = new HybridCatalogueRetrievalService(repository, catalogues, timedEmbeddings, canonical, mapper);
        List<Observation> observations = new ArrayList<>();
        ArrayNode details = mapper.createArrayNode();

        for (JsonNode labelled : dataset.path("cases")) {
            long caseStarted = System.nanoTime();
            CompiledIntent intent;
            long compileNanos;
            try {
                long started = System.nanoTime();
                intent = compiler.compile(message(labelled), BuyerIntentCompiler.ConversationContext.empty(), null);
                compileNanos = System.nanoTime() - started;
            } catch (RuntimeException failure) {
                compileNanos = System.nanoTime() - caseStarted;
                Observation observation = Observation.providerError(labelled, compileNanos, failure.getClass().getSimpleName());
                observations.add(observation);
                details.add(errorDetail(labelled, observation));
                continue;
            }

            boolean normalizationCorrect = normalizationCorrect(labelled, intent);
            SearchRequest request = null;
            SearchResponse response = null;
            long retrievalNanos = 0;
            long embeddingNanos = 0;
            int embeddingErrorsBefore = timedEmbeddings.errors();
            if (intent.ambiguityState() == AmbiguityState.CLEAR) {
                request = CandidateCartService.searchRequest(intent);
                timedEmbeddings.beginCase();
                long started = System.nanoTime();
                response = retrieval.search(merchant.id(), request);
                retrievalNanos = System.nanoTime() - started;
                embeddingNanos = timedEmbeddings.caseNanos();
            }
            long totalNanos = System.nanoTime() - caseStarted;
            List<String> valid = response == null ? List.of() : response.matches().stream()
                    .map(hit -> hit.product().merchantSku()).toList();
            List<String> discovery = response == null ? List.of() : java.util.stream.Stream.concat(
                    response.matches().stream(), response.relatedAlternatives().stream())
                    .map(hit -> hit.product().merchantSku()).toList();
            List<String> expected = strings(labelled.path("expectedSkus"));
            boolean expectedClarification = "CLARIFICATION".equals(labelled.path("expectedOutcome").asText());
            boolean expectedNoMatch = "NO_TRUSTWORTHY_MATCH".equals(labelled.path("expectedOutcome").asText());
            boolean actualClarification = intent.ambiguityState() == AmbiguityState.AMBIGUOUS && request == null;
            boolean expectedOutcomeCorrect = expectedClarification ? actualClarification
                    : expectedNoMatch ? valid.isEmpty() : !valid.isEmpty() && valid.stream().anyMatch(expected::contains);
            boolean englishSearch = request == null || request.query().codePoints().allMatch(code -> code <= 127);
            boolean fabricated = valid.stream().anyMatch(sku -> !fixtureSkus.contains(sku));
            boolean wrongValid = !valid.isEmpty() && (expected.isEmpty() || !expected.contains(valid.getFirst()));
            Observation observation = new Observation(labelled.path("id").asText(), labelled.path("cohort").asText(),
                    labelled.path("language").asText(), expected, valid, discovery, normalizationCorrect,
                    expectedOutcomeCorrect, englishSearch, fabricated, wrongValid,
                    response != null && response.vectorFallback(), timedEmbeddings.errors() > embeddingErrorsBefore,
                    compileNanos, embeddingNanos, Math.max(0, retrievalNanos - embeddingNanos), retrievalNanos,
                    totalNanos, null);
            observations.add(observation);
            details.add(detail(labelled, intent, request, response, observation));
        }

        List<Observation> recall = observations.stream().filter(value -> "RECALL".equals(value.cohort())).toList();
        List<Observation> negatives = observations.stream().filter(value -> "NEGATIVE_CONTROL".equals(value.cohort())).toList();
        ObjectNode summary = summary(observations, recall, negatives, health);
        boolean measurementValid = summary.path("providerErrors").asInt() == 0
                && summary.path("vectorFallbacks").asInt() == 0
                && health.readyEmbeddings() == fixture.path("products").size() && health.failedEmbeddings() == 0
                && observations.stream().allMatch(value -> value.normalizationCorrect() && value.englishSearch()
                        && !value.fabricated() && !value.wrongValid())
                && negatives.stream().allMatch(Observation::expectedOutcomeCorrect);
        summary.put("status", EvidenceSupport.status(measurementValid));

        ObjectNode artifact = EvidenceSupport.envelope(mapper, "amana-multilingual-e2e-evidence-v1",
                observations.size(), COMMAND, summary, details,
                List.of("This is a small labelled fixture evaluation: 12 recall cases and 5 negative controls, not production-scale benchmarking.",
                        "Intent and embedding results can vary with provider/model revisions, quota, and network conditions.",
                        "Intent compile timing is wall-clock time around GeminiBuyerIntentCompiler; local parsing is included and expected to be small.",
                        "Local deterministic retrieval is estimated as hybrid-v3 wall time minus the separately timed query-embedding call.",
                        "The pipeline stops at pre-cart discovery and therefore does not exercise authorization or payment."),
                "This run does not establish production SLAs, quality on unlabelled languages or catalogues, or permission to execute a purchase.");
        artifact.put("datasetSha256", sha256(datasetPath));
        artifact.put("catalogueFixtureSha256", sha256(fixturePath));
        artifact.set("datasetSources", dataset.path("sourceDatasets"));
        EvidenceSupport.write(mapper, "multilingual-e2e.json", "MULTILINGUAL_E2E.md", artifact, markdown(artifact));

        assertThat(summary.path("providerErrors").asInt()).as("provider calls must complete").isZero();
        assertThat(health.readyEmbeddings()).as("every Amazing product must have a READY real embedding")
                .isEqualTo(fixture.path("products").size());
        assertThat(health.failedEmbeddings()).isZero();
        assertThat(summary.path("vectorFallbacks").asInt()).isZero();
        assertThat(summary.path("status").asText()).as("every labelled case and safety control must pass").isEqualTo("PASS");
    }

    private ObjectNode summary(List<Observation> all, List<Observation> recall, List<Observation> negatives,
            CatalogueHealth health) {
        ObjectNode result = mapper.createObjectNode();
        result.put("provider", "GEMINI").put("intentModel", MODEL)
                .put("embeddingModel", EmbeddingProvider.MODEL).put("ranker", "hybrid-v3");
        result.put("recallCases", recall.size()).put("negativeControls", negatives.size());
        result.put("intentNormalizationCorrect", recall.stream().filter(Observation::normalizationCorrect).count());
        result.put("validRecallAt1", rate(recall.stream().filter(value -> at(value.validCandidates(), value.expectedSkus(), 1)).count(), recall.size()));
        result.put("validRecallAt5", rate(recall.stream().filter(value -> at(value.validCandidates(), value.expectedSkus(), 5)).count(), recall.size()));
        result.put("discoveryRecallAt1", rate(recall.stream().filter(value -> at(value.discoveryCandidates(), value.expectedSkus(), 1)).count(), recall.size()));
        result.put("discoveryRecallAt5", rate(recall.stream().filter(value -> at(value.discoveryCandidates(), value.expectedSkus(), 5)).count(), recall.size()));
        List<Observation> noMatch = negatives.stream().filter(value -> !"ML-NEG-005".equals(value.id())).toList();
        result.put("noMatchCases", noMatch.size());
        result.put("noMatchCorrect", noMatch.stream().filter(value -> value.validCandidates().isEmpty()).count());
        result.put("noMatchAccuracy", rate(noMatch.stream().filter(value -> value.validCandidates().isEmpty()).count(), noMatch.size()));
        result.put("clarificationControls", negatives.stream().filter(value -> "ML-NEG-005".equals(value.id())).count());
        result.put("clarificationControlsCorrect", negatives.stream().filter(value -> "ML-NEG-005".equals(value.id())
                && value.expectedOutcomeCorrect()).count());
        List<Observation> retrievalCases = all.stream().filter(value -> value.retrievalNanos() > 0).toList();
        result.put("retrievalCases", retrievalCases.size());
        result.put("fabricatedValidProductRate", rate(retrievalCases.stream().filter(Observation::fabricated).count(), retrievalCases.size()));
        result.put("wrongValidProductOrVariantRate", rate(retrievalCases.stream().filter(Observation::wrongValid).count(), retrievalCases.size()));
        result.put("providerErrors", all.stream().filter(value -> value.providerError() != null || value.embeddingError()).count());
        result.put("intentProviderErrors", all.stream().filter(value -> value.providerError() != null).count());
        result.put("queryEmbeddingProviderErrors", all.stream().filter(Observation::embeddingError).count());
        result.put("vectorFallbacks", all.stream().filter(Observation::vectorFallback).count());
        result.put("readyEmbeddings", health.readyEmbeddings()).put("failedEmbeddings", health.failedEmbeddings());
        result.put("authorizationAttempts", 0).put("authorizationGainedFromLanguage", 0);

        ObjectNode languages = result.putObject("perLanguage");
        for (String language : List.of("hi", "hinglish", "te", "ur")) {
            List<Observation> selected = recall.stream().filter(value -> language.equals(value.language())).toList();
            languages.putObject(language).put("cases", selected.size())
                    .put("intentNormalizationCorrect", selected.stream().filter(Observation::normalizationCorrect).count())
                    .put("validRecallAt1", rate(selected.stream().filter(value -> at(value.validCandidates(), value.expectedSkus(), 1)).count(), selected.size()))
                    .put("validRecallAt5", rate(selected.stream().filter(value -> at(value.validCandidates(), value.expectedSkus(), 5)).count(), selected.size()));
        }
        result.set("latencyMillis", latency(recall));
        return result;
    }

    private ObjectNode latency(List<Observation> values) {
        ObjectNode result = mapper.createObjectNode();
        result.set("intentCompile", percentiles(values.stream().map(Observation::compileNanos).toList()));
        result.set("queryEmbedding", percentiles(values.stream().map(Observation::embeddingNanos).toList()));
        result.set("localDeterministicRetrieval", percentiles(values.stream().map(Observation::localRetrievalNanos).toList()));
        result.set("retrievalIncludingEmbedding", percentiles(values.stream().map(Observation::retrievalNanos).toList()));
        result.set("totalPreCartDiscovery", percentiles(values.stream().map(Observation::totalNanos).toList()));
        return result;
    }

    private ObjectNode percentiles(List<Long> samples) {
        List<Long> sorted = samples.stream().sorted().toList();
        return mapper.createObjectNode().put("sampleSize", sorted.size())
                .put("p50", round(EvidenceSupport.percentile(sorted, .50)))
                .put("p95", round(EvidenceSupport.percentile(sorted, .95)))
                .put("max", round(EvidenceSupport.percentile(sorted, 1.0)));
    }

    private boolean normalizationCorrect(JsonNode labelled, CompiledIntent intent) {
        if (!matchesExpectedFields(labelled.path("expectedIntent"), intent)) return false;
        List<String> accepted = strings(labelled.path("acceptableNormalizedCategories"));
        return accepted.isEmpty() || accepted.stream().anyMatch(value -> equivalent(value, intent.categoryRequest()));
    }

    private boolean matchesExpectedFields(JsonNode expected, CompiledIntent intent) {
        for (var field : expected.properties()) {
            Object actual = switch (field.getKey()) {
                case "budgetAmountMinor" -> intent.budgetAmountMinor(); case "currency" -> intent.currency();
                case "exactMerchantSku" -> intent.exactMerchantSku(); case "exactGtin" -> intent.exactGtin();
                case "exactBrand" -> intent.exactBrand(); case "exactVariant" -> intent.exactVariant();
                case "exactSizeStorage" -> intent.exactSizeStorage(); case "exactColour" -> intent.exactColour();
                case "substitutionPolicy" -> intent.substitutionPolicy(); case "ambiguityState" -> intent.ambiguityState();
                default -> null;
            };
            JsonNode observed = mapper.valueToTree(actual instanceof Enum<?> value ? value.name() : actual);
            if (field.getValue().isNumber() ? !observed.isNumber()
                    || field.getValue().decimalValue().compareTo(observed.decimalValue()) != 0
                    : !equivalent(field.getValue().asText(), observed.isNull() ? null : observed.asText())) return false;
        }
        return true;
    }

    private ObjectNode detail(JsonNode labelled, CompiledIntent intent, SearchRequest request,
            SearchResponse response, Observation observation) {
        ObjectNode detail = mapper.createObjectNode().put("caseId", observation.id()).put("cohort", observation.cohort())
                .put("language", observation.language()).put("originalUtterance", labelled.path("utterance").asText())
                .put("expectedNormalizedMeaning", labelled.path("expectedNormalizedMeaning").asText())
                .set("expectedSkus", labelled.path("expectedSkus"));
        detail.set("compiledIntent", mapper.valueToTree(Map.ofEntries(
                Map.entry("category", value(intent.categoryRequest())), Map.entry("merchantSku", value(intent.exactMerchantSku())),
                Map.entry("gtin", value(intent.exactGtin())), Map.entry("brand", value(intent.exactBrand())),
                Map.entry("variant", value(intent.exactVariant())), Map.entry("sizeStorage", value(intent.exactSizeStorage())),
                Map.entry("colour", value(intent.exactColour())), Map.entry("budgetAmountMinor", intent.budgetAmountMinor() == null ? 0 : intent.budgetAmountMinor()),
                Map.entry("currency", value(intent.currency())), Map.entry("ambiguityState", intent.ambiguityState().name()),
                Map.entry("clarificationQuestion", value(intent.clarificationQuestion())), Map.entry("provider", intent.provider()), Map.entry("model", intent.model()))));
        if (request != null) detail.set("searchRequest", mapper.valueToTree(request)); else detail.putNull("searchRequest");
        detail.put("intentNormalizationCorrect", observation.normalizationCorrect()).put("searchTextEnglish", observation.englishSearch())
                .set("validCandidates", mapper.valueToTree(observation.validCandidates()))
                .set("discoveryCandidates", mapper.valueToTree(observation.discoveryCandidates()))
                .put("observedOutcome", observedOutcome(intent, response)).put("expectedOutcomeCorrect", observation.expectedOutcomeCorrect())
                .put("fabricatedValidProduct", observation.fabricated()).put("wrongValidProductOrVariant", observation.wrongValid())
                .put("vectorFallback", observation.vectorFallback()).put("authorizationAttempted", false)
                .put("authorizationGranted", false).put("boundaryReached", "PRE_CART_DISCOVERY");
        ObjectNode latency = detail.putObject("latencyMillis");
        latency.put("intentCompile", millis(observation.compileNanos())).put("queryEmbedding", millis(observation.embeddingNanos()))
                .put("localDeterministicRetrieval", millis(observation.localRetrievalNanos()))
                .put("retrievalIncludingEmbedding", millis(observation.retrievalNanos()))
                .put("totalPreCartDiscovery", millis(observation.totalNanos()));
        if (response != null) detail.set("retrievalEvidence", mapper.valueToTree(response.evidence()));
        return detail;
    }

    private ObjectNode errorDetail(JsonNode labelled, Observation observation) {
        return mapper.createObjectNode().put("caseId", observation.id()).put("cohort", observation.cohort())
                .put("language", observation.language()).put("originalUtterance", labelled.path("utterance").asText())
                .put("status", "PROVIDER_ERROR").put("errorClass", observation.providerError())
                .put("intentCompileMillis", millis(observation.compileNanos())).put("authorizationAttempted", false);
    }

    private String markdown(ObjectNode artifact) {
        JsonNode summary = artifact.path("summary"), languages = summary.path("perLanguage"), latency = summary.path("latencyMillis");
        StringBuilder out = new StringBuilder("# Multilingual Buyer end-to-end retrieval\n\n")
                .append("Status: **").append(summary.path("status").asText()).append("**\n\n")
                .append("Pipeline: multilingual utterance → real Gemini intent compile → production SearchRequest mapping → real Gemini query embedding → PostgreSQL 17/pgvector hybrid-v3 retrieval.\n\n")
                .append("Recall cohort: ").append(summary.path("recallCases").asInt()).append(" cases; negative controls: ")
                .append(summary.path("negativeControls").asInt()).append(". Provider/model: `GEMINI/").append(MODEL).append("`.\n\n")
                .append("| Metric | Result |\n|---|---:|\n")
                .append(row("Valid Recall@1", summary, "validRecallAt1"))
                .append(row("Valid Recall@5", summary, "validRecallAt5"))
                .append(row("Discovery Recall@1", summary, "discoveryRecallAt1"))
                .append(row("Discovery Recall@5", summary, "discoveryRecallAt5"))
                .append(row("No-match accuracy", summary, "noMatchAccuracy"))
                .append(row("Fabricated valid product rate", summary, "fabricatedValidProductRate"))
                .append(row("Wrong valid product / variant rate", summary, "wrongValidProductOrVariantRate"))
                .append("\n## Per language\n\n| Language | Cases | Intent normalization correct | Valid R@1 | Valid R@5 |\n|---|---:|---:|---:|---:|\n");
        for (String language : List.of("hi", "hinglish", "te", "ur")) {
            JsonNode value = languages.path(language);
            out.append("| ").append(language).append(" | ").append(value.path("cases").asInt()).append(" | ")
                    .append(value.path("intentNormalizationCorrect").asInt()).append(" | ")
                    .append(value.path("validRecallAt1").asText()).append(" | ").append(value.path("validRecallAt5").asText()).append(" |\n");
        }
        out.append("\n## Latency (milliseconds)\n\n| Stage | p50 | p95 | max |\n|---|---:|---:|---:|\n");
        for (String stage : List.of("intentCompile", "queryEmbedding", "localDeterministicRetrieval", "retrievalIncludingEmbedding", "totalPreCartDiscovery")) {
            JsonNode value = latency.path(stage);
            out.append("| ").append(stage).append(" | ").append(value.path("p50").asText()).append(" | ")
                    .append(value.path("p95").asText()).append(" | ").append(value.path("max").asText()).append(" |\n");
        }
        out.append("\nREADY embeddings: ").append(summary.path("readyEmbeddings").asInt())
                .append("; FAILED embeddings: ").append(summary.path("failedEmbeddings").asInt())
                .append("; provider errors: ").append(summary.path("providerErrors").asInt())
                .append("; vector fallbacks: ").append(summary.path("vectorFallbacks").asInt()).append(".\n\n")
                .append("Authorization attempts: 0. This evaluator stops before cart construction and cannot grant purchase authority.\n");
        return out.toString();
    }

    private static String observedOutcome(CompiledIntent intent, SearchResponse response) {
        if (intent.ambiguityState() == AmbiguityState.AMBIGUOUS) return "CLARIFICATION";
        return response != null && !response.matches().isEmpty() ? "VALID_MATCH" : "NO_TRUSTWORTHY_MATCH";
    }

    private static ThreadMessage message(JsonNode labelled) {
        String id = labelled.path("id").asText();
        return new ThreadMessage(UUID.nameUUIDFromBytes((id + ":message").getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes((id + ":thread").getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes((id + ":buyer").getBytes(StandardCharsets.UTF_8)), 1, "TEXT",
                labelled.path("utterance").asText(), "labelled-evaluation", java.time.Instant.now());
    }

    private static boolean at(List<String> candidates, List<String> expected, int rank) {
        return candidates.stream().limit(rank).anyMatch(expected::contains);
    }
    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>(); values.forEach(value -> result.add(value.asText())); return List.copyOf(result);
    }
    private static boolean equivalent(String expected, String actual) {
        return expected != null && actual != null && expected.strip().equalsIgnoreCase(actual.strip());
    }
    private static String value(String input) { return input == null ? "" : input; }
    private static String firstNonBlank(String first, String second) { return first != null && !first.isBlank() ? first : second; }
    private static double rate(long numerator, long denominator) { return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 10000.0; }
    private static double millis(long nanos) { return Math.round(nanos / 1000.0) / 1000.0; }
    private static double round(double value) { return Math.round(value * 10000.0) / 10000.0; }
    private static String row(String label, JsonNode node, String field) { return "| " + label + " | " + node.path(field).asText() + " |\n"; }
    private static String sha256(java.nio.file.Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static final class TimedEmbeddingProvider implements EmbeddingProvider {
        private final EmbeddingProvider delegate;
        private long caseNanos;
        private int errors;
        private TimedEmbeddingProvider(EmbeddingProvider delegate) { this.delegate = delegate; }
        void beginCase() { caseNanos = 0; }
        long caseNanos() { return caseNanos; }
        int errors() { return errors; }
        @Override public List<Float> embed(String input) { return delegate.embed(input); }
        @Override public List<Float> embedDocument(String input) { return delegate.embedDocument(input); }
        @Override public List<Float> embedQuery(String input) {
            long started = System.nanoTime();
            try { return delegate.embedQuery(input); }
            catch (RuntimeException failure) { errors++; throw failure; }
            finally { caseNanos += System.nanoTime() - started; }
        }
        @Override public boolean available() { return delegate.available(); }
    }

    private record Observation(String id, String cohort, String language, List<String> expectedSkus,
            List<String> validCandidates, List<String> discoveryCandidates, boolean normalizationCorrect,
            boolean expectedOutcomeCorrect, boolean englishSearch, boolean fabricated, boolean wrongValid,
            boolean vectorFallback, boolean embeddingError, long compileNanos, long embeddingNanos,
            long localRetrievalNanos, long retrievalNanos, long totalNanos, String providerError) {
        static Observation providerError(JsonNode labelled, long compileNanos, String error) {
            return new Observation(labelled.path("id").asText(), labelled.path("cohort").asText(),
                    labelled.path("language").asText(), List.of(), List.of(), List.of(), false, false, false,
                    false, false, false, false, compileNanos, 0, 0, 0, compileNanos, error);
        }
    }
}


