package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenticcommerce.gateway.catalogue.CatalogueProvider;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.catalogue.CatalogueService;
import dev.agenticcommerce.gateway.catalogue.EmbeddingProvider;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import dev.agenticcommerce.gateway.agentization.authority.DeterministicReadinessService;
import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.CapabilityReadiness;
import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.ReadinessCapability;
import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.tool.AgentToolRegistry;
import dev.agenticcommerce.gateway.agentization.tool.AgentToolExecutor;
import dev.agenticcommerce.gateway.agentization.tool.NextAgentAction;
import dev.agenticcommerce.gateway.agentization.model.ToolOutcome;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.AgentObservationRepository;
import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@Import(Task007CatalogueIntegrationTest.Fakes.class)
class Task007CatalogueIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");
    @Autowired JdbcClient jdbc;@Autowired ObjectMapper mapper;@Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;@Autowired MerchantAdminMembershipRepository memberships;
    @Autowired CatalogueService catalogues;@Autowired CatalogueRepository repository;
    @Autowired HybridCatalogueRetrievalService retrieval;@Autowired FakeEmbedding embedding;
    @Autowired DeterministicReadinessService readiness;@Autowired AgentToolRegistry tools;
    @Autowired AgentToolExecutor toolExecutor;@Autowired AgentizationRunRepository runs;@Autowired AgentObservationRepository observations;

    @BeforeEach void clear(){embedding.fail=false;jdbc.sql("""
            TRUNCATE TABLE catalogue_retrieval_evidence,product_embedding,product_external_fact,
              product_identity_resolution,merchant_product_commerce_state,merchant_product,catalogue_version,
              agent_commerce_manifest_capability,agent_commerce_manifest,capability_readiness_evaluation,
              merchant_policy_snapshot_rule,merchant_policy_snapshot,policy_rule_approval_decision,
              merchant_clarification,proposed_policy_rule,policy_document,mapping_approval_decision,
              capability_contract_test_run,capability_mapping_proposal,agent_observation,agentization_run,
              openapi_artifact,merchant_approved_endpoint,spring_session_attributes,spring_session,
              actor_password_credential,merchant_admin_membership,application_actor,merchant CASCADE
            """).update();}

    @Test void migrationCreatesTenantScopedVectorAndSearchSchema(){
        assertThat(jdbc.sql("SELECT count(*)::int FROM flyway_schema_history WHERE version='007' AND success").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT format_type(a.atttypid,a.atttypmod) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid WHERE c.relname='product_embedding' AND a.attname='embedding'").query(String.class).single()).isEqualTo("vector(768)");
        assertThat(jdbc.sql("SELECT count(*)::int FROM pg_indexes WHERE tablename='merchant_product' AND indexdef LIKE '%gin%'").query(Integer.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test void ingestionVersionsNormalizesRejectsAndHashesDeterministically(){Identity i=identity("versions");String payload=payload(true);
        IngestionResult one=catalogues.ingest(i.actor.id(),i.merchant.id(),"JSON",payload);
        IngestionResult two=catalogues.ingest(i.actor.id(),i.merchant.id(),"JSON",payload);
        assertThat(one.version().version()).isOne();assertThat(two.version().version()).isEqualTo(2);
        assertThat(one.version().sourceHash()).isEqualTo(two.version().sourceHash()).hasSize(64);
        assertThat(one.version().contentHash()).isEqualTo(two.version().contentHash()).hasSize(64);
        assertThat(one.version().accepted()).isEqualTo(4);assertThat(one.version().rejected()).isOne();
        assertThat(one.rejections().getFirst().code()).isEqualTo("MALFORMED_ROW");
    }

    @Test void publishedProductIdentityAndVersionAreImmutable(){Identity i=identity("immutable");var v=ingest(i);
        UUID product=repository.products(i.merchant.id(),v.id(),10).getFirst().id();
        assertThatThrownBy(()->jdbc.sql("UPDATE merchant_product SET canonical_name='mutated' WHERE product_id=:p").param("p",product).update()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.sql("UPDATE catalogue_version SET content_hash=repeat('0',64) WHERE catalogue_version_id=:v").param("v",v.id()).update()).isInstanceOf(RuntimeException.class);
    }

    @Test void catalogueAndProductsAreTenantScoped(){Identity a=identity("tenant-a"),b=identity("tenant-b");var v=ingest(a);
        assertThat(catalogues.products(b.actor.id(),b.merchant.id(),v.id(),20)).isEmpty();
        assertThat(repository.latestPublished(b.merchant.id())).isEmpty();
        assertThat(repository.products(a.merchant.id(),v.id(),20)).hasSize(4);
    }

    @Test void exactOffIdentityAttachesBoundedProvenancedFactsButConflictDoesNot(){Identity i=identity("facts");var v=ingest(i);
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_identity_resolution WHERE catalogue_version_id=:v AND external_source='OPEN_FOOD_FACTS' AND outcome='EXACT'").param("v",v.id()).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_identity_resolution WHERE catalogue_version_id=:v AND outcome='CONFLICT'").param("v",v.id()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_external_fact WHERE catalogue_version_id=:v AND source='OPEN_FOOD_FACTS' AND source_version='OFF-test-v2' AND observed_at IS NOT NULL").param("v",v.id()).query(Integer.class).single()).isGreaterThan(8);
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_external_fact f JOIN merchant_product p USING(product_id) WHERE p.merchant_sku='MILK-1L'").query(Integer.class).single()).isZero();
    }

    @Test void embeddingsPersistExactModelDimensionHashAndRealVectors(){Identity i=identity("vectors");var v=ingest(i);
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_embedding WHERE catalogue_version_id=:v AND model_name='gemini-embedding-2' AND output_dimensions=768 AND length(input_hash)=64 AND vector_dims(embedding)=768").param("v",v.id()).query(Integer.class).single()).isEqualTo(4);
        var vector=repository.vectorCandidates(i.merchant.id(),v.id(),embedding.embed("paneer"),4);
        assertThat(vector).hasSize(4);assertThat(vector.getFirst().score()).isGreaterThanOrEqualTo(vector.getLast().score());
    }

    @Test void embeddingFailureStoresNoFakeVectorAndLexicalRetrievalStillWorks(){Identity i=identity("fallback");embedding.fail=true;var v=ingest(i);
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_embedding WHERE catalogue_version_id=:v AND indexing_state='FAILED' AND embedding IS NULL").param("v",v.id()).query(Integer.class).single()).isEqualTo(4);
        SearchResponse result=retrieval.search(i.merchant.id(),search("high protein paneer"));
        assertThat(result.vectorFallback()).isTrue();assertThat(result.matches()).isNotEmpty();
        assertThat(result.matches().getFirst().product().merchantSku()).isEqualTo("PANEER-200");
    }

    @Test void postgresFtsTrigramAndExactPrecedenceAreReal(){Identity i=identity("lexical");ingest(i);
        SearchResponse fts=retrieval.search(i.merchant.id(),search("wholegrain breakfast oats"));
        SearchResponse typo=retrieval.search(i.merchant.id(),search("paneeer"));
        SearchResponse exact=retrieval.exact(i.merchant.id(),"PANEER-200",null);
        assertThat(fts.matches()).extracting(h->h.product().merchantSku()).contains("OATS-500");
        assertThat(typo.matches().isEmpty()?typo.relatedAlternatives():typo.matches()).extracting(h->h.product().merchantSku()).contains("PANEER-200");
        assertThat(exact.matches().getFirst().score()).isEqualTo(1.0);assertThat(exact.matches().getFirst().identityGate()).isEqualTo(GateOutcome.PASS);
    }

    @Test void explicitHardIdentityMismatchCannotBeOverridden(){Identity i=identity("gate");ingest(i);
        SearchResponse wrongSku=retrieval.exact(i.merchant.id(),"MISSING-SKU",null);
        SearchResponse wrongBarcode=retrieval.exact(i.merchant.id(),null,"9999999999999");
        SearchResponse wrongVariant=retrieval.search(i.merchant.id(),new SearchRequest("High Protein Paneer",null,null,"Demo Dairy","Smoked","200 g",null,null,null,null,null,null,10));
        assertThat(wrongSku.classification()).isEqualTo(MatchClassification.NO_TRUSTWORTHY_MATCH);assertThat(wrongSku.matches()).isEmpty();
        assertThat(wrongBarcode.classification()).isEqualTo(MatchClassification.NO_TRUSTWORTHY_MATCH);assertThat(wrongBarcode.matches()).isEmpty();
        assertThat(wrongVariant.matches()).isEmpty();
    }

    @Test void variantSizeColourAndBrandGatesRejectFuzzyOrVectorSubstitution(){Identity i=identity("hard-fields");ingest(i);
        SearchResponse wrongSize=retrieval.search(i.merchant.id(),new SearchRequest("High Protein Paneer",null,null,null,"Fresh","500 g",null,null,null,null,null,null,10));
        SearchResponse wrongColour=retrieval.search(i.merchant.id(),new SearchRequest("High Protein Paneer",null,null,null,"Fresh","200 g","Black",null,null,null,null,null,10));
        SearchResponse wrongBrand=retrieval.search(i.merchant.id(),new SearchRequest("High Protein Paneer",null,null,"Other Dairy","Fresh","200 g",null,null,null,null,null,null,10));
        assertThat(wrongSize.matches()).isEmpty();assertThat(wrongColour.matches()).isEmpty();assertThat(wrongBrand.matches()).isEmpty();
    }

    @Test void nearestVectorAloneOnlyProducesSeparatedAlternativeNeverValidMatch(){Identity i=identity("vector-open-set");ingest(i);
        SearchResponse response=retrieval.search(i.merchant.id(),search("unlisted semantic token alpha"));
        assertThat(response.matches()).isEmpty();
        assertThat(response.classification()).isIn(MatchClassification.RELATED_ALTERNATIVES,MatchClassification.NO_TRUSTWORTHY_MATCH);
        assertThat(response.relatedAlternatives()).allMatch(alternative->!response.matches().contains(alternative));
    }

    @Test void priceCategoryAndActiveMetadataFiltersRemoveCandidates(){Identity i=identity("filters");ingest(i);
        SearchResponse category=retrieval.search(i.merchant.id(),new SearchRequest("protein",null,null,null,null,null,null,"Dairy",10000L,13000L,null,null,10));
        SearchResponse impossiblePrice=retrieval.search(i.merchant.id(),new SearchRequest("protein",null,null,null,null,null,null,null,999999L,null,null,null,10));
        assertThat(category.matches()).extracting(h->h.product().merchantSku()).contains("PANEER-200").doesNotContain("BAR-PEANUT");
        assertThat(impossiblePrice.classification()).isEqualTo(MatchClassification.NO_TRUSTWORTHY_MATCH);
    }

    @Test void absentAllergenIsUnknownAndPresentOrUnknownFailClosed(){Identity i=identity("allergen");var v=ingest(i);
        Product peanut=product(i,v,"BAR-PEANUT"),oats=product(i,v,"OATS-500");
        assertThat(retrieval.allergen(i.merchant.id(),v.id(),peanut.id(),"peanut")).isEqualTo(AllergenState.PRESENT);
        assertThat(retrieval.allergen(i.merchant.id(),v.id(),oats.id(),"peanut")).isEqualTo(AllergenState.UNKNOWN);
        SearchResponse filtered=retrieval.search(i.merchant.id(),new SearchRequest("protein",null,null,null,null,null,null,null,null,null,null,"peanut",10));
        assertThat(filtered.matches()).isEmpty();assertThat(filtered.relatedAlternatives()).isEmpty();
    }

    @Test void explicitFreshPrimaryAbsenceQualifiesWhileStaleOrConflictFailClosed(){Identity i=identity("safety");var v=ingest(i);Product oats=product(i,v,"OATS-500");
        UUID resolution=jdbc.sql("SELECT identity_resolution_id FROM product_identity_resolution WHERE product_id=:p AND external_source='MERCHANT'").param("p",oats.id()).query(UUID.class).single();
        var absent=mapper.createObjectNode().put("allergen","peanut").put("status","ABSENT");
        repository.insertFact(i.merchant.id(),v.id(),oats.id(),resolution,"ALLERGEN",absent,"MERCHANT","merchant-oats","v1","PRIMARY","ACTIVE",Instant.now(),Instant.now().plus(30,ChronoUnit.DAYS),"a".repeat(64));
        assertThat(retrieval.allergen(i.merchant.id(),v.id(),oats.id(),"peanut")).isEqualTo(AllergenState.ABSENT);
        jdbc.sql("UPDATE product_external_fact SET resolution_state='STALE' WHERE product_id=:p AND authority_tier='PRIMARY'").param("p",oats.id()).update();
        assertThat(retrieval.allergen(i.merchant.id(),v.id(),oats.id(),"peanut")).isEqualTo(AllergenState.UNKNOWN);
        jdbc.sql("UPDATE product_external_fact SET resolution_state='CONFLICT' WHERE product_id=:p AND authority_tier='PRIMARY'").param("p",oats.id()).update();
        assertThat(retrieval.allergen(i.merchant.id(),v.id(),oats.id(),"peanut")).isEqualTo(AllergenState.CONFLICT);
    }

    @Test void noMatchAlternativesHealthAndEvidenceAreDeterministic(){Identity i=identity("health");var v=ingest(i);
        SearchResponse nonexistent=retrieval.search(i.merchant.id(),search("quantum dragonfruit powder zxqv"));
        CatalogueHealth h1=catalogues.health(i.actor.id(),i.merchant.id()),h2=catalogues.health(i.actor.id(),i.merchant.id());
        assertThat(nonexistent.classification()).isEqualTo(MatchClassification.NO_TRUSTWORTHY_MATCH);
        assertThat(h1).isEqualTo(h2);assertThat(h1.products()).isEqualTo(4);assertThat(h1.readyEmbeddings()).isEqualTo(4);
        assertThat(repository.passingEvidenceTypes(i.merchant.id(),v.id())).containsExactlyInAnyOrder("CATALOGUE_SCHEMA","EXACT_PRODUCT_RETRIEVAL","NO_MATCH","IDENTITY_GATE");
        assertThat(catalogues.inspect(i.merchant.id(),2).products()).hasSize(2);
    }

    @Test void catalogueInspectionToolIsBoundedTenantContextAndAuditable(){Identity a=identity("tool-a"),b=identity("tool-b");var av=ingest(a);ingest(b);
        assertThat(tools.isPermitted(AgentizationState.INSPECTING_API,AgentToolName.INSPECT_CATALOG_SAMPLE)).isTrue();
        assertThat(catalogues.inspect(a.merchant.id(),100).products()).hasSize(4).allMatch(p->p.merchantId().equals(a.merchant.id()));
        assertThat(catalogues.inspect(b.merchant.id(),100).products()).allMatch(p->p.merchantId().equals(b.merchant.id()));
        assertThat(repository.passingEvidenceTypes(a.merchant.id(),av.id())).hasSize(4);
        UUID ar=run(a),br=run(b);var aRun=runs.findByMerchantAndId(a.merchant.id(),ar).orElseThrow();var bRun=runs.findByMerchantAndId(b.merchant.id(),br).orElseThrow();
        var action=NextAgentAction.inspectCatalogSample(aRun.sourceArtifactId(),100,"bounded catalogue evidence");var executed=toolExecutor.execute(aRun,action);
        assertThat(executed.structuredResult().path("products").size()).isLessThanOrEqualTo(20);
        assertThatThrownBy(()->toolExecutor.execute(aRun,NextAgentAction.inspectCatalogSample(bRun.sourceArtifactId(),10,"cross tenant"))).isInstanceOf(RuntimeException.class);
        observations.create(aRun,AgentToolName.INSPECT_CATALOG_SAMPLE,"2".repeat(64),executed.structuredResult(),ToolOutcome.SUCCESS,executed.reasonCode(),"catalogue evidence",executed);
        assertThat(observations.findAllByMerchantAndRun(a.merchant.id(),ar)).singleElement().satisfies(o->assertThat(o.toolName()).isEqualTo(AgentToolName.INSPECT_CATALOG_SAMPLE));
    }

    @Test void readinessUsesCurrentCatalogueEvidenceAndPurchaseRemainsUntested(){Identity i=identity("readiness");var v=ingest(i);UUID run=run(i);
        var search=readiness.evaluate(i.actor.id(),i.merchant.id(),run,ReadinessCapability.SEARCH_PRODUCTS);
        var purchase=readiness.evaluate(i.actor.id(),i.merchant.id(),run,ReadinessCapability.PURCHASE);
        assertThat(search.readiness()).isEqualTo(CapabilityReadiness.READY);
        assertThat(search.satisfiedEvidence().toString()).contains("EXACT_PRODUCT_RETRIEVAL","NO_MATCH","CATALOGUE_SCHEMA","IDENTITY_GATE");
        assertThat(purchase.readiness()).isEqualTo(CapabilityReadiness.UNTESTED);
        assertThat(purchase.missingRequirements().toString()).contains("STOCK_ENFORCEMENT_TEST","ORDER_IDEMPOTENCY_TEST");
        jdbc.sql("UPDATE catalogue_retrieval_evidence SET outcome='FAIL' WHERE catalogue_version_id=:v AND evidence_type='NO_MATCH'")
                .param("v",v.id()).update();
        var reevaluated=readiness.evaluate(i.actor.id(),i.merchant.id(),run,ReadinessCapability.SEARCH_PRODUCTS);
        assertThat(reevaluated.readiness()).isEqualTo(CapabilityReadiness.UNTESTED);
        assertThat(reevaluated.missingRequirements().toString()).contains("NO_MATCH");
    }

    private CatalogueVersion ingest(Identity i){return catalogues.ingest(i.actor.id(),i.merchant.id(),"JSON",payload(false)).version();}
    private Product product(Identity i,CatalogueVersion v,String sku){return repository.products(i.merchant.id(),v.id(),20).stream().filter(p->p.merchantSku().equals(sku)).findFirst().orElseThrow();}
    private Identity identity(String key){Merchant m=merchants.create(key,key);ApplicationActor a=actors.create(key+"@test",PlatformRole.MERCHANT_ADMIN);memberships.create(m.id(),a.id());return new Identity(m,a);}
    private UUID run(Identity i){UUID endpoint=jdbc.sql("INSERT INTO merchant_approved_endpoint(merchant_id,base_uri,hostname,approved_at,dns_validated_at) VALUES(:m,'https://merchant.example.test','merchant.example.test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING endpoint_id").param("m",i.merchant.id()).query(UUID.class).single();
        UUID artifact=jdbc.sql("INSERT INTO openapi_artifact(merchant_id,endpoint_id,artifact_type,artifact_version,content_hash,document) VALUES(:m,:e,'OPENAPI','v1',:h,'{}') RETURNING artifact_id")
                .param("m",i.merchant.id()).param("e",endpoint).param("h","1".repeat(64)).query(UUID.class).single();
        return jdbc.sql("""
                INSERT INTO agentization_run(merchant_id,created_by_actor_id,source_artifact_id,target_capability,current_capability,
                  orchestration_state,step_count,max_step_budget,wall_clock_deadline)
                VALUES(:m,:a,:artifact,'SEARCH_PRODUCTS','SEARCH_PRODUCTS','READY_CANDIDATE',1,20,CURRENT_TIMESTAMP+interval '1 hour') RETURNING run_id
                """).param("m",i.merchant.id()).param("a",i.actor.id()).param("artifact",artifact).query(UUID.class).single();}
    private static SearchRequest search(String query){return new SearchRequest(query,null,null,null,null,null,null,null,null,null,null,null,10);}
    private String payload(boolean malformed){String invalid=malformed?",{\"merchantSku\":\"\",\"canonicalName\":\"\"}":"";return """
            {"products":[
             {"merchantSku":"PANEER-200","gtin":"8901234567001","brand":"Demo Dairy","canonicalName":"High Protein Paneer","variant":"Fresh","sizeStorage":"200 g","category":"Dairy","description":"Vegetarian high protein paneer","priceMinor":12000,"currency":"INR","stockQuantity":20,"availability":"IN_STOCK"},
             {"merchantSku":"BAR-PEANUT","gtin":"8901234567002","brand":"Demo Fuel","canonicalName":"Peanut Protein Bar","variant":"Crunch","sizeStorage":"60 g","category":"Snacks","description":"Protein snack","priceMinor":7500,"currency":"INR","stockQuantity":30,"availability":"IN_STOCK"},
             {"merchantSku":"OATS-500","gtin":"8901234567003","brand":"Demo Grains","canonicalName":"Wholegrain Rolled Oats","variant":"Plain","sizeStorage":"500 g","category":"Breakfast","description":"Wholegrain breakfast oats","priceMinor":18000,"currency":"INR","stockQuantity":12,"availability":"IN_STOCK"},
             {"merchantSku":"MILK-1L","gtin":"8901234567004","brand":"Demo Dairy","canonicalName":"Toned Milk","variant":"Toned","sizeStorage":"1 L","category":"Dairy","priceMinor":6800,"currency":"INR","stockQuantity":0,"availability":"OUT_OF_STOCK"}
             %s]}
            """.formatted(invalid);}
    record Identity(Merchant merchant,ApplicationActor actor){}

    static class Fakes{
        @Bean @Primary FakeEmbedding fakeEmbedding(){return new FakeEmbedding();}
        @Bean @Primary CatalogueProvider fakeCatalogue(){return barcode->{
            String brand=barcode.endsWith("4")?"Conflicting Brand":barcode.endsWith("2")?"Demo Fuel":barcode.endsWith("3")?"Demo Grains":"Demo Dairy";
            String size=barcode.endsWith("1")?"200 g":barcode.endsWith("2")?"60 g":barcode.endsWith("3")?"500 g":"1 L";
            List<String> allergens=barcode.endsWith("2")?List.of("peanut"):List.of();
            return Optional.of(new CatalogueProvider.ExternalProduct(barcode,barcode,brand,"Captured product",null,size,
                    List.of("captured ingredient"),allergens,barcode.endsWith("1"),barcode.endsWith("1")?22.0:8.0,
                    "https://images.example.test/"+barcode,"OFF-test-v2",Instant.parse("2026-08-01T00:00:00Z")));};}
    }
    static class FakeEmbedding implements EmbeddingProvider{volatile boolean fail;
        public List<Float> embed(String input){if(fail)throw new IllegalStateException("FAKE_EMBEDDING_FAILURE");List<Float> v=new ArrayList<>(java.util.Collections.nCopies(768,0f));
            int index=Math.floorMod(input.toLowerCase().hashCode(),32);v.set(index,1f);return List.copyOf(v);}}
}
