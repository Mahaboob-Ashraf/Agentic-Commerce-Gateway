package dev.agenticcommerce.gateway.agentization.authority;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;

import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.agentization.service.MerchantAgentizationAccessService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PolicyAuthorityService {
    public static final int MAX_POLICY_CHARACTERS = 100_000;
    public static final int MAX_EXTRACTED_RULES = 64;
    private static final Set<String> CONDITION_FIELDS = Set.of(
            "action", "maximumAgeDays", "itemCondition", "productCategory");
    private static final Set<String> OUTCOME_FIELDS = Set.of(
            "eligible", "maximumAgeDays", "shippingDays", "effect");

    private final MerchantAgentizationAccessService access;
    private final AgentizationAuthorityRepository repository;
    private final PolicyExtractionProvider extractor;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public PolicyAuthorityService(MerchantAgentizationAccessService access,
            AgentizationAuthorityRepository repository, PolicyExtractionProvider extractor,
            CanonicalJsonService canonical, ObjectMapper mapper) {
        this.access=access; this.repository=repository; this.extractor=extractor;
        this.canonical=canonical; this.mapper=mapper;
    }

    @Transactional
    public PolicyDocument upload(UUID actorId, UUID merchantId, PolicyDocumentType type,
                                 String title, String content) {
        access.requireMerchantAdmin(actorId, merchantId);
        if (type == null) throw invalid("POLICY_DOCUMENT_TYPE_REQUIRED", "Canonical document type is required");
        String normalizedTitle = normalize(title, 256, "POLICY_TITLE_INVALID");
        String normalizedContent = normalizePolicy(content);
        String hash = canonical.hashText(type.name() + "\n" + normalizedContent);
        try {
            return repository.createPolicyDocument(merchantId, type, normalizedTitle, normalizedContent, hash, actorId);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw conflict("POLICY_DOCUMENT_ALREADY_EXISTS", "This exact policy content is already versioned");
        }
    }

    public List<PolicyDocument> documents(UUID actorId, UUID merchantId) {
        access.requireMerchantAdmin(actorId, merchantId); return repository.findPolicyDocuments(merchantId);
    }

    public PolicyDocument document(UUID actorId, UUID merchantId, UUID documentId) {
        access.requireMerchantAdmin(actorId, merchantId);
        return requireDocument(merchantId, documentId);
    }

    @Transactional
    public List<ProposedPolicyRule> extract(UUID actorId, UUID merchantId, UUID documentId) {
        access.requireMerchantAdmin(actorId, merchantId);
        PolicyDocument document = requireDocument(merchantId, documentId);
        return persistExtraction(document, extractor.extract(document));
    }

    /** Internal authority-preserving seam for explicit deterministic fixture bootstraps. */
    @Transactional
    public List<ProposedPolicyRule> recordBootstrapExtraction(
            UUID actorId, UUID merchantId, UUID documentId,
            PolicyExtractionProvider.PolicyExtractionResult extraction) {
        access.requireMerchantAdmin(actorId, merchantId);
        return persistExtraction(requireDocument(merchantId, documentId), extraction);
    }

    private List<ProposedPolicyRule> persistExtraction(
            PolicyDocument document, PolicyExtractionProvider.PolicyExtractionResult extraction) {
        if (extraction == null || extraction.rules() == null || extraction.rules().isEmpty()
                || extraction.rules().size() > MAX_EXTRACTED_RULES) {
            throw invalid("POLICY_EXTRACTION_INVALID", "Policy extraction must return one to 64 typed rules");
        }
        String provider = normalize(extraction.provider(), 128, "POLICY_PROVIDER_INVALID");
        String model = normalize(extraction.model(), 256, "POLICY_MODEL_INVALID");
        List<ProposedPolicyRule> created = new ArrayList<>();
        int version = 1;
        for (ProposedPolicyRuleInput input : extraction.rules()) {
            validateRule(input);
            var material = mapper.createObjectNode();
            material.put("documentId", document.policyDocumentId().toString()); material.put("documentVersion", document.documentVersion());
            material.put("ruleVersion", version); material.put("ruleType", input.ruleType().name());
            material.put("sourceClause", input.sourceClause()); material.set("conditions", input.applicabilityConditions());
            material.set("outcome", input.outcomeEffect());
            if (input.precedencePriority()!=null) material.put("precedencePriority", input.precedencePriority());
            if (input.effectiveFrom()!=null) material.put("effectiveFrom", input.effectiveFrom().toString());
            if (input.effectiveTo()!=null) material.put("effectiveTo", input.effectiveTo().toString());
            created.add(repository.createPolicyRule(document, version++, input, provider, model, canonical.hash(material)));
        }
        return List.copyOf(created);
    }

    public List<ProposedPolicyRule> rules(UUID actorId, UUID merchantId, UUID documentId) {
        access.requireMerchantAdmin(actorId, merchantId);
        if (documentId != null) requireDocument(merchantId, documentId);
        return repository.findPolicyRules(merchantId, documentId);
    }

    @Transactional
    public PolicyRuleApprovalDecision decideRule(UUID actorId, UUID merchantId, UUID ruleId,
            AuthorityDecision decision, String note) {
        access.requireMerchantAdmin(actorId, merchantId);
        ProposedPolicyRule rule = requireRule(merchantId, ruleId);
        if (rule.approvalState() != PolicyApprovalState.PROPOSED) {
            throw conflict("POLICY_RULE_ALREADY_DECIDED", "This exact policy rule version was already decided");
        }
        try {
            return repository.createRuleApproval(rule, decision, actorId, optional(note, 512));
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw conflict("POLICY_RULE_ALREADY_DECIDED", "This exact policy rule version was already decided");
        }
    }

    @Transactional
    public PolicySnapshot publishSnapshot(UUID actorId, UUID merchantId) {
        access.requireMerchantAdmin(actorId, merchantId);
        List<ProposedPolicyRule> approved = repository.findPolicyRules(merchantId, null).stream()
                .filter(rule -> rule.approvalState() == PolicyApprovalState.APPROVED)
                .sorted(Comparator.comparing((ProposedPolicyRule r) -> r.ruleType().name())
                        .thenComparing(ProposedPolicyRule::ruleContentHash)).toList();
        var material = mapper.createObjectNode();
        material.put("merchantId", merchantId.toString());
        var hashes = material.putArray("approvedRuleHashes"); approved.forEach(rule -> hashes.add(rule.ruleContentHash()));
        return repository.createPolicySnapshot(merchantId, actorId, canonical.hash(material), approved);
    }

    public PolicyResolution resolve(UUID merchantId, UUID snapshotId, PolicyResolutionRequest request) {
        PolicySnapshot snapshot = repository.findPolicySnapshot(merchantId, snapshotId)
                .orElseThrow(() -> invalid("POLICY_SNAPSHOT_NOT_FOUND", "Policy snapshot was not found"));
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw invalid("POLICY_ACTION_REQUIRED", "Policy action is required");
        }
        Instant at = request.at() == null ? Instant.now() : request.at();
        List<ProposedPolicyRule> eligible = snapshot.rules().stream()
                .filter(rule -> rule.approvalState() == PolicyApprovalState.APPROVED)
                .filter(rule -> timeApplies(rule, at)).filter(rule -> conditionsApply(rule, request)).toList();
        if (eligible.isEmpty()) return new PolicyResolution(PolicyDecisionOutcome.UNKNOWN,
                "REQUIRED_POLICY_COVERAGE_MISSING", snapshotId, List.of(), List.of());
        int highest = eligible.stream().map(rule -> rule.precedencePriority() == null ? 0 : rule.precedencePriority())
                .max(Integer::compareTo).orElse(0);
        List<ProposedPolicyRule> winners = eligible.stream()
                .filter(rule -> (rule.precedencePriority() == null ? 0 : rule.precedencePriority()) == highest).toList();
        Set<Boolean> decisions = new HashSet<>();
        for (ProposedPolicyRule rule : winners) {
            JsonNode value = rule.outcomeEffect().get("eligible");
            if (value != null && value.isBoolean()) decisions.add(value.booleanValue());
        }
        List<UUID> ruleIds = winners.stream().map(ProposedPolicyRule::policyRuleId).toList();
        List<UUID> documentIds = winners.stream().map(ProposedPolicyRule::policyDocumentId).distinct().toList();
        if (decisions.size() != 1) return new PolicyResolution(PolicyDecisionOutcome.UNKNOWN,
                decisions.isEmpty() ? "AUTHORITATIVE_OUTCOME_MISSING" : "APPROVED_RULE_CONFLICT",
                snapshotId, ruleIds, documentIds);
        return new PolicyResolution(decisions.iterator().next() ? PolicyDecisionOutcome.PASS : PolicyDecisionOutcome.FAIL,
                decisions.iterator().next() ? "APPROVED_POLICY_ALLOWS" : "APPROVED_POLICY_DISALLOWS",
                snapshotId, ruleIds, documentIds);
    }

    private void validateRule(ProposedPolicyRuleInput input) {
        if (input == null || input.ruleType() == null) throw invalid("POLICY_RULE_TYPE_INVALID", "Canonical rule type required");
        normalize(input.sourceClause(), 1000, "POLICY_SOURCE_CLAUSE_INVALID");
        validateObject(input.applicabilityConditions(), CONDITION_FIELDS, "POLICY_CONDITIONS_INVALID");
        validateObject(input.outcomeEffect(), OUTCOME_FIELDS, "POLICY_OUTCOME_INVALID");
        if (input.modelConfidence()!=null && (input.modelConfidence().compareTo(BigDecimal.ZERO)<0
                || input.modelConfidence().compareTo(BigDecimal.ONE)>0)) throw invalid("POLICY_CONFIDENCE_INVALID", "Confidence must be zero to one");
        if (input.precedencePriority()!=null && (input.precedencePriority()<0 || input.precedencePriority()>10000))
            throw invalid("POLICY_PRECEDENCE_INVALID", "Precedence is outside the bounded range");
        if (input.effectiveFrom()!=null && input.effectiveTo()!=null && !input.effectiveTo().isAfter(input.effectiveFrom()))
            throw invalid("POLICY_EFFECTIVE_RANGE_INVALID", "Effective-to must follow effective-from");
        JsonNode days = input.applicabilityConditions().get("maximumAgeDays");
        if (days != null && (!days.canConvertToInt() || days.intValue()<0 || days.intValue()>3650))
            throw invalid("POLICY_DAYS_INVALID", "Policy day values must be bounded integers");
    }

    private void validateObject(JsonNode value, Set<String> allowed, String code) {
        if (value==null || !value.isObject() || value.size()>allowed.size()
                || mapper.writeValueAsString(value).length()>8000) throw invalid(code, "Bounded JSON object required");
        value.propertyNames().forEach(name -> { if (!allowed.contains(name)) throw invalid(code,
                "Unsupported policy field; executable expressions are not allowed"); });
        value.forEach(child -> { if (!(child.isTextual() || child.isBoolean() || child.isIntegralNumber() || child.isNull()))
            throw invalid(code, "Only scalar typed policy fields are allowed"); });
    }

    private boolean conditionsApply(ProposedPolicyRule rule, PolicyResolutionRequest request) {
        JsonNode c = rule.applicabilityConditions();
        if (c.hasNonNull("action") && !c.path("action").asText().equalsIgnoreCase(request.action())) return false;
        if (c.has("maximumAgeDays")) {
            if (request.itemAgeDays()==null || request.itemAgeDays()>c.path("maximumAgeDays").intValue()) return false;
        }
        return !c.hasNonNull("itemCondition") || (request.itemCondition()!=null
                && c.path("itemCondition").asText().equalsIgnoreCase(request.itemCondition()));
    }
    private static boolean timeApplies(ProposedPolicyRule rule, Instant at) {
        return (rule.effectiveFrom()==null || !at.isBefore(rule.effectiveFrom()))
                && (rule.effectiveTo()==null || at.isBefore(rule.effectiveTo()));
    }
    private PolicyDocument requireDocument(UUID merchantId, UUID id) { return repository.findPolicyDocument(merchantId,id)
            .orElseThrow(() -> invalid("POLICY_DOCUMENT_NOT_FOUND", "Tenant-owned policy document was not found")); }
    private ProposedPolicyRule requireRule(UUID merchantId, UUID id) { return repository.findPolicyRule(merchantId,id)
            .orElseThrow(() -> invalid("POLICY_RULE_NOT_FOUND", "Tenant-owned policy rule was not found")); }
    private static String normalizePolicy(String value) {
        if (value==null) throw invalid("POLICY_CONTENT_INVALID", "Policy content is required");
        String normalized=value.replace("\r\n","\n").replace('\r','\n').strip();
        if (normalized.isEmpty() || normalized.length()>MAX_POLICY_CHARACTERS)
            throw invalid("POLICY_CONTENT_INVALID", "Policy content must be between 1 and 100000 characters");
        return normalized;
    }
    private static String normalize(String value,int max,String code) { if(value==null||value.isBlank()) throw invalid(code,"Value is required");
        String n=value.strip().replaceAll("\\s+"," "); if(n.length()>max) throw invalid(code,"Value exceeds limit"); return n; }
    private static String optional(String value,int max){return value==null||value.isBlank()?null:normalize(value,max,"POLICY_NOTE_INVALID");}
    private static AgentizationException invalid(String code,String message){return new AgentizationException(code,HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private static AgentizationException conflict(String code,String message){return new AgentizationException(code,HttpStatus.CONFLICT,message);}
}
