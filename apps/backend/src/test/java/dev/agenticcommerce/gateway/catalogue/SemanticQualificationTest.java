package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.proof.EvidenceSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SemanticQualificationTest {
    final ObjectMapper mapper = new ObjectMapper();
    final CatalogueRepository repository = mock(CatalogueRepository.class);
    final CatalogueService catalogues = mock(CatalogueService.class);
    final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    final UUID merchant = UUID.randomUUID(), version = UUID.randomUUID();
    final HybridCatalogueRetrievalService retrieval = new HybridCatalogueRetrievalService(
            repository, catalogues, embeddings, new CanonicalJsonService(mapper), mapper);
    Product buds;

    @BeforeEach void fixture() throws Exception {
        var fixture = mapper.readTree(Files.readString(EvidenceSupport.repositoryRoot()
                .resolve("evaluation/demo-data/amazing-catalogue-v1.json")));
        var input = fixture.path("products").valueStream()
                .filter(p -> p.path("merchantSku").asText().equals("AMZ-AUDIO-032")).findFirst().orElseThrow();
        buds = new Product(UUID.randomUUID(), merchant, version, input.path("merchantSku").asText(), null,
                input.path("brand").asText(), input.path("canonicalName").asText(), "auralink buds bluetooth earphones",
                input.path("variant").asText(null), input.path("sizeStorage").asText(null), input.path("colour").asText(null),
                input.path("category").asText(null), input.path("description").asText(null), true, "fixture",
                input.path("priceMinor").asLong(), "INR", 18L, Availability.IN_STOCK, Instant.now());
        when(catalogues.requirePublished(merchant)).thenReturn(new CatalogueVersion(version, merchant, 1,
                VersionStatus.PUBLISHED, "JSON", "source", "hash", 50, 0, 0, 0, mapper.createObjectNode(), Instant.now(), Instant.now()));
        when(repository.health(merchant, version, 1)).thenReturn(new CatalogueHealth(merchant, version, 1, 50, 50, 50, 0, 0, 50, 0, 0, 0));
        when(repository.latestIdentity(merchant, version, buds.id())).thenReturn(IdentityOutcome.EXACT);
        when(repository.findProduct(merchant, version, buds.id())).thenReturn(Optional.of(buds));
        when(embeddings.available()).thenReturn(true);
        when(embeddings.embedQuery(anyString())).thenReturn(List.of(1f));
        when(repository.vectorCandidates(eq(merchant), eq(version), anyList(), anyInt()))
                .thenReturn(List.of(new CatalogueRepository.VectorScore(buds.id(), .95)));
    }

    @Test void wirelessEarphonesUnder3500MustQualifyDespiteTheHybridCeiling() {
        assertThat(.30 * 1.0 + .05 * 1.0).isLessThan(RetrievalThresholds.VALID_MATCH);
        var response = retrieval.search(merchant, request("wireless earphones"));
        assertThat(response.matches()).extracting(h -> h.product().merchantSku()).containsExactly("AMZ-AUDIO-032");
        assertThat(response.matches().getFirst().score()).isLessThan(RetrievalThresholds.VALID_MATCH);
        assertThat(response.matches().getFirst().scoreEvidence()).containsEntry("semanticQualified", 1.0);
        assertThat(response.evidence()).contains("ranker:hybrid-v3", "qualification:SEMANTIC");
        var query = org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(repository).insertEvidence(eq(merchant), eq(version), anyString(), anyString(), query.capture(), any(), anyString());
        assertThat(query.getValue().at("/retrievalQualification/matches/0/path").asText()).isEqualTo("SEMANTIC");
    }

    @Test void semanticQualificationReusesOneQueryEmbeddingAndOneVectorCandidatePass() {
        var response = retrieval.search(merchant, request("wireless earphones"));

        assertThat(response.matches()).singleElement().satisfies(hit -> {
            assertThat(hit.product().merchantSku()).isEqualTo("AMZ-AUDIO-032");
            assertThat(hit.scoreEvidence()).containsEntry("semanticQualified", 1.0);
        });
        assertThat(response.evidence()).contains("qualification:SEMANTIC");
        verify(embeddings, times(1)).available();
        verify(embeddings, times(1)).embedQuery("wireless earphones");
        verify(repository, times(1)).lexicalCandidates(eq(merchant), eq(version), eq("wireless earphones"),
                nullable(String.class), nullable(String.class), eq("wireless earphones"),
                nullable(Long.class), eq(350000L), eq(RetrievalThresholds.MAX_CANDIDATES));
        verify(repository, times(1)).vectorCandidates(eq(merchant), eq(version), anyList(),
                eq(RetrievalThresholds.MAX_CANDIDATES));
        verifyNoMoreInteractions(embeddings);
    }

    @Test void oldRankerReproducesNoValidMatchOnTheSameVectorEvidence() {
        var old = new HybridV2RetrievalSnapshot(repository, catalogues, embeddings, new CanonicalJsonService(mapper), mapper);
        var result = old.search(merchant, request("wireless earphones"));
        assertThat(result.matches()).isEmpty();
        assertThat(result.relatedAlternatives()).singleElement().satisfies(hit -> {
            assertThat(hit.product().merchantSku()).isEqualTo("AMZ-AUDIO-032");
            assertThat(hit.identityGate()).isEqualTo(GateOutcome.PASS);
            assertThat(hit.score()).isLessThan(RetrievalThresholds.VALID_MATCH);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"merchantSku", "gtin", "brand", "variant", "sizeStorage", "colour"})
    void explicitIdentityCannotUseSemanticQualificationEvenWhenCorrect(String field) {
        var json = mapper.valueToTree(request("wireless earphones"));
        ((tools.jackson.databind.node.ObjectNode) json).put(field,
                field.equals("gtin") ? "1234567890123" : mapper.valueToTree(buds).path(field).asText());
        var result = retrieval.search(merchant, mapper.treeToValue(json, SearchRequest.class));
        assertThat(result.matches()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"category", "brand", "variant", "sizeStorage", "colour", "merchantSku", "gtin"})
    void conflictingHardIdentityNeverPassesWithPerfectVectors(String field) {
        var json = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(request("wireless earphones"));
        json.put(field, "WRONG_IDENTITY");
        assertThat(retrieval.search(merchant, mapper.treeToValue(json, SearchRequest.class)).matches()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"INACTIVE_PROVIDER", "NO_READY", "LOOKUP_FAILURE", "QUERY_FAILURE", "EMPTY_VECTORS", "CONFLICT", "MISSING_PRODUCT", "INACTIVE_PRODUCT", "WRONG_MERCHANT", "WRONG_VERSION", "PRICE", "MISSING_PRICE", "VEGETARIAN_UNKNOWN", "ALLERGEN_UNKNOWN"})
    void missingOrConflictingAuthorityFailsClosed(String failure) {
        SearchRequest req = request("wireless earphones");
        switch (failure) {
            case "INACTIVE_PROVIDER" -> when(embeddings.available()).thenReturn(false);
            case "NO_READY" -> when(repository.health(merchant, version, 1)).thenReturn(
                    new CatalogueHealth(merchant, version, 1, 50, 50, 50, 0, 0, 0, 50, 0, 0));
            case "LOOKUP_FAILURE" -> when(repository.vectorCandidates(eq(merchant), eq(version), anyList(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
            case "QUERY_FAILURE" -> when(embeddings.embedQuery(anyString())).thenThrow(new IllegalStateException("unavailable"));
            case "EMPTY_VECTORS" -> when(repository.vectorCandidates(eq(merchant), eq(version), anyList(), anyInt())).thenReturn(List.of());
            case "CONFLICT" -> when(repository.latestIdentity(merchant, version, buds.id())).thenReturn(IdentityOutcome.CONFLICT);
            case "MISSING_PRODUCT" -> when(repository.findProduct(merchant, version, buds.id())).thenReturn(Optional.empty());
            case "INACTIVE_PRODUCT", "WRONG_MERCHANT", "WRONG_VERSION", "MISSING_PRICE" -> {
                var json = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(buds);
                if (failure.equals("INACTIVE_PRODUCT")) json.put("active", false);
                if (failure.equals("WRONG_MERCHANT")) json.put("merchantId", UUID.randomUUID().toString());
                if (failure.equals("WRONG_VERSION")) json.put("catalogueVersionId", UUID.randomUUID().toString());
                if (failure.equals("MISSING_PRICE")) json.putNull("priceMinor");
                when(repository.findProduct(merchant, version, buds.id())).thenReturn(Optional.of(mapper.treeToValue(json, Product.class)));
            }
            default -> {
                var json = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(req);
                if (failure.equals("PRICE")) json.put("maximumPriceMinor", 100L);
                if (failure.equals("VEGETARIAN_UNKNOWN")) json.put("vegetarian", true);
                if (failure.equals("ALLERGEN_UNKNOWN")) json.put("prohibitedAllergen", "peanut");
                req = mapper.treeToValue(json, SearchRequest.class);
            }
        }
        assertThat(retrieval.search(merchant, req).matches()).isEmpty();
    }

    @Test void thresholdBoundaryAndInvalidVectorScoresAreExplicit() {
        double minimum = RetrievalThresholds.SEMANTIC_MINIMUM_SIMILARITY;
        for (double score : new double[]{Math.nextDown(minimum), Double.NaN, Double.POSITIVE_INFINITY, -1, 1.01}) {
            when(repository.vectorCandidates(eq(merchant), eq(version), anyList(), anyInt()))
                    .thenReturn(List.of(new CatalogueRepository.VectorScore(buds.id(), score)));
            assertThat(retrieval.search(merchant, request("wireless earphones")).matches()).isEmpty();
        }
        when(repository.vectorCandidates(eq(merchant), eq(version), anyList(), anyInt()))
                .thenReturn(List.of(new CatalogueRepository.VectorScore(buds.id(), minimum)));
        assertThat(retrieval.search(merchant, request("wireless earphones")).matches()).hasSize(1);
    }

    @Test void semanticLaneCannotBroadenAnAlreadyQualifiedHybridSet() {
        var json = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(buds);
        json.put("id", UUID.randomUUID().toString()).put("merchantSku", "OTHER-AUTHORITATIVE-BUDS");
        Product lexical = mapper.treeToValue(json, Product.class);
        when(repository.lexicalCandidates(eq(merchant), eq(version), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(List.of(new CatalogueRepository.ScoredProduct(lexical, 0, .9, .9, 0)));
        var result = retrieval.search(merchant, request("wireless earphones"));
        assertThat(result.matches()).extracting(h -> h.product().id()).containsExactly(lexical.id());
        assertThat(result.relatedAlternatives()).singleElement().satisfies(hit -> {
            assertThat(hit.product().id()).isEqualTo(buds.id());
            assertThat(hit.scoreEvidence()).containsEntry("semanticQualified", 0.0);
        });
    }

    @Test void bluetoothEarphonesContinuesToQualifyLexically() {
        when(repository.lexicalCandidates(eq(merchant), eq(version), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(List.of(new CatalogueRepository.ScoredProduct(buds, 0, .8, .8, 0)));
        var result = retrieval.search(merchant, request("bluetooth earphones"));
        assertThat(result.matches())
                .extracting(h -> h.product().merchantSku()).containsExactly("AMZ-AUDIO-032");
        assertThat(result.matches().getFirst().scoreEvidence()).containsEntry("semanticQualified", 0.0);
        assertThat(result.evidence()).contains("qualification:HYBRID_SCORE");
    }

    @Test void qualificationReferencesStayBoundedForTwentyMatches() {
        List<CatalogueRepository.ScoredProduct> products = java.util.stream.IntStream.range(0, 20).mapToObj(i -> {
            var json = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(buds);
            json.put("id", UUID.randomUUID().toString()).put("merchantSku", "BUDS-" + i);
            return new CatalogueRepository.ScoredProduct(mapper.treeToValue(json, Product.class), 0, .9, .9, 0);
        }).toList();
        when(repository.lexicalCandidates(eq(merchant), eq(version), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(Long.class), nullable(Long.class), anyInt())).thenReturn(products);
        var req = request("wireless earphones");
        var response = retrieval.search(merchant, new SearchRequest(req.query(), null, null, null, null, null, null,
                req.category(), null, req.maximumPriceMinor(), null, null, 20));
        assertThat(response.matches()).hasSize(20);
        assertThat(response.evidence()).hasSize(6).contains("qualification:HYBRID_SCORE");
    }

    SearchRequest request(String query) {
        return new SearchRequest(query, null, null, null, null, null, null, query, null, 350000L, null, null, 5);
    }
}
