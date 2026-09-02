package dev.agenticcommerce.gateway.demo;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;
import static dev.agenticcommerce.gateway.demo.DemoMerchantModels.*;
import static dev.agenticcommerce.gateway.onboarding.OnboardingModels.*;

import dev.agenticcommerce.gateway.agentization.authority.*;
import dev.agenticcommerce.gateway.agentization.execution.EnvironmentMerchantCredentialProvider;
import dev.agenticcommerce.gateway.agentization.execution.MerchantCredentialProvider;
import dev.agenticcommerce.gateway.agentization.model.*;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.*;
import dev.agenticcommerce.gateway.agentization.tool.MappingProposalInput;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.catalogue.CatalogueService;
import dev.agenticcommerce.gateway.identity.model.*;
import dev.agenticcommerce.gateway.identity.persistence.*;
import dev.agenticcommerce.gateway.onboarding.OnboardingRepository;
import dev.agenticcommerce.gateway.onboarding.OnboardingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DemoBootstrapService {
    public static final String BOOTSTRAP_KEY="p0-two-merchant-demo";public static final String FIXTURE_VERSION="task-012.6-v1";
    public static final String DEPLOYMENT_PRECONDITION="CURRENT_BUILD_DEPLOYED_AT_PUBLIC_HTTPS_BASE_URL_BEFORE_BOOTSTRAP";
    private static final int EXPECTED_MERCHANTS=2,EXPECTED_AMAZING_PRODUCTS=50,EXPECTED_FRESH_PRODUCTS=30;
    private static final int EXPECTED_BUYER_LINKS=2,EXPECTED_MAPPINGS=14,EXPECTED_READY_CAPABILITIES=14;
    private static final List<CanonicalCapability> CAPABILITIES=List.of(CanonicalCapability.SEARCH_PRODUCTS,
            CanonicalCapability.GET_AVAILABILITY,CanonicalCapability.GET_QUOTE,CanonicalCapability.PLACE_ORDER,
            CanonicalCapability.GET_ORDER_STATE,CanonicalCapability.CANCEL_ORDER,CanonicalCapability.RETURN_ITEM);
    private final JdbcClient jdbc;private final ObjectMapper mapper;private final MerchantRepository merchants;
    private final ApplicationActorRepository actors;private final MerchantAdminMembershipRepository memberships;
    private final ActorPasswordCredentialRepository credentials;private final PasswordEncoder passwords;
    private final CatalogueService catalogues;private final CatalogueRepository catalogueRepository;
    private final DemoMerchantRepository demo;private final OnboardingService onboarding;private final OnboardingRepository onboardingRepository;
    private final ApprovedMerchantEndpointService endpoints;private final OpenApiArtifactService artifacts;
    private final AgentizationRunService runService;private final AgentizationRunRepository runRepository;
    private final CapabilityMappingProposalRepository mappings;private final ExecutableMappingValidator validator;
    private final MerchantAuthorityService mappingAuthority;private final CanonicalCapabilityContractTestService contracts;
    private final DeterministicReadinessService readiness;private final PolicyAuthorityService policies;
    private final MerchantCredentialProvider credentialProvider;private final DemoPolicyExtractionProvider demoPolicyExtractor;
    public DemoBootstrapService(JdbcClient jdbc,ObjectMapper mapper,MerchantRepository merchants,ApplicationActorRepository actors,
            MerchantAdminMembershipRepository memberships,ActorPasswordCredentialRepository credentials,PasswordEncoder passwords,
            CatalogueService catalogues,CatalogueRepository catalogueRepository,DemoMerchantRepository demo,OnboardingService onboarding,
            OnboardingRepository onboardingRepository,ApprovedMerchantEndpointService endpoints,OpenApiArtifactService artifacts,
            AgentizationRunService runService,AgentizationRunRepository runRepository,CapabilityMappingProposalRepository mappings,
            ExecutableMappingValidator validator,MerchantAuthorityService mappingAuthority,
            CanonicalCapabilityContractTestService contracts,DeterministicReadinessService readiness,PolicyAuthorityService policies,
            MerchantCredentialProvider credentialProvider){
        this.jdbc=jdbc;this.mapper=mapper;this.merchants=merchants;this.actors=actors;this.memberships=memberships;this.credentials=credentials;
        this.passwords=passwords;this.catalogues=catalogues;this.catalogueRepository=catalogueRepository;this.demo=demo;this.onboarding=onboarding;
        this.onboardingRepository=onboardingRepository;this.endpoints=endpoints;this.artifacts=artifacts;this.runService=runService;
        this.runRepository=runRepository;this.mappings=mappings;this.validator=validator;this.mappingAuthority=mappingAuthority;
        this.contracts=contracts;this.readiness=readiness;this.policies=policies;this.credentialProvider=credentialProvider;
        this.demoPolicyExtractor=new DemoPolicyExtractionProvider(mapper);}

    public BootstrapSummary bootstrap(String publicBaseUrl,String buyerIdentity,String buyerPassword,Path fixtureRoot){
        require(publicBaseUrl!=null&&publicBaseUrl.matches("https://[^\\s/]+(?:/[A-Za-z0-9._~/-]*)?"),
                "DEMO_MERCHANT_PUBLIC_BASE_URL must be the deployed public HTTPS base URL for the current service");
        require(buyerIdentity!=null&&!buyerIdentity.isBlank()&&buyerIdentity.length()<=320,"DEMO_BUYER_IDENTITY is required");
        require(buyerPassword!=null&&buyerPassword.length()>=12&&buyerPassword.length()<=1024,"DEMO_BUYER_PASSWORD must contain 12 to 1024 characters");
        credentialProvider.require(EnvironmentMerchantCredentialProvider.DEMO_CREDENTIAL_REFERENCE);
        String normalizedBaseUrl=publicBaseUrl.replaceAll("/+$","");
        var completed=completion();if(completed.isPresent()&&completionReusable(completed.get(),normalizedBaseUrl,buyerIdentity))return reused(completed.get());
        if(completionMarkerExists())clearCompletion();
        BuyerSeed buyer=buyer(buyerIdentity,buyerPassword);MerchantSeed amazing=merchant("amazing","Amazing","AMAZING",true,true,true,2880,
                fixtureRoot.resolve("amazing-catalogue-v1.json"));MerchantSeed fresh=merchant("freshbasket","FreshBasket","FRESH_BASKET",false,false,false,30,
                fixtureRoot.resolve("freshbasket-catalogue-v1.json"));
        seedBuyer(buyer.actor(),List.of(amazing.merchant(),fresh.merchant()),buyerIdentity,buyerPassword);
        List<String> blockers=new ArrayList<>();
        for(MerchantSeed seed:List.of(amazing,fresh)){
            seedPolicy(seed);
            blockers.addAll(agentize(seed,normalizedBaseUrl).blockers());
        }
        int createdMerchants=(amazing.created()?1:0)+(fresh.created()?1:0);Stats stats=stats();
        int merchantCount=demoMerchantCount();int buyerLinks=buyerLinks(buyer.actor().id(),amazing.merchant().id(),fresh.merchant().id());
        AuthorityStats authority=authorityStats(normalizedBaseUrl);addCompletionBlockers(blockers,merchantCount,amazing.products(),fresh.products(),
                buyerLinks,authority.mappings(),authority.ready());
        BootstrapSummary result=new BootstrapSummary(false,buyer.created(),buyer.actor().id(),merchantCount,createdMerchants,merchantCount-createdMerchants,
                amazing.products(),fresh.products(),stats.primaryFacts(),stats.embeddingsReady(),stats.embeddingFailures(),
                authority.mappings(),authority.ready(),authority.manifests(),buyerLinks,normalizedBaseUrl,DEPLOYMENT_PRECONDITION,List.copyOf(blockers));
        if(blockers.isEmpty())complete(result);return result;
    }

    private MerchantSeed merchant(String key,String display,String code,boolean cancel,boolean returns,boolean perishable,int delivery,Path fixture){
        var existingMerchant=merchants.findByKey(key);Merchant merchant=existingMerchant.orElseGet(()->merchants.create(key,display));String adminHandle="demo-"+key+"-admin@agentic-commerce.invalid";
        ApplicationActor admin=actors.findByIdentityHandle(adminHandle).orElseGet(()->actors.create(adminHandle,PlatformRole.MERCHANT_ADMIN));
        if(!memberships.existsByMerchantAndActor(merchant.id(),admin.id()))memberships.create(merchant.id(),admin.id());
        demo.saveProfile(merchant.id(),code,cancel,returns,perishable,delivery);
        var published=catalogueRepository.latestPublished(merchant.id());int count;
        if(published.isEmpty()){String payload=read(fixture);var ingestion=catalogues.ingest(admin.id(),merchant.id(),"JSON",payload);published=Optional.of(ingestion.version());count=ingestion.version().accepted();}
        else count=published.get().accepted();demo.initializeInventory(merchant.id(),published.orElseThrow().id());return new MerchantSeed(merchant,admin,published.orElseThrow().id(),count,existingMerchant.isEmpty());
    }

    private void seedBuyer(ApplicationActor buyer,List<Merchant> merchantList,String identity,String password){
        if(onboardingRepository.profile(buyer.id()).isEmpty())onboarding.updateProfile(buyer.id(),new ProfileInput("Demo Evaluator","9999999999",identity));
        var addresses=onboardingRepository.addresses(buyer.id());var selected=onboardingRepository.selectedAddress(buyer.id());
        if(selected.isEmpty()){var address=addresses.isEmpty()?onboarding.addAddress(buyer.id(),new AddressInput("Demo","Demo Evaluator","9999999999",
                "1 Evaluator Lane",null,"Indiranagar","Bengaluru","Karnataka","560001")):addresses.getFirst();onboarding.selectAddress(buyer.id(),address.id());}
        for(Merchant merchant:merchantList)if(onboardingRepository.activeLink(buyer.id(),merchant.id(),Instant.now()).isEmpty())
            onboarding.link(buyer.id(),new LinkRequest(merchant.id(),identity,password));
    }
    private BuyerSeed buyer(String identity,String password){var existing=actors.findByIdentityHandle(identity);ApplicationActor buyer=existing
            .orElseGet(()->actors.create(identity,PlatformRole.BUYER));require(buyer.role()==PlatformRole.BUYER,"DEMO_BUYER_IDENTITY belongs to a non-buyer actor");
        if(credentials.findByActorId(buyer.id()).isEmpty())credentials.createArgon2Credential(buyer.id(),passwords.encode(password),true);return new BuyerSeed(buyer,existing.isEmpty());}

    private void seedPolicy(MerchantSeed seed){
        String fresh=seed.merchant().merchantKey().equals("freshbasket")?"FRESH_BASKET: 30 minute specialist delivery. Cancellation and returns of food are not permitted after placement.":
                "AMAZING: broad commerce delivery within five days. Cancellation before processing and returns within fourteen days are permitted.";
        var document=policies.documents(seed.admin().id(),seed.merchant().id()).stream()
                .filter(value->value.documentType()==PolicyDocumentType.GENERAL_COMMERCE).findFirst()
                .orElseGet(()->policies.upload(seed.admin().id(),seed.merchant().id(),PolicyDocumentType.GENERAL_COMMERCE,"P0 demo commerce policy",fresh));
        var proposed=policies.rules(seed.admin().id(),seed.merchant().id(),document.policyDocumentId());
        if(proposed.isEmpty())proposed=policies.recordBootstrapExtraction(seed.admin().id(),seed.merchant().id(),document.policyDocumentId(),
                demoPolicyExtractor.extract(document));
        proposed.stream().filter(rule->rule.approvalState()==PolicyApprovalState.PROPOSED).forEach(rule->policies.decideRule(
                seed.admin().id(),seed.merchant().id(),rule.policyRuleId(),AuthorityDecision.APPROVE,"Deterministic P0 demo policy approval"));
        int latestSnapshotRules=jdbc.sql("""
                SELECT count(*)::integer FROM merchant_policy_snapshot_rule rule
                JOIN merchant_policy_snapshot snapshot USING(policy_snapshot_id,merchant_id)
                WHERE snapshot.merchant_id=:merchant AND snapshot.snapshot_version=(
                  SELECT max(snapshot_version) FROM merchant_policy_snapshot WHERE merchant_id=:merchant)
                """).param("merchant",seed.merchant().id()).query(Integer.class).single();
        if(latestSnapshotRules!=3)policies.publishSnapshot(seed.admin().id(),seed.merchant().id());}

    private AgentizationResult agentize(MerchantSeed seed,String publicBase){String base=publicBase.replaceAll("/+$","")+"/api/demo-merchants/"+seed.merchant().merchantKey();
        List<String> paths=List.of("/products/search","/availability","/quotes","/orders","/orders/{orderId}","/orders/{orderId}/cancel","/orders/{orderId}/returns");
        var endpoint=endpoints.registerOrReuseApproved(seed.admin().id(),seed.merchant().id(),base,Set.of("GET","POST"),paths,
                EnvironmentMerchantCredentialProvider.DEMO_CREDENTIAL_REFERENCE);
        JsonNode api=openApi();var artifact=artifacts.registerOrReuse(seed.merchant().id(),endpoint.endpointId(),FIXTURE_VERSION,api);
        int mapped=0,readyCount=0,manifests=0;List<String> blockers=new ArrayList<>();
        for(CanonicalCapability capability:CAPABILITIES){if(alreadyReady(seed.merchant().id(),artifact.artifactId(),capability))continue;
            AgentizationRun run=runService.start(seed.admin().id(),seed.merchant().id(),artifact.artifactId(),capability,20,Instant.now().plus(1,ChronoUnit.HOURS));
            run=runService.transition(run,AgentizationState.INPUTS_VALIDATING,null);run=runService.transition(run,AgentizationState.INSPECTING_API,null);run=runService.transition(run,AgentizationState.MAPPING_CAPABILITY,null);
            MappingProposalInput input=mapping(artifact.artifactId(),capability);var proposal=mappings.create(seed.merchant().id(),run.runId(),capability,input);
            validator.validate(proposal);proposal=mappings.markValidation(seed.merchant().id(),run.runId(),proposal.mappingProposalId(),true);
            run=runRepository.setCurrentMappingVersion(run,proposal.mappingVersion());mappings.markAwaitingApproval(seed.merchant().id(),run.runId(),proposal.mappingProposalId());
            mappingAuthority.decideMapping(seed.admin().id(),seed.merchant().id(),run.runId(),proposal.mappingProposalId(),AuthorityDecision.APPROVE,"Approved deterministic demo HTTP contract");
            run=runService.transition(run,AgentizationState.TESTING_CAPABILITY,null);var test=contracts.runCanonical(run,proposal,null);mapped++;
            if(test.outcome()!=ContractTestOutcome.PASS){blockers.add(seed.merchant().merchantKey()+":"+capability+":"+test.failureCode());continue;}
            run=runService.transition(run,AgentizationState.READY_CANDIDATE,null);var evaluation=readiness.evaluate(seed.admin().id(),seed.merchant().id(),run.runId(),ReadinessCapability.from(capability));
            readiness.publishManifestCandidate(seed.admin().id(),seed.merchant().id(),run.runId());manifests++;
            if(evaluation.readiness()==CapabilityReadiness.READY)readyCount++;else blockers.add(seed.merchant().merchantKey()+":"+capability+":"+evaluation.missingRequirements());
        }return new AgentizationResult(mapped,readyCount,manifests,List.copyOf(blockers));}

    private JsonNode openApi(){ObjectNode root=mapper.createObjectNode().put("openapi","3.1.0");var paths=root.putObject("paths");
        operation(paths,"/products/search","post","searchProducts");operation(paths,"/availability","post","getAvailability");
        operation(paths,"/quotes","post","getQuote");operation(paths,"/orders","post","placeOrder");operation(paths,"/orders/{orderId}","get","getOrderState");
        operation(paths,"/orders/{orderId}/cancel","post","cancelOrder");operation(paths,"/orders/{orderId}/returns","post","returnItem");return root;}
    private void operation(ObjectNode paths,String path,String method,String id){paths.withObject(path).withObject(method).put("operationId",id).putObject("responses").putObject("200").put("description","Canonical JSON response");}
    private MappingProposalInput mapping(UUID artifact,CanonicalCapability capability){String path=switch(capability){case SEARCH_PRODUCTS->"/products/search";case GET_AVAILABILITY->"/availability";case GET_QUOTE->"/quotes";case PLACE_ORDER->"/orders";case GET_ORDER_STATE->"/orders/{orderId}";case CANCEL_ORDER->"/orders/{orderId}/cancel";case RETURN_ITEM->"/orders/{orderId}/returns";default->throw new IllegalArgumentException();};
        String method=capability==CanonicalCapability.GET_ORDER_STATE?"GET":"POST";ObjectNode request=mapper.createObjectNode(),response=mapper.createObjectNode(),transforms=mapper.createObjectNode();
        switch(capability){case SEARCH_PRODUCTS->response.put("classification","body.classification");case GET_AVAILABILITY->response.put("available","body.available");
            case GET_QUOTE->{response.put("quoteId","body.quoteId").put("amount","body.finalAmountMinor").put("currency","body.currency");transforms.put("amount",MappingTransformation.IDENTITY.name());}
            default->response.put("orderId","body.orderId");}
        return new MappingProposalInput(artifact,1,operationId(capability),method,path,request,response,transforms,
                mapper.createObjectNode().put("unit","minor"),mapper.createObjectNode().put("field","currency"),"DETERMINISTIC_BOOTSTRAP","task-012.6-v1");}
    private static String operationId(CanonicalCapability c){return switch(c){case SEARCH_PRODUCTS->"searchProducts";case GET_AVAILABILITY->"getAvailability";case GET_QUOTE->"getQuote";case PLACE_ORDER->"placeOrder";case GET_ORDER_STATE->"getOrderState";case CANCEL_ORDER->"cancelOrder";case RETURN_ITEM->"returnItem";default->throw new IllegalArgumentException();};}

    private Optional<JsonNode> completion(){return jdbc.sql("SELECT summary FROM demo_bootstrap_completion WHERE bootstrap_key=:k AND fixture_version=:v")
            .param("k",BOOTSTRAP_KEY).param("v",FIXTURE_VERSION).query((rs,n)->mapper.readTree(rs.getString(1))).optional();}
    private BootstrapSummary reused(JsonNode n){List<String> blockers=new ArrayList<>();n.path("blockers").forEach(item->blockers.add(item.asText()));int merchantCount=n.path("merchants").asInt();
        return new BootstrapSummary(true,false,UUID.fromString(n.path("buyerActorId").asText()),merchantCount,0,merchantCount,
                n.path("amazingProducts").asInt(),n.path("freshBasketProducts").asInt(),n.path("primaryFacts").asInt(),
                n.path("embeddingsReady").asInt(),n.path("lexicalFallbacks").asInt(),n.path("capabilitiesMapped").asInt(),
                n.path("capabilitiesReady").asInt(),n.path("manifests").asInt(),n.path("buyerLinks").asInt(),
                n.path("merchantPublicBaseUrl").asText(),n.path("deploymentPrecondition").asText(DEPLOYMENT_PRECONDITION),List.copyOf(blockers));}
    @Transactional protected void complete(BootstrapSummary summary){jdbc.sql("INSERT INTO demo_bootstrap_completion(bootstrap_key,fixture_version,buyer_actor_id,summary) VALUES (:k,:v,:b,CAST(:s AS jsonb)) ON CONFLICT (bootstrap_key) DO UPDATE SET fixture_version=EXCLUDED.fixture_version,buyer_actor_id=EXCLUDED.buyer_actor_id,summary=EXCLUDED.summary,completed_at=CURRENT_TIMESTAMP")
            .param("k",BOOTSTRAP_KEY).param("v",FIXTURE_VERSION).param("b",summary.buyerActorId()).param("s",mapper.writeValueAsString(summary)).update();}
    private Stats stats(){return jdbc.sql("""
            SELECT count(*) FILTER (WHERE authority_tier='PRIMARY')::integer primary_facts,
            (SELECT count(*)::integer FROM product_embedding e JOIN merchant_product p
               ON p.merchant_id=e.merchant_id AND p.catalogue_version_id=e.catalogue_version_id AND p.product_id=e.product_id
               WHERE e.indexing_state='READY' AND p.merchant_id IN (SELECT merchant_id FROM demo_merchant_profile)) embeddings_ready,
            (SELECT count(*)::integer FROM product_embedding e JOIN merchant_product p
               ON p.merchant_id=e.merchant_id AND p.catalogue_version_id=e.catalogue_version_id AND p.product_id=e.product_id
               WHERE e.indexing_state='FAILED' AND p.merchant_id IN (SELECT merchant_id FROM demo_merchant_profile)) embedding_failures FROM product_external_fact
            WHERE merchant_id IN (SELECT merchant_id FROM demo_merchant_profile)
            """).query((rs,n)->new Stats(rs.getInt(1),rs.getInt(2),rs.getInt(3))).single();}

    private boolean alreadyReady(UUID merchantId,UUID artifactId,CanonicalCapability capability){return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM capability_mapping_proposal mapping
            JOIN mapping_approval_decision approval ON approval.merchant_id=mapping.merchant_id
              AND approval.mapping_proposal_id=mapping.mapping_proposal_id AND approval.decision='APPROVE'
            JOIN capability_contract_test_run test ON test.merchant_id=mapping.merchant_id
              AND test.mapping_proposal_id=mapping.mapping_proposal_id AND test.outcome='PASS'
            JOIN capability_readiness_evaluation ready ON ready.merchant_id=mapping.merchant_id
              AND ready.capability=mapping.capability AND ready.readiness='READY'
            JOIN agent_commerce_manifest_capability advertised ON advertised.merchant_id=mapping.merchant_id
              AND advertised.readiness_evaluation_id=ready.readiness_evaluation_id
              AND advertised.capability=mapping.capability AND advertised.advertised AND advertised.readiness='READY'
            WHERE mapping.merchant_id=:merchant AND mapping.source_artifact_id=:artifact
              AND mapping.capability=:capability AND mapping.validation_status='VALID'
              AND ready.readiness_evaluation_id=(SELECT candidate.readiness_evaluation_id
                FROM capability_readiness_evaluation candidate
                WHERE candidate.merchant_id=mapping.merchant_id AND candidate.capability=mapping.capability
                ORDER BY candidate.evaluated_at DESC,candidate.readiness_evaluation_id DESC LIMIT 1)
              AND ((mapping.capability='SEARCH_PRODUCTS' AND ready.mapping_proposal_id IS NULL
                    AND advertised.executable_mapping_proposal_id IS NULL)
                OR (ready.mapping_proposal_id=mapping.mapping_proposal_id
                    AND ready.mapping_content_hash=approval.mapping_content_hash
                    AND advertised.executable_mapping_proposal_id=mapping.mapping_proposal_id)))
            """).param("merchant",merchantId).param("artifact",artifactId).param("capability",capability.name())
            .query(Boolean.class).single();}

    private AuthorityStats authorityStats(String publicBase){String amazing=publicBase+"/api/demo-merchants/amazing";
        String fresh=publicBase+"/api/demo-merchants/freshbasket";return jdbc.sql("""
            WITH fixture_mappings AS (
              SELECT mapping.* FROM capability_mapping_proposal mapping
              JOIN openapi_artifact artifact ON artifact.merchant_id=mapping.merchant_id AND artifact.artifact_id=mapping.source_artifact_id
              JOIN merchant_approved_endpoint endpoint ON endpoint.merchant_id=mapping.merchant_id AND endpoint.endpoint_id=mapping.endpoint_id
              JOIN mapping_approval_decision approval ON approval.merchant_id=mapping.merchant_id
                AND approval.mapping_proposal_id=mapping.mapping_proposal_id AND approval.decision='APPROVE'
              WHERE mapping.validation_status='VALID' AND artifact.artifact_version=:fixture
                AND endpoint.base_uri IN (:amazing,:fresh) AND endpoint.credential_reference=:credential
            ), valid_ready AS (
              SELECT DISTINCT mapping.merchant_id,mapping.capability,mapping.mapping_proposal_id
              FROM fixture_mappings mapping
              JOIN mapping_approval_decision approval ON approval.merchant_id=mapping.merchant_id
                AND approval.mapping_proposal_id=mapping.mapping_proposal_id AND approval.decision='APPROVE'
              JOIN capability_contract_test_run test ON test.merchant_id=mapping.merchant_id
                AND test.mapping_proposal_id=mapping.mapping_proposal_id AND test.outcome='PASS'
              JOIN capability_readiness_evaluation ready ON ready.merchant_id=mapping.merchant_id
                AND ready.capability=mapping.capability AND ready.readiness='READY'
              JOIN agent_commerce_manifest_capability advertised ON advertised.merchant_id=mapping.merchant_id
                AND advertised.readiness_evaluation_id=ready.readiness_evaluation_id
                AND advertised.capability=mapping.capability AND advertised.advertised AND advertised.readiness='READY'
              WHERE ready.readiness_evaluation_id=(SELECT candidate.readiness_evaluation_id
                FROM capability_readiness_evaluation candidate
                WHERE candidate.merchant_id=mapping.merchant_id AND candidate.capability=mapping.capability
                ORDER BY candidate.evaluated_at DESC,candidate.readiness_evaluation_id DESC LIMIT 1)
                AND ((mapping.capability='SEARCH_PRODUCTS' AND ready.mapping_proposal_id IS NULL
                      AND advertised.executable_mapping_proposal_id IS NULL)
                  OR (ready.mapping_proposal_id=mapping.mapping_proposal_id
                      AND ready.mapping_content_hash=approval.mapping_content_hash
                      AND advertised.executable_mapping_proposal_id=mapping.mapping_proposal_id))
            )
            SELECT (SELECT count(*)::integer FROM (SELECT DISTINCT merchant_id,capability FROM fixture_mappings) value),
                   (SELECT count(*)::integer FROM (SELECT DISTINCT merchant_id,capability FROM valid_ready) value),
                   (SELECT count(DISTINCT manifest.manifest_id)::integer FROM agent_commerce_manifest manifest
                    JOIN agent_commerce_manifest_capability capability ON capability.merchant_id=manifest.merchant_id
                      AND capability.manifest_id=manifest.manifest_id
                    JOIN fixture_mappings mapping ON mapping.merchant_id=capability.merchant_id
                      AND mapping.capability=capability.capability)
            """).param("fixture",FIXTURE_VERSION).param("amazing",amazing).param("fresh",fresh)
            .param("credential",EnvironmentMerchantCredentialProvider.DEMO_CREDENTIAL_REFERENCE)
            .query((rs,n)->new AuthorityStats(rs.getInt(1),rs.getInt(2),rs.getInt(3))).single();}

    private boolean completionReusable(JsonNode summary,String publicBase,String buyerIdentity){try{if(!summary.path("blockers").isArray()
                ||!summary.path("blockers").isEmpty()||!publicBase.equals(summary.path("merchantPublicBaseUrl").asText()))return false;
            UUID buyerId=UUID.fromString(summary.path("buyerActorId").asText());UUID amazing=merchantId("amazing"),fresh=merchantId("freshbasket");
            String storedBuyer=jdbc.sql("SELECT identity_handle FROM application_actor WHERE actor_id=:id")
                    .param("id",buyerId).query(String.class).optional().orElse("");
            AuthorityStats authority=authorityStats(publicBase);return storedBuyer.equals(buyerIdentity)&&demoMerchantCount()==EXPECTED_MERCHANTS
                    &&productCount(amazing)==EXPECTED_AMAZING_PRODUCTS&&productCount(fresh)==EXPECTED_FRESH_PRODUCTS
                    &&buyerLinks(buyerId,amazing,fresh)==EXPECTED_BUYER_LINKS
                    &&authority.mappings()==EXPECTED_MAPPINGS&&authority.ready()==EXPECTED_READY_CAPABILITIES;
        }catch(RuntimeException invalid){return false;}}
    private boolean completionMarkerExists(){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM demo_bootstrap_completion WHERE bootstrap_key=:k)")
            .param("k",BOOTSTRAP_KEY).query(Boolean.class).single();}
    private void clearCompletion(){jdbc.sql("DELETE FROM demo_bootstrap_completion WHERE bootstrap_key=:k").param("k",BOOTSTRAP_KEY).update();}
    private int demoMerchantCount(){return jdbc.sql("SELECT count(*)::integer FROM demo_merchant_profile").query(Integer.class).single();}
    private UUID merchantId(String key){return jdbc.sql("SELECT merchant_id FROM merchant WHERE merchant_key=:key").param("key",key).query(UUID.class).single();}
    private int productCount(UUID merchant){return jdbc.sql("""
            SELECT count(*)::integer FROM merchant_product product JOIN catalogue_version version
              ON version.merchant_id=product.merchant_id AND version.catalogue_version_id=product.catalogue_version_id
            WHERE product.merchant_id=:merchant AND version.status='PUBLISHED'
              AND version.version_number=(SELECT max(version_number) FROM catalogue_version WHERE merchant_id=:merchant AND status='PUBLISHED')
            """).param("merchant",merchant).query(Integer.class).single();}
    private int buyerLinks(UUID buyer,UUID amazing,UUID fresh){return jdbc.sql("""
            SELECT count(*)::integer FROM merchant_account_link WHERE buyer_actor_id=:buyer AND merchant_id IN (:amazing,:fresh)
              AND status='LINKED' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
            """).param("buyer",buyer).param("amazing",amazing).param("fresh",fresh).query(Integer.class).single();}
    private static void addCompletionBlockers(List<String> blockers,int merchants,int amazingProducts,int freshProducts,
            int links,int mappings,int ready){if(merchants!=EXPECTED_MERCHANTS)blockers.add("EXPECTED_2_DEMO_MERCHANTS_ACTUAL_"+merchants);
        if(amazingProducts!=EXPECTED_AMAZING_PRODUCTS)blockers.add("EXPECTED_50_AMAZING_PRODUCTS_ACTUAL_"+amazingProducts);
        if(freshProducts!=EXPECTED_FRESH_PRODUCTS)blockers.add("EXPECTED_30_FRESHBASKET_PRODUCTS_ACTUAL_"+freshProducts);
        if(links!=EXPECTED_BUYER_LINKS)blockers.add("EXPECTED_2_BUYER_LINKS_ACTUAL_"+links);
        if(mappings!=EXPECTED_MAPPINGS)blockers.add("EXPECTED_14_REQUIRED_MAPPINGS_ACTUAL_"+mappings);
        if(ready!=EXPECTED_READY_CAPABILITIES)blockers.add("EXPECTED_14_READY_CAPABILITIES_ACTUAL_"+ready);}
    private static String read(Path path){try{return Files.readString(path);}catch(IOException e){throw new IllegalStateException("Demo fixture cannot be read: "+path,e);}}
    private static void require(boolean value,String message){if(!value)throw new dev.agenticcommerce.gateway.agentization.service.AgentizationException("DEMO_BOOTSTRAP_CONFIGURATION_INVALID",HttpStatus.CONFLICT,message);}
    private record BuyerSeed(ApplicationActor actor,boolean created){}
    private record MerchantSeed(Merchant merchant,ApplicationActor admin,UUID catalogueVersionId,int products,boolean created){}
    private record AgentizationResult(int mapped,int ready,int manifests,List<String> blockers){}
    private record Stats(int primaryFacts,int embeddingsReady,int embeddingFailures){}
    private record AuthorityStats(int mappings,int ready,int manifests){}
}
