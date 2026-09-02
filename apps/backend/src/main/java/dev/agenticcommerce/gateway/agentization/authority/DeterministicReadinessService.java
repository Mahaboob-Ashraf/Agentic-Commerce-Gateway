package dev.agenticcommerce.gateway.agentization.authority;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;

import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.AgentizationRunService;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.agentization.service.MerchantAgentizationAccessService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.payment.PaymentRepository;

/** The sole application component that computes and persists capability readiness. */
@Service
public class DeterministicReadinessService {
    public static final List<String> SEARCH_PRODUCTS_REQUIREMENTS = List.of(
            "CATALOGUE_SCHEMA", "EXACT_PRODUCT_RETRIEVAL", "NO_MATCH", "IDENTITY_GATE");
    public static final List<String> GET_QUOTE_REQUIREMENTS = List.of(
            "VALID_MAPPING", "APPROVED_EXECUTABLE_CONTRACT", "CURRENT_PASSING_QUOTE_CONTRACT_TEST",
            "AMOUNT_CURRENCY_NORMALIZATION", "SCHEMA_VALIDATION", "NO_OPEN_CLARIFICATION", "APPROVED_ENDPOINT");
    public static final List<String> PURCHASE_REQUIREMENTS = List.of(
            "SEARCH_EXACT_PRODUCT_TEST", "NO_MATCH_TEST", "QUOTE_CONSISTENCY_EXPIRY_TEST",
            "MONEY_NORMALIZATION_TEST", "STOCK_ENFORCEMENT_TEST", "ORDER_CREATION_CONTRACT_TEST",
            "ORDER_STATE_CONTRACT_TEST", "ORDER_IDEMPOTENCY_TEST", "DUPLICATE_ORDER_PROTECTION_TEST",
            "TIMEOUT_RECONCILIATION_TEST", "SCHEMA_VALIDATION", "APPROVED_POLICY_COVERAGE");

    private final MerchantAgentizationAccessService access;
    private final AgentizationRunService runs;
    private final CapabilityMappingProposalRepository mappings;
    private final CapabilityContractTestRunRepository tests;
    private final ApprovedMerchantEndpointRepository endpoints;
    private final AgentizationAuthorityRepository authority;
    private final MerchantAuthorityService merchantAuthority;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    private final CatalogueRepository catalogues;
    private final PaymentRepository payments;

    public DeterministicReadinessService(MerchantAgentizationAccessService access,
            AgentizationRunService runs, CapabilityMappingProposalRepository mappings,
            CapabilityContractTestRunRepository tests, ApprovedMerchantEndpointRepository endpoints,
            AgentizationAuthorityRepository authority, MerchantAuthorityService merchantAuthority,
            CanonicalJsonService canonical, ObjectMapper mapper, CatalogueRepository catalogues,
            PaymentRepository payments) {
        this.access=access; this.runs=runs; this.mappings=mappings; this.tests=tests; this.endpoints=endpoints;
        this.authority=authority; this.merchantAuthority=merchantAuthority; this.canonical=canonical; this.mapper=mapper;
        this.catalogues=catalogues;
        this.payments=payments;
    }

    public ReadinessEvaluation evaluate(UUID actorId, UUID merchantId, UUID runId,
                                        ReadinessCapability capability) {
        access.requireMerchantAdmin(actorId, merchantId);
        AgentizationRun run = runs.require(actorId, merchantId, runId);
        return evaluatePersisted(run, capability);
    }

    private ReadinessEvaluation evaluatePersisted(AgentizationRun run, ReadinessCapability capability) {
        List<String> required = requirements(capability);
        Set<String> satisfied = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> blocking = new LinkedHashSet<>();
        List<String> refs = new ArrayList<>();
        CapabilityMappingProposal mapping = null;
        CapabilityContractTestRun latestTest = null;
        String mappingHash = null;
        UUID snapshotId = authority.findLatestPolicySnapshot(run.merchantId()).map(PolicySnapshot::policySnapshotId).orElse(null);

        if (capability == ReadinessCapability.SEARCH_PRODUCTS) {
            var catalogue=catalogues.latestPublished(run.merchantId()).orElse(null);
            if(catalogue==null) missing.addAll(SEARCH_PRODUCTS_REQUIREMENTS);
            else {
                var passed=catalogues.passingEvidenceTypes(run.merchantId(),catalogue.id());
                for(String requirement:SEARCH_PRODUCTS_REQUIREMENTS){if(passed.contains(requirement))satisfied.add(requirement);else missing.add(requirement);}
                refs.add("catalogue:"+catalogue.id()+":v"+catalogue.version()+":"+catalogue.contentHash());
            }
        } else if (capability == ReadinessCapability.PURCHASE) {
            missing.addAll(PURCHASE_REQUIREMENTS);
            catalogues.latestPublished(run.merchantId()).ifPresent(c->{var passed=catalogues.passingEvidenceTypes(run.merchantId(),c.id());
                if(passed.contains("EXACT_PRODUCT_RETRIEVAL")){missing.remove("SEARCH_EXACT_PRODUCT_TEST");satisfied.add("SEARCH_EXACT_PRODUCT_TEST");}
                if(passed.contains("NO_MATCH")){missing.remove("NO_MATCH_TEST");satisfied.add("NO_MATCH_TEST");}
                refs.add("catalogue:"+c.id()+":v"+c.version()+":"+c.contentHash());});
            for (String evidence : payments.purchaseReadinessEvidence(run.merchantId())) {
                if (PURCHASE_REQUIREMENTS.contains(evidence)) {
                    missing.remove(evidence);
                    satisfied.add(evidence);
                    refs.add("task010-payment-evidence:" + evidence);
                }
            }
        } else if (capability == ReadinessCapability.from(run.currentCapability()) && run.currentMappingVersion()!=null) {
            mapping = mappings.findByMerchantRunAndVersion(run.merchantId(), run.runId(), run.currentMappingVersion()).orElse(null);
            if (mapping == null) missing.add("VALID_MAPPING");
            else {
                mappingHash = merchantAuthority.mappingHash(mapping);
                CapabilityMappingProposal exactMapping = mapping;
                String exactMappingHash = mappingHash;
                refs.add("mapping:" + mapping.mappingProposalId() + ":v" + mapping.mappingVersion() + ":" + mappingHash);
                if ("VALID".equals(mapping.validationStatus())) {
                    satisfied.add("VALID_MAPPING"); satisfied.add("SCHEMA_VALIDATION");
                } else missing.add("VALID_MAPPING");
                var approvals = authority.findMappingApprovals(run.merchantId(), mapping.mappingProposalId());
                boolean rejected = approvals.stream().anyMatch(a -> a.decision()==AuthorityDecision.REJECT
                        && a.mappingVersion()==exactMapping.mappingVersion() && a.mappingContentHash().equals(exactMappingHash));
                boolean approved = approvals.stream().anyMatch(a -> a.decision()==AuthorityDecision.APPROVE
                        && a.mappingVersion()==exactMapping.mappingVersion() && a.mappingContentHash().equals(exactMappingHash));
                if (rejected) blocking.add("MAPPING_REJECTED_BY_MERCHANT");
                else if (approved) { satisfied.add("APPROVED_EXECUTABLE_CONTRACT");
                    approvals.stream().filter(a -> a.decision()==AuthorityDecision.APPROVE).findFirst()
                            .ifPresent(a -> refs.add("mapping-approval:" + a.approvalId()));
                } else missing.add("APPROVED_EXECUTABLE_CONTRACT");
                if (endpoints.findByMerchantAndId(run.merchantId(), mapping.endpointId()).isPresent())
                    satisfied.add("APPROVED_ENDPOINT"); else blocking.add("ENDPOINT_NOT_APPROVED");
                latestTest = tests.findLatestForMapping(run.merchantId(), run.runId(), mapping.mappingProposalId()).orElse(null);
                if (latestTest == null) missing.add(testRequirement(capability));
                else {
                    refs.add("contract-test:" + latestTest.contractTestRunId() + ":" + latestTest.evidenceHash());
                    if (latestTest.mappingVersion()!=mapping.mappingVersion()) missing.add("CURRENT_MAPPING_CONTRACT_TEST");
                    else if (latestTest.outcome()==ContractTestOutcome.PASS) {
                        satisfied.add(testRequirement(capability));
                        if (capability==ReadinessCapability.GET_QUOTE
                                && latestTest.structuredEvidence().has("normalizedAmountPaise")
                                && "INR".equals(latestTest.structuredEvidence().path("currency").asText()))
                            satisfied.add("AMOUNT_CURRENCY_NORMALIZATION");
                        else if (capability==ReadinessCapability.GET_QUOTE) missing.add("AMOUNT_CURRENCY_NORMALIZATION");
                    } else if (latestTest.outcome()==ContractTestOutcome.FAIL)
                        blocking.add("CURRENT_CONTRACT_TEST_FAILED:" + latestTest.failureCode());
                    else missing.add("CURRENT_CONTRACT_TEST_UNKNOWN:" + latestTest.failureCode());
                }
            }
            if (authority.hasOpenClarification(run.merchantId(), run.runId(), run.currentCapability()))
                missing.add("UNRESOLVED_CLARIFICATION"); else satisfied.add("NO_OPEN_CLARIFICATION");
            for (String item : required) if (!satisfied.contains(item) && blocking.isEmpty()) missing.add(item);
        } else {
            missing.addAll(required);
        }

        CapabilityReadiness readiness = !blocking.isEmpty() ? CapabilityReadiness.BLOCKED
                : missing.isEmpty() && satisfied.containsAll(required) ? CapabilityReadiness.READY
                : CapabilityReadiness.UNTESTED;
        ArrayNode requiredJson=array(required), satisfiedJson=array(satisfied), missingJson=array(missing),
                blockingJson=array(blocking), refsJson=array(refs);
        var hashMaterial=mapper.createObjectNode(); hashMaterial.put("merchantId",run.merchantId().toString());
        hashMaterial.put("runId",run.runId().toString()); hashMaterial.put("capability",capability.name());
        hashMaterial.put("readiness",readiness.name()); hashMaterial.set("required",requiredJson);
        hashMaterial.set("satisfied",satisfiedJson); hashMaterial.set("missing",missingJson);
        hashMaterial.set("blocking",blockingJson); hashMaterial.set("refs",refsJson);
        return authority.createReadinessEvaluation(run.merchantId(),run.runId(),capability,readiness,
                mapping==null?null:mapping.mappingProposalId(),mapping==null?null:mapping.mappingVersion(),mappingHash,
                latestTest==null?null:latestTest.contractTestRunId(),snapshotId,requiredJson,satisfiedJson,missingJson,
                blockingJson,refsJson,canonical.hash(hashMaterial));
    }

    @Transactional
    public AgentCommerceManifest publishManifestCandidate(UUID actorId, UUID merchantId, UUID runId) {
        access.requireMerchantAdmin(actorId, merchantId);
        AgentizationRun run = runs.requireForUpdate(actorId, merchantId, runId);
        if (run.state()!=AgentizationState.READY_CANDIDATE
                && run.state()!=AgentizationState.WAITING_FOR_MERCHANT_APPROVAL
                && run.state()!=AgentizationState.COMPLETE) {
            throw new AgentizationException("RUN_NOT_READY_FOR_REDUCTION",HttpStatus.CONFLICT,
                    "Manifest reduction requires READY_CANDIDATE, approval-waiting, or completed state");
        }
        Map<ReadinessCapability,ReadinessEvaluation> evaluations=new EnumMap<>(ReadinessCapability.class);
        ReadinessCapability targetCapability=ReadinessCapability.from(run.currentCapability());
        for(ReadinessCapability capability:ReadinessCapability.values()) {
            if(capability==targetCapability||capability==ReadinessCapability.PURCHASE)
                evaluations.put(capability,evaluatePersisted(run,capability));
            else evaluations.put(capability,authority.findLatestReadiness(merchantId,capability)
                    .orElseGet(()->evaluatePersisted(run,capability)));
        }
        List<ManifestCapability> entries=evaluations.values().stream().map(e -> new ManifestCapability(
                e.capability(),e.readiness()==CapabilityReadiness.READY,e.readiness(),
                e.readiness()==CapabilityReadiness.READY?e.mappingProposalId():null,e.readinessEvaluationId())).toList();
        UUID snapshotId=authority.findLatestPolicySnapshot(merchantId).map(PolicySnapshot::policySnapshotId).orElse(null);
        var material=mapper.createObjectNode();material.put("schemaVersion",1);material.put("merchantId",merchantId.toString());
        if(snapshotId!=null)material.put("policySnapshotId",snapshotId.toString());else material.putNull("policySnapshotId");
        String catalogueVersion=catalogues.latestPublished(merchantId).map(c->"v"+c.version()+":"+c.contentHash()).orElse(null);
        if(catalogueVersion==null)material.putNull("catalogueVersion");else material.put("catalogueVersion",catalogueVersion);var capabilities=material.putArray("capabilities");
        entries.stream().sorted(java.util.Comparator.comparing(e->e.capability().name())).forEach(e->{var node=capabilities.addObject();
            node.put("capability",e.capability().name());node.put("advertised",e.advertised());node.put("readiness",e.readiness().name());
            if(e.executableMappingProposalId()!=null)node.put("executableContract",e.executableMappingProposalId().toString());else node.putNull("executableContract");
            node.put("evaluationHash",evaluations.get(e.capability()).evaluationHash());});
        AgentCommerceManifest manifest=authority.createManifest(merchantId,runId,snapshotId,catalogueVersion,actorId,canonical.hash(material),entries);
        ReadinessEvaluation target=evaluations.get(ReadinessCapability.from(run.targetCapability()));
        if(run.state()!=AgentizationState.COMPLETE){
            if(target.readiness()==CapabilityReadiness.READY) runs.transition(run,AgentizationState.COMPLETE,"READINESS_REDUCER_CERTIFIED");
            else if(target.readiness()==CapabilityReadiness.BLOCKED) runs.transition(run,AgentizationState.BLOCKED,"READINESS_REDUCER_BLOCKED");
        }
        return manifest;
    }

    public List<ReadinessEvaluation> evaluations(UUID actorId,UUID merchantId,UUID runId){runs.require(actorId,merchantId,runId);return authority.findReadiness(merchantId,runId);}
    public List<AgentCommerceManifest> manifests(UUID actorId,UUID merchantId){access.requireMerchantAdmin(actorId,merchantId);return authority.findManifests(merchantId);}
    public AgentCommerceManifest latestManifest(UUID actorId,UUID merchantId){access.requireMerchantAdmin(actorId,merchantId);return authority.findLatestManifest(merchantId)
            .orElseThrow(()->new AgentizationException("MANIFEST_NOT_FOUND",HttpStatus.NOT_FOUND,"Published manifest was not found"));}
    public List<ManifestCapability> buyerReady(UUID merchantId){return authority.findBuyerReadyCapabilities(merchantId);}

    private static List<String> requirements(ReadinessCapability capability){return switch(capability){case SEARCH_PRODUCTS->SEARCH_PRODUCTS_REQUIREMENTS;case GET_QUOTE->GET_QUOTE_REQUIREMENTS;case PURCHASE->PURCHASE_REQUIREMENTS;
        default->List.of("VALID_MAPPING","APPROVED_EXECUTABLE_CONTRACT","CURRENT_CAPABILITY_CONTRACT_TEST","SCHEMA_VALIDATION","NO_OPEN_CLARIFICATION","APPROVED_ENDPOINT");};}
    private static String testRequirement(ReadinessCapability capability){return capability==ReadinessCapability.GET_QUOTE?"CURRENT_PASSING_QUOTE_CONTRACT_TEST":"CURRENT_CAPABILITY_CONTRACT_TEST";}
    private ArrayNode array(Iterable<String> values){ArrayNode result=mapper.createArrayNode();values.forEach(result::add);return result;}
}
