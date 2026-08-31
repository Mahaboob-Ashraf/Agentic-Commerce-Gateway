package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenticcommerce.gateway.agentization.authority.AgentizationAuthorityRepository;
import dev.agenticcommerce.gateway.agentization.authority.DeterministicReadinessService;
import dev.agenticcommerce.gateway.agentization.authority.MerchantAuthorityService;
import dev.agenticcommerce.gateway.agentization.authority.PolicyAuthorityService;
import dev.agenticcommerce.gateway.agentization.authority.PolicyExtractionProvider;
import dev.agenticcommerce.gateway.agentization.execution.MerchantDnsResolver;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransport;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransportRequest;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransportResponse;
import dev.agenticcommerce.gateway.agentization.execution.ValidatedEndpointResolution;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.GetQuoteTestCase;
import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.AgentizationRunService;
import dev.agenticcommerce.gateway.agentization.service.ApprovedMerchantEndpointService;
import dev.agenticcommerce.gateway.agentization.service.ExecutableMappingValidator;
import dev.agenticcommerce.gateway.agentization.service.GetQuoteContractTestService;
import dev.agenticcommerce.gateway.agentization.service.OpenApiArtifactService;
import dev.agenticcommerce.gateway.agentization.service.AgentizationOrchestrationService;
import dev.agenticcommerce.gateway.agentization.tool.AgentDecisionContext;
import dev.agenticcommerce.gateway.agentization.tool.AgentizationDecisionProvider;
import dev.agenticcommerce.gateway.agentization.tool.AgentToolRegistry;
import dev.agenticcommerce.gateway.agentization.tool.MappingProposalInput;
import dev.agenticcommerce.gateway.agentization.tool.NextAgentAction;
import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
@Import(Task006AuthorityIntegrationTest.Boundaries.class)
class Task006AuthorityIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;
    @Autowired ApprovedMerchantEndpointService endpointService;
    @Autowired OpenApiArtifactService artifacts;
    @Autowired AgentizationRunService runService;
    @Autowired AgentizationRunRepository runRepository;
    @Autowired CapabilityMappingProposalRepository mappings;
    @Autowired ExecutableMappingValidator mappingValidator;
    @Autowired GetQuoteContractTestService contractTests;
    @Autowired MerchantAuthorityService authority;
    @Autowired PolicyAuthorityService policies;
    @Autowired DeterministicReadinessService readiness;
    @Autowired AgentizationAuthorityRepository authorityRepository;
    @Autowired FakeDns dns;
    @Autowired FakePolicyExtractor extractor;
    @Autowired FakeDecisionProvider decisions;
    @Autowired AgentizationOrchestrationService orchestration;
    @Autowired AgentToolRegistry toolRegistry;

    @BeforeEach void clear(){dns.answers.clear();extractor.rules=List.of();decisions.actions.clear();jdbc.sql("""
            TRUNCATE TABLE catalogue_retrieval_evidence, product_embedding, product_external_fact,
              product_identity_resolution, merchant_product_commerce_state, merchant_product, catalogue_version,
              agent_commerce_manifest_capability, agent_commerce_manifest,
              capability_readiness_evaluation, merchant_policy_snapshot_rule, merchant_policy_snapshot,
              policy_rule_approval_decision, merchant_clarification, proposed_policy_rule, policy_document,
              mapping_approval_decision, capability_contract_test_run, capability_mapping_proposal,
              agent_observation, agentization_run, openapi_artifact, merchant_approved_endpoint,
              spring_session_attributes, spring_session, actor_password_credential,
              merchant_admin_membership, application_actor, merchant CASCADE
            """).update();}

    @Test void v006MigrationCreatesAuthorityTablesAndState(){
        assertThat(jdbc.sql("SELECT COUNT(*)::integer FROM flyway_schema_history WHERE version='006' AND success")
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*)::integer FROM information_schema.tables WHERE table_name IN ('merchant_clarification','mapping_approval_decision','policy_document','proposed_policy_rule','merchant_policy_snapshot','capability_readiness_evaluation','agent_commerce_manifest')")
                .query(Integer.class).single()).isEqualTo(7);
    }

    @Test void clarificationIsDurablePausesRunAndOnlyExactMerchantAdminAnswers() throws Exception {
        Fixture f=fixture("clarify",false);force(f.run(),AgentizationState.DIAGNOSING_FAILURE,f.mapping().mappingVersion());
        var evidence=mapper.createArrayNode().add("contract-test:money-mismatch");
        MerchantClarification c=authority.requestClarification(f.merchant().id(),f.run().runId(),
                f.mapping().mappingProposalId(),null,null,"Is the amount expressed in rupees or paise?",evidence,
                ClarificationKind.MONEY_SEMANTICS,AgentizationState.REVISING_MAPPING);
        assertThat(c.status()).isEqualTo(ClarificationStatus.OPEN);
        assertThat(run(f).state()).isEqualTo(AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION);
        Identity other=identity("other");
        assertThatThrownBy(()->authority.answerClarification(other.admin().id(),f.merchant().id(),f.run().runId(),c.clarificationId(),"Rupees"))
                .isInstanceOf(AgentizationException.class);
        ApplicationActor buyer=actors.create("clarify-buyer",PlatformRole.BUYER);
        assertThatThrownBy(()->authority.answerClarification(buyer.id(),f.merchant().id(),f.run().runId(),c.clarificationId(),"Rupees"))
                .isInstanceOf(AgentizationException.class);
        MerchantClarification answered=authority.answerClarification(f.admin().id(),f.merchant().id(),f.run().runId(),c.clarificationId(),"Amounts are INR rupees.");
        assertThat(answered.respondingActorId()).isEqualTo(f.admin().id());
        assertThat(answered.evidenceReferences()).isEqualTo(evidence);
        assertThat(run(f).state()).isEqualTo(AgentizationState.REVISING_MAPPING);
        assertThatThrownBy(()->authority.answerClarification(f.admin().id(),f.merchant().id(),f.run().runId(),c.clarificationId(),"Again"))
                .isInstanceOf(AgentizationException.class);
    }

    @Test void mappingApprovalIsImmutableActorTimeHashAndExactVersionBound() throws Exception {
        Fixture f=fixture("approval",false);
        MappingApprovalDecision decision=authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),
                f.mapping().mappingProposalId(),AuthorityDecision.APPROVE,"Confirmed INR minor units");
        assertThat(decision.approvingActorId()).isEqualTo(f.admin().id());
        assertThat(decision.decidedAt()).isNotNull();assertThat(decision.mappingContentHash()).hasSize(64);
        CapabilityMappingProposal newer=createMapping(f,2,mapper.createObjectNode());
        mappingValidator.validate(newer);newer=mappings.markValidation(f.merchant().id(),f.run().runId(),newer.mappingProposalId(),true);
        assertThat(authority.mappingApprovals(f.admin().id(),f.merchant().id(),f.run().runId(),newer.mappingProposalId())).isEmpty();
        assertThatThrownBy(()->authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),
                f.mapping().mappingProposalId(),AuthorityDecision.REJECT,null)).isInstanceOf(AgentizationException.class);
    }

    @Test void crossTenantMappingDecisionFailsAndRejectionBlocksReadiness() throws Exception {
        Fixture f=fixture("reject",true);Identity other=identity("reject-other");
        assertThatThrownBy(()->authority.decideMapping(other.admin().id(),f.merchant().id(),f.run().runId(),
                f.mapping().mappingProposalId(),AuthorityDecision.APPROVE,null)).isInstanceOf(AgentizationException.class);
        authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),f.mapping().mappingProposalId(),AuthorityDecision.REJECT,"Unsupported");
        var evaluation=readiness.evaluate(f.admin().id(),f.merchant().id(),f.run().runId(),ReadinessCapability.GET_QUOTE);
        assertThat(evaluation.readiness()).isEqualTo(CapabilityReadiness.BLOCKED);
        assertThat(evaluation.blockingEvidence().toString()).contains("MAPPING_REJECTED_BY_MERCHANT");
    }

    @Test void policyDocumentsAreNormalizedHashedVersionedAndTenantScoped() {
        Identity a=identity("policy-a"),b=identity("policy-b");
        PolicyDocument one=policies.upload(a.admin().id(),a.merchant().id(),PolicyDocumentType.RETURN,"Returns","  Items may be returned in 7 days.\r\nUnused only.  ");
        PolicyDocument two=policies.upload(a.admin().id(),a.merchant().id(),PolicyDocumentType.RETURN,"Returns v2","Items may be returned in 14 days.");
        assertThat(one.documentVersion()).isOne();assertThat(two.documentVersion()).isEqualTo(2);
        assertThat(one.contentHash()).hasSize(64);assertThat(one.normalizedContent()).doesNotStartWith(" ");
        assertThatThrownBy(()->policies.rules(b.admin().id(),b.merchant().id(),one.policyDocumentId()))
                .isInstanceOf(AgentizationException.class);
    }

    @Test void extractionCreatesBoundedTypedNonAuthoritativeRules() {
        Identity i=identity("extract");PolicyDocument d=policies.upload(i.admin().id(),i.merchant().id(),PolicyDocumentType.RETURN,"Returns","Unused items within seven days.");
        extractor.rules=List.of(rule(PolicyRuleType.RETURN_WINDOW,"RETURN",true,7,10));
        ProposedPolicyRule extracted=policies.extract(i.admin().id(),i.merchant().id(),d.policyDocumentId()).getFirst();
        assertThat(extracted.approvalState()).isEqualTo(PolicyApprovalState.PROPOSED);
        assertThat(extracted.extractionProvider()).isEqualTo("DETERMINISTIC_FAKE_GEMINI");
        extractor.rules=List.of(new ProposedPolicyRuleInput(PolicyRuleType.RETURN_WINDOW,"bad",
                mapper.createObjectNode().put("script","return true"),mapper.createObjectNode().put("eligible",true),
                BigDecimal.ONE,null,null,null));
        assertThatThrownBy(()->policies.extract(i.admin().id(),i.merchant().id(),d.policyDocumentId()))
                .isInstanceOf(AgentizationException.class).extracting(e->((AgentizationException)e).code())
                .isEqualTo("POLICY_CONDITIONS_INVALID");
    }

    @Test void onlyApprovedRulesResolveAndMissingCoverageIsUnknown() {
        Identity i=identity("resolve");PolicyDocument d=policies.upload(i.admin().id(),i.merchant().id(),PolicyDocumentType.RETURN,"Returns","Seven days.");
        extractor.rules=List.of(rule(PolicyRuleType.RETURN_WINDOW,"RETURN",true,7,10));
        ProposedPolicyRule r=policies.extract(i.admin().id(),i.merchant().id(),d.policyDocumentId()).getFirst();
        PolicySnapshot before=policies.publishSnapshot(i.admin().id(),i.merchant().id());
        assertThat(policies.resolve(i.merchant().id(),before.policySnapshotId(),new PolicyResolutionRequest("RETURN",3,"UNUSED",Instant.now())).outcome())
                .isEqualTo(PolicyDecisionOutcome.UNKNOWN);
        policies.decideRule(i.admin().id(),i.merchant().id(),r.policyRuleId(),AuthorityDecision.APPROVE,"Approved");
        PolicySnapshot after=policies.publishSnapshot(i.admin().id(),i.merchant().id());
        assertThat(policies.resolve(i.merchant().id(),after.policySnapshotId(),new PolicyResolutionRequest("RETURN",3,"UNUSED",Instant.now())).outcome())
                .isEqualTo(PolicyDecisionOutcome.PASS);
        assertThat(authorityRepository.findPolicySnapshot(i.merchant().id(),before.policySnapshotId()).orElseThrow().rules()).isEmpty();
        assertThat(after.snapshotVersion()).isEqualTo(before.snapshotVersion()+1);
    }

    @Test void conflictingApprovedRulesResolveUnknownAndExplicitRuleCanFail() {
        Identity i=identity("conflict");PolicyDocument d=policies.upload(i.admin().id(),i.merchant().id(),PolicyDocumentType.RETURN,"Returns","Conflicting clauses.");
        extractor.rules=List.of(rule(PolicyRuleType.RETURN_WINDOW,"RETURN",true,7,10),
                rule(PolicyRuleType.RETURN_WINDOW,"RETURN",false,7,10));
        List<ProposedPolicyRule> rules=policies.extract(i.admin().id(),i.merchant().id(),d.policyDocumentId());
        rules.forEach(r->policies.decideRule(i.admin().id(),i.merchant().id(),r.policyRuleId(),AuthorityDecision.APPROVE,null));
        PolicySnapshot snapshot=policies.publishSnapshot(i.admin().id(),i.merchant().id());
        assertThat(policies.resolve(i.merchant().id(),snapshot.policySnapshotId(),new PolicyResolutionRequest("RETURN",2,null,Instant.now())).outcome())
                .isEqualTo(PolicyDecisionOutcome.UNKNOWN);
        Identity j=identity("policy-fail");PolicyDocument jd=policies.upload(j.admin().id(),j.merchant().id(),PolicyDocumentType.RETURN,"Returns","No returns.");
        extractor.rules=List.of(rule(PolicyRuleType.NON_RETURNABLE,"RETURN",false,30,1));
        ProposedPolicyRule jr=policies.extract(j.admin().id(),j.merchant().id(),jd.policyDocumentId()).getFirst();
        policies.decideRule(j.admin().id(),j.merchant().id(),jr.policyRuleId(),AuthorityDecision.APPROVE,null);
        PolicySnapshot js=policies.publishSnapshot(j.admin().id(),j.merchant().id());
        assertThat(policies.resolve(j.merchant().id(),js.policySnapshotId(),new PolicyResolutionRequest("RETURN",1,null,Instant.now())).outcome())
                .isEqualTo(PolicyDecisionOutcome.FAIL);
    }

    @Test void quoteNeedsCurrentPassApprovalAndPurchaseNeverShortcutsToReady() throws Exception {
        Fixture f=fixture("ready",true);
        assertThat(readiness.evaluate(f.admin().id(),f.merchant().id(),f.run().runId(),ReadinessCapability.GET_QUOTE).readiness())
                .isEqualTo(CapabilityReadiness.UNTESTED);
        authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),f.mapping().mappingProposalId(),AuthorityDecision.APPROVE,"Money semantics approved");
        ReadinessEvaluation quote=readiness.evaluate(f.admin().id(),f.merchant().id(),f.run().runId(),ReadinessCapability.GET_QUOTE);
        ReadinessEvaluation purchase=readiness.evaluate(f.admin().id(),f.merchant().id(),f.run().runId(),ReadinessCapability.PURCHASE);
        assertThat(quote.readiness()).isEqualTo(CapabilityReadiness.READY);
        assertThat(quote.evidenceReferences().toString()).contains("contract-test:","mapping-approval:");
        assertThat(purchase.readiness()).isEqualTo(CapabilityReadiness.UNTESTED);
        assertThat(purchase.missingRequirements().toString()).contains("SEARCH_EXACT_PRODUCT_TEST","ORDER_IDEMPOTENCY_TEST","TIMEOUT_RECONCILIATION_TEST");
    }

    @Test void staleMappingAndUnknownOrFailedEvidenceCannotCertifyReady() throws Exception {
        Fixture f=fixture("stale",true);authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),f.mapping().mappingProposalId(),AuthorityDecision.APPROVE,null);
        CapabilityMappingProposal newer=createMapping(f,2,mapper.createObjectNode());mappingValidator.validate(newer);
        newer=mappings.markValidation(f.merchant().id(),f.run().runId(),newer.mappingProposalId(),true);
        force(f.run(),AgentizationState.READY_CANDIDATE,2);
        ReadinessEvaluation result=readiness.evaluate(f.admin().id(),f.merchant().id(),f.run().runId(),ReadinessCapability.GET_QUOTE);
        assertThat(result.readiness()).isEqualTo(CapabilityReadiness.UNTESTED);
        assertThat(result.missingRequirements().toString()).contains("APPROVED_EXECUTABLE_CONTRACT","CURRENT_PASSING_QUOTE_CONTRACT_TEST");
    }

    @Test void manifestIsReducerOwnedImmutableVersionedHashedAndBuyerFiltersReady() throws Exception {
        Fixture f=fixture("manifest",true);authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),f.mapping().mappingProposalId(),AuthorityDecision.APPROVE,null);
        AgentCommerceManifest one=readiness.publishManifestCandidate(f.admin().id(),f.merchant().id(),f.run().runId());
        AgentCommerceManifest two=readiness.publishManifestCandidate(f.admin().id(),f.merchant().id(),f.run().runId());
        assertThat(one.schemaVersion()).isOne();assertThat(two.manifestVersion()).isEqualTo(one.manifestVersion()+1);
        assertThat(two.manifestId()).isNotEqualTo(one.manifestId());assertThat(two.manifestHash()).isEqualTo(one.manifestHash());
        assertThat(one.capabilities()).filteredOn(ManifestCapability::advertised)
                .extracting(ManifestCapability::capability).containsExactly(ReadinessCapability.GET_QUOTE);
        assertThat(readiness.buyerReady(f.merchant().id())).allMatch(c->c.advertised()&&c.readiness()==CapabilityReadiness.READY)
                .extracting(ManifestCapability::capability).containsExactly(ReadinessCapability.GET_QUOTE);
        Identity other=identity("manifest-other");assertThat(readiness.buyerReady(other.merchant().id())).isEmpty();
        assertThat(run(f).state()).isEqualTo(AgentizationState.COMPLETE);
    }

    @Test void typedAgentClarificationToolPausesAndCannotAnswerItsOwnQuestion() throws Exception {
        Fixture f=fixture("tool-clarify",false);force(f.run(),AgentizationState.DIAGNOSING_FAILURE,1);
        assertThat(toolRegistry.permittedTools(AgentizationState.DIAGNOSING_FAILURE))
                .contains(dev.agenticcommerce.gateway.agentization.model.AgentToolName.REQUEST_MERCHANT_CLARIFICATION);
        assertThat(java.util.Arrays.stream(dev.agenticcommerce.gateway.agentization.model.AgentToolName.values()).map(Enum::name))
                .doesNotContain("ANSWER_MERCHANT_CLARIFICATION","SET_READY");
        decisions.actions.add(NextAgentAction.requestClarification(f.artifactId(),f.mapping().mappingProposalId(),
                "Does the merchant guarantee rupee-denominated quote amounts?"));
        var advanced=orchestration.advance(f.admin().id(),f.merchant().id(),f.run().runId());
        assertThat(advanced.run().state()).isEqualTo(AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION);
        assertThat(authority.listClarifications(f.admin().id(),f.merchant().id(),f.run().runId()))
                .singleElement().satisfies(c->{assertThat(c.status()).isEqualTo(ClarificationStatus.OPEN);
                    assertThat(c.respondingActorId()).isNull();});
    }

    @Test void modelManifestToolOnlyRequestsReducerAndCannotOverridePurchase() throws Exception {
        Fixture f=fixture("tool-publish",true);authority.decideMapping(f.admin().id(),f.merchant().id(),f.run().runId(),f.mapping().mappingProposalId(),AuthorityDecision.APPROVE,null);
        decisions.actions.add(NextAgentAction.publishManifestCandidate(f.artifactId(),"Run deterministic readiness reduction"));
        var result=orchestration.advance(f.admin().id(),f.merchant().id(),f.run().runId());
        assertThat(result.run().state()).isEqualTo(AgentizationState.COMPLETE);
        AgentCommerceManifest manifest=readiness.latestManifest(f.admin().id(),f.merchant().id());
        assertThat(manifest.capabilities()).filteredOn(c->c.capability()==ReadinessCapability.PURCHASE)
                .singleElement().extracting(ManifestCapability::readiness).isEqualTo(CapabilityReadiness.UNTESTED);
    }

    private Fixture fixture(String key,boolean pass) throws Exception {
        Identity i=identity(key);String host=key+".example";dns.answers.put(host,List.of(InetAddress.getByName("93.184.216.34")));
        var endpoint=endpointService.registerAndApprove(i.admin().id(),i.merchant().id(),"https://"+host,Set.of("POST"),List.of("/cart/price"));
        var artifact=artifacts.register(i.merchant().id(),endpoint.endpointId(),"1",openApi(host));
        AgentizationRun run=runService.start(i.admin().id(),i.merchant().id(),artifact.artifactId(),CanonicalCapability.GET_QUOTE,30,Instant.now().plusSeconds(1800));
        var transformations=pass?mapper.createObjectNode().put("amount",MappingTransformation.MONEY_RUPEES_TO_PAISE.name()):mapper.createObjectNode();
        Fixture shell=new Fixture(i.merchant(),i.admin(),run,null,artifact.artifactId());
        CapabilityMappingProposal mapping=createMapping(shell,1,transformations);mappingValidator.validate(mapping);
        mapping=mappings.markValidation(i.merchant().id(),run.runId(),mapping.mappingProposalId(),true);
        force(run,AgentizationState.TESTING_CAPABILITY,1);
        contractTests.run(run(fresh(shell)),mapping,GetQuoteTestCase.canonicalRupeesFixture());
        force(run,AgentizationState.READY_CANDIDATE,1);
        return new Fixture(i.merchant(),i.admin(),run,mapping,artifact.artifactId());
    }
    private AgentizationRun run(Fixture f){return runRepository.findByMerchantAndId(f.merchant().id(),f.run().runId()).orElseThrow();}
    private Fixture fresh(Fixture f){return new Fixture(f.merchant(),f.admin(),run(f),f.mapping(),f.artifactId());}
    private CapabilityMappingProposal createMapping(Fixture f,int version,tools.jackson.databind.JsonNode transforms){return mappings.create(f.merchant().id(),f.run().runId(),CanonicalCapability.GET_QUOTE,
            new MappingProposalInput(f.artifactId(),version,"priceCart","POST","/cart/price",mapper.createObjectNode().put("productId","body.productId"),
                    mapper.createObjectNode().put("amount","body.amount").put("currency","body.currency").put("quoteId","body.quoteId"),transforms,
                    mapper.createObjectNode().put("unit","minor"),mapper.createObjectNode().put("field","currency"),"fake-gemini","gemini-3.6-flash"));}
    private void force(AgentizationRun run,AgentizationState state,Integer version){jdbc.sql("UPDATE agentization_run SET orchestration_state=:s,current_mapping_version=:v,completed_at=NULL,terminal_reason=NULL,version=version+1 WHERE run_id=:id")
            .param("s",state.name()).param("v",version).param("id",run.runId()).update();}
    private Identity identity(String key){Merchant m=merchants.create(key,"Merchant "+key);ApplicationActor a=actors.create(key+"-admin",PlatformRole.MERCHANT_ADMIN);memberships.create(m.id(),a.id());return new Identity(m,a);}
    private ProposedPolicyRuleInput rule(PolicyRuleType type,String action,boolean eligible,int days,int priority){return new ProposedPolicyRuleInput(type,"Explicit clause",
            mapper.createObjectNode().put("action",action).put("maximumAgeDays",days),mapper.createObjectNode().put("eligible",eligible),new BigDecimal("0.95"),priority,null,null);}
    private tools.jackson.databind.JsonNode openApi(String host){return mapper.readTree("""
            {"openapi":"3.1.0","servers":[{"url":"https://%s"}],"paths":{"/cart/price":{"post":{"operationId":"priceCart","responses":{"200":{"description":"INR rupees","content":{"application/json":{"schema":{"type":"object","properties":{"amount":{"type":"integer"},"currency":{"type":"string"},"quoteId":{"type":"string"}}}}}}}}}}}
            """.formatted(host));}
    record Identity(Merchant merchant,ApplicationActor admin){}
    record Fixture(Merchant merchant,ApplicationActor admin,AgentizationRun run,CapabilityMappingProposal mapping,UUID artifactId){}

    @TestConfiguration(proxyBeanMethods=false) static class Boundaries {
        @Bean @Primary FakeDns fakeDns(){return new FakeDns();}
        @Bean @Primary FakeTransport fakeTransport(){return new FakeTransport();}
        @Bean @Primary FakePolicyExtractor fakePolicyExtractor(){return new FakePolicyExtractor();}
        @Bean @Primary FakeDecisionProvider fakeDecisionProvider(){return new FakeDecisionProvider();}
    }
    static final class FakeDns implements MerchantDnsResolver {final Map<String,List<InetAddress>> answers=new HashMap<>();
        public List<InetAddress> resolve(String hostname)throws UnknownHostException{if(!answers.containsKey(hostname))throw new UnknownHostException(hostname);return answers.get(hostname);}}
    static final class FakeTransport implements MerchantTransport {public MerchantTransportResponse execute(ValidatedEndpointResolution r,MerchantTransportRequest q){return new MerchantTransportResponse(200,"application/json",
            "{\"amount\":499,\"currency\":\"INR\",\"quoteId\":\"q-499\"}".getBytes(StandardCharsets.UTF_8));}}
    static final class FakePolicyExtractor implements PolicyExtractionProvider {List<ProposedPolicyRuleInput> rules=new ArrayList<>();
        public PolicyExtractionResult extract(PolicyDocument document){return new PolicyExtractionResult("DETERMINISTIC_FAKE_GEMINI","gemini-3.6-flash",List.copyOf(rules));}}
    static final class FakeDecisionProvider implements AgentizationDecisionProvider {final ArrayDeque<NextAgentAction> actions=new ArrayDeque<>();
        public NextAgentAction chooseNextAction(AgentDecisionContext context){NextAgentAction action=actions.pollFirst();if(action==null)throw new IllegalStateException("No test action");return action;}}
}
