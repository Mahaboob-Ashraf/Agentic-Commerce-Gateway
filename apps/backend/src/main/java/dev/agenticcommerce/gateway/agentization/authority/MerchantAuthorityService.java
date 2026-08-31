package dev.agenticcommerce.gateway.agentization.authority;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;

import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.AgentizationRunService;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.agentization.service.MerchantAgentizationAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MerchantAuthorityService {
    private final MerchantAgentizationAccessService access;
    private final AgentizationRunService runs;
    private final AgentizationRunRepository runRepository;
    private final CapabilityMappingProposalRepository mappings;
    private final AgentizationAuthorityRepository repository;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public MerchantAuthorityService(MerchantAgentizationAccessService access, AgentizationRunService runs,
            AgentizationRunRepository runRepository, CapabilityMappingProposalRepository mappings,
            AgentizationAuthorityRepository repository, CanonicalJsonService canonical, ObjectMapper mapper) {
        this.access = access; this.runs = runs; this.runRepository = runRepository; this.mappings = mappings;
        this.repository = repository; this.canonical = canonical; this.mapper = mapper;
    }

    @Transactional
    public MerchantClarification requestClarification(
            UUID merchantId, UUID runId, UUID mappingId, UUID documentId, UUID ruleId,
            String question, JsonNode evidence, ClarificationKind kind, AgentizationState continuation) {
        AgentizationRun run = requireSystemRun(merchantId, runId);
        MerchantClarification result = createClarification(run, mappingId, documentId, ruleId,
                question, evidence, kind, continuation);
        if (run.state() != AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION) {
            runs.transition(run, AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION,
                    "OPEN_CLARIFICATION:" + result.clarificationId());
        }
        return result;
    }

    public MerchantClarification createAgentClarification(AgentizationRun run, UUID mappingId,
            UUID documentId, UUID ruleId, String question, JsonNode evidence,
            ClarificationKind kind, AgentizationState continuation) {
        return createClarification(run, mappingId, documentId, ruleId, question, evidence, kind, continuation);
    }

    private MerchantClarification createClarification(AgentizationRun run, UUID mappingId,
            UUID documentId, UUID ruleId, String question, JsonNode evidence,
            ClarificationKind kind, AgentizationState continuation) {
        String bounded = normalize(question, 512, "CLARIFICATION_QUESTION_REQUIRED");
        if (evidence == null || !evidence.isArray() || evidence.isEmpty() || evidence.size() > 32) {
            throw invalid("CLARIFICATION_EVIDENCE_REQUIRED", "Clarification requires bounded evidence references");
        }
        if (mappingId != null) requireMapping(run.merchantId(), run.runId(), mappingId);
        return repository.createClarification(run.merchantId(), run.runId(),
                run.currentCapability(), mappingId, documentId, ruleId, bounded, evidence, kind, continuation);
    }

    @Transactional
    public MerchantClarification answerClarification(
            UUID actorId, UUID merchantId, UUID runId, UUID clarificationId, String response) {
        access.requireMerchantAdmin(actorId, merchantId);
        AgentizationRun run = runRepository.findByMerchantAndIdForUpdate(merchantId, runId)
                .orElseThrow(MerchantAuthorityService::notFound);
        MerchantClarification clarification = repository.findClarification(merchantId, clarificationId)
                .filter(item -> item.runId().equals(runId)).orElseThrow(MerchantAuthorityService::notFound);
        if (clarification.status() != ClarificationStatus.OPEN) {
            throw conflict("CLARIFICATION_NOT_OPEN", "Only an open clarification may be answered");
        }
        MerchantClarification answered;
        try {
            answered = repository.answerClarification(merchantId, clarificationId, actorId,
                    normalize(response, 2000, "CLARIFICATION_RESPONSE_REQUIRED"));
        } catch (java.util.NoSuchElementException exception) {
            throw conflict("CLARIFICATION_NOT_OPEN", "Only an open clarification may be answered");
        }
        if (run.state() != AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION) {
            throw conflict("RUN_NOT_WAITING_FOR_CLARIFICATION", "Run is not waiting for this merchant response");
        }
        runs.transition(run, clarification.continuationState(), null);
        return answered;
    }

    public List<MerchantClarification> listClarifications(UUID actorId, UUID merchantId, UUID runId) {
        runs.require(actorId, merchantId, runId);
        return repository.findClarifications(merchantId, runId);
    }

    @Transactional
    public MappingApprovalDecision decideMapping(UUID actorId, UUID merchantId, UUID runId,
            UUID mappingId, AuthorityDecision decision, String note) {
        access.requireMerchantAdmin(actorId, merchantId);
        runs.require(actorId, merchantId, runId);
        CapabilityMappingProposal mapping = requireMapping(merchantId, runId, mappingId);
        if (!"VALID".equals(mapping.validationStatus())) {
            throw conflict("MAPPING_NOT_VALIDATED", "Only an exact validated mapping version may be decided");
        }
        String hash = mappingHash(mapping);
        try {
            return repository.createMappingApproval(merchantId, runId, mappingId, mapping.mappingVersion(),
                    hash, decision, actorId, optional(note, 512));
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw conflict("MAPPING_DECISION_ALREADY_RECORDED", "This exact mapping decision already exists");
        }
    }

    public List<MappingApprovalDecision> mappingApprovals(UUID actorId, UUID merchantId,
                                                           UUID runId, UUID mappingId) {
        runs.require(actorId, merchantId, runId);
        requireMapping(merchantId, runId, mappingId);
        return repository.findMappingApprovals(merchantId, mappingId);
    }

    public String mappingHash(CapabilityMappingProposal mapping) {
        var material = mapper.createObjectNode();
        material.put("mappingProposalId", mapping.mappingProposalId().toString());
        material.put("capability", mapping.capability().name());
        material.put("mappingVersion", mapping.mappingVersion());
        material.put("endpointId", mapping.endpointId().toString());
        material.put("method", mapping.httpMethod()); material.put("path", mapping.pathTemplate());
        material.set("requestBindings", mapping.requestBindings()); material.set("responseBindings", mapping.responseBindings());
        material.set("transformations", mapping.transformations()); material.set("amountInterpretation", mapping.amountInterpretation());
        material.set("currencyInterpretation", mapping.currencyInterpretation());
        material.set("statusNormalization", mapping.statusNormalization());
        material.set("idempotencySemantics", mapping.idempotencySemantics());
        material.set("retrySemantics", mapping.retrySemantics());
        material.put("connectTimeoutMs", mapping.connectTimeoutMs()); material.put("requestTimeoutMs", mapping.requestTimeoutMs());
        material.put("maximumRequestBytes", mapping.maximumRequestBytes()); material.put("maximumResponseBytes", mapping.maximumResponseBytes());
        return canonical.hash(material);
    }

    private AgentizationRun requireSystemRun(UUID merchantId, UUID runId) {
        return runRepository.findByMerchantAndIdForUpdate(merchantId, runId).orElseThrow(MerchantAuthorityService::notFound);
    }
    private CapabilityMappingProposal requireMapping(UUID merchantId, UUID runId, UUID mappingId) {
        return mappings.findByMerchantRunAndId(merchantId, runId, mappingId)
                .orElseThrow(() -> invalid("MAPPING_NOT_FOUND", "Tenant-owned mapping was not found"));
    }
    private static String normalize(String value, int max, String code) {
        if (value == null || value.isBlank()) throw invalid(code, "A non-blank bounded value is required");
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.length() > max) throw invalid(code, "Value exceeds its bounded size");
        return normalized;
    }
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return normalize(value, max, "NOTE_TOO_LARGE");
    }
    private static AgentizationException invalid(String code, String message) {
        return new AgentizationException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    private static AgentizationException conflict(String code, String message) {
        return new AgentizationException(code, HttpStatus.CONFLICT, message);
    }
    private static AgentizationException notFound() {
        return new AgentizationException("AUTHORITY_RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Tenant-owned authority resource was not found");
    }
}
