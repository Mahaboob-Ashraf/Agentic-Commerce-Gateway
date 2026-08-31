package dev.agenticcommerce.gateway.agentization.authority;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;

import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AgentizationAuthorityRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AgentizationAuthorityRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public MerchantClarification createClarification(
            UUID merchantId, UUID runId, CanonicalCapability capability, UUID mappingId,
            UUID documentId, UUID ruleId, String question, JsonNode evidence,
            ClarificationKind kind, AgentizationState continuation) {
        return jdbc.sql("""
                INSERT INTO merchant_clarification (
                    merchant_id, agentization_run_id, capability, mapping_proposal_id,
                    policy_document_id, policy_rule_id, question, evidence_references,
                    clarification_kind, continuation_state)
                VALUES (:merchantId, :runId, :capability, :mappingId, :documentId, :ruleId,
                    :question, CAST(:evidence AS jsonb), :kind, :continuation)
                RETURNING *
                """)
                .param("merchantId", merchantId).param("runId", runId)
                .param("capability", capability.name()).param("mappingId", mappingId)
                .param("documentId", documentId).param("ruleId", ruleId)
                .param("question", question).param("evidence", mapper.writeValueAsString(evidence))
                .param("kind", kind.name()).param("continuation", continuation.name())
                .query(this::mapClarification).single();
    }

    public Optional<MerchantClarification> findClarification(UUID merchantId, UUID clarificationId) {
        return jdbc.sql("SELECT * FROM merchant_clarification WHERE merchant_id=:merchantId AND clarification_id=:id")
                .param("merchantId", merchantId).param("id", clarificationId)
                .query(this::mapClarification).optional();
    }

    public List<MerchantClarification> findClarifications(UUID merchantId, UUID runId) {
        return jdbc.sql("""
                SELECT * FROM merchant_clarification
                WHERE merchant_id=:merchantId AND agentization_run_id=:runId
                ORDER BY created_at, clarification_id
                """).param("merchantId", merchantId).param("runId", runId)
                .query(this::mapClarification).list();
    }

    public MerchantClarification answerClarification(
            UUID merchantId, UUID clarificationId, UUID actorId, String response) {
        return jdbc.sql("""
                UPDATE merchant_clarification SET status='ANSWERED', merchant_response=:response,
                    responding_actor_id=:actorId, answered_at=CURRENT_TIMESTAMP
                WHERE merchant_id=:merchantId AND clarification_id=:id AND status='OPEN'
                RETURNING *
                """).param("response", response).param("actorId", actorId)
                .param("merchantId", merchantId).param("id", clarificationId)
                .query(this::mapClarification).optional().orElseThrow();
    }

    public boolean hasOpenClarification(UUID merchantId, UUID runId, CanonicalCapability capability) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM merchant_clarification
                    WHERE merchant_id=:merchantId AND agentization_run_id=:runId
                      AND capability=:capability AND status='OPEN')
                """).param("merchantId", merchantId).param("runId", runId)
                .param("capability", capability.name()).query(Boolean.class).single();
    }

    public MappingApprovalDecision createMappingApproval(
            UUID merchantId, UUID runId, UUID mappingId, int version, String hash,
            AuthorityDecision decision, UUID actorId, String note) {
        return jdbc.sql("""
                INSERT INTO mapping_approval_decision (
                    merchant_id, agentization_run_id, mapping_proposal_id, mapping_version,
                    mapping_content_hash, decision, approving_actor_id, merchant_note)
                VALUES (:merchantId,:runId,:mappingId,:version,:hash,:decision,:actorId,:note)
                RETURNING *
                """).param("merchantId", merchantId).param("runId", runId)
                .param("mappingId", mappingId).param("version", version).param("hash", hash)
                .param("decision", decision.name()).param("actorId", actorId).param("note", note)
                .query(this::mapMappingApproval).single();
    }

    public List<MappingApprovalDecision> findMappingApprovals(UUID merchantId, UUID mappingId) {
        return jdbc.sql("""
                SELECT * FROM mapping_approval_decision
                WHERE merchant_id=:merchantId AND mapping_proposal_id=:mappingId ORDER BY decided_at
                """).param("merchantId", merchantId).param("mappingId", mappingId)
                .query(this::mapMappingApproval).list();
    }

    public PolicyDocument createPolicyDocument(
            UUID merchantId, PolicyDocumentType type, String title, String content,
            String hash, UUID actorId) {
        return jdbc.sql("""
                INSERT INTO policy_document (merchant_id, document_type, document_version, title,
                    normalized_content, content_hash, uploaded_by_actor_id)
                VALUES (:merchantId,:type,
                    (SELECT COALESCE(MAX(document_version),0)+1 FROM policy_document
                     WHERE merchant_id=:merchantId AND document_type=:type),
                    :title,:content,:hash,:actorId)
                RETURNING *
                """).param("merchantId", merchantId).param("type", type.name())
                .param("title", title).param("content", content).param("hash", hash)
                .param("actorId", actorId).query(this::mapPolicyDocument).single();
    }

    public Optional<PolicyDocument> findPolicyDocument(UUID merchantId, UUID documentId) {
        return jdbc.sql("SELECT * FROM policy_document WHERE merchant_id=:merchantId AND policy_document_id=:id")
                .param("merchantId", merchantId).param("id", documentId)
                .query(this::mapPolicyDocument).optional();
    }

    public List<PolicyDocument> findPolicyDocuments(UUID merchantId) {
        return jdbc.sql("SELECT * FROM policy_document WHERE merchant_id=:merchantId ORDER BY created_at")
                .param("merchantId", merchantId).query(this::mapPolicyDocument).list();
    }

    public ProposedPolicyRule createPolicyRule(
            PolicyDocument document, int ruleVersion, ProposedPolicyRuleInput input,
            String provider, String model, String hash) {
        return jdbc.sql("""
                INSERT INTO proposed_policy_rule (merchant_id, policy_document_id, document_version,
                    rule_version, rule_type, source_clause, applicability_conditions, outcome_effect,
                    model_confidence, precedence_priority, effective_from, effective_to,
                    extraction_provider, extraction_model, rule_content_hash)
                VALUES (:merchantId,:documentId,:documentVersion,:ruleVersion,:ruleType,:sourceClause,
                    CAST(:conditions AS jsonb),CAST(:outcome AS jsonb),:confidence,:priority,
                    :effectiveFrom,:effectiveTo,:provider,:model,:hash)
                RETURNING *
                """).param("merchantId", document.merchantId()).param("documentId", document.policyDocumentId())
                .param("documentVersion", document.documentVersion()).param("ruleVersion", ruleVersion)
                .param("ruleType", input.ruleType().name()).param("sourceClause", input.sourceClause())
                .param("conditions", mapper.writeValueAsString(input.applicabilityConditions()))
                .param("outcome", mapper.writeValueAsString(input.outcomeEffect()))
                .param("confidence", input.modelConfidence()).param("priority", input.precedencePriority())
                .param("effectiveFrom", offset(input.effectiveFrom())).param("effectiveTo", offset(input.effectiveTo()))
                .param("provider", provider).param("model", model).param("hash", hash)
                .query(this::mapPolicyRule).single();
    }

    public Optional<ProposedPolicyRule> findPolicyRule(UUID merchantId, UUID ruleId) {
        return jdbc.sql("SELECT * FROM proposed_policy_rule WHERE merchant_id=:merchantId AND policy_rule_id=:id")
                .param("merchantId", merchantId).param("id", ruleId).query(this::mapPolicyRule).optional();
    }

    public List<ProposedPolicyRule> findPolicyRules(UUID merchantId, UUID documentId) {
        if (documentId == null) {
            return jdbc.sql("""
                    SELECT * FROM proposed_policy_rule WHERE merchant_id=:merchantId
                    ORDER BY created_at, policy_rule_id
                    """).param("merchantId", merchantId).query(this::mapPolicyRule).list();
        }
        return jdbc.sql("""
                SELECT * FROM proposed_policy_rule WHERE merchant_id=:merchantId
                  AND policy_document_id=:documentId
                ORDER BY created_at, policy_rule_id
                """).param("merchantId", merchantId).param("documentId", documentId)
                .query(this::mapPolicyRule).list();
    }

    public PolicyRuleApprovalDecision createRuleApproval(
            ProposedPolicyRule rule, AuthorityDecision decision, UUID actorId, String note) {
        PolicyRuleApprovalDecision result = jdbc.sql("""
                INSERT INTO policy_rule_approval_decision (merchant_id,policy_rule_id,rule_version,
                    rule_content_hash,decision,approving_actor_id,merchant_note)
                VALUES (:merchantId,:ruleId,:version,:hash,:decision,:actorId,:note) RETURNING *
                """).param("merchantId", rule.merchantId()).param("ruleId", rule.policyRuleId())
                .param("version", rule.ruleVersion()).param("hash", rule.ruleContentHash())
                .param("decision", decision.name()).param("actorId", actorId).param("note", note)
                .query(this::mapRuleApproval).single();
        jdbc.sql("""
                UPDATE proposed_policy_rule SET approval_state=:state
                WHERE merchant_id=:merchantId AND policy_rule_id=:ruleId AND approval_state='PROPOSED'
                """).param("state", decision == AuthorityDecision.APPROVE ? "APPROVED" : "REJECTED")
                .param("merchantId", rule.merchantId()).param("ruleId", rule.policyRuleId()).update();
        return result;
    }

    public List<PolicyRuleApprovalDecision> findRuleApprovals(UUID merchantId, UUID ruleId) {
        return jdbc.sql("SELECT * FROM policy_rule_approval_decision WHERE merchant_id=:m AND policy_rule_id=:r")
                .param("m", merchantId).param("r", ruleId).query(this::mapRuleApproval).list();
    }

    public PolicySnapshot createPolicySnapshot(UUID merchantId, UUID actorId, String hash,
                                                List<ProposedPolicyRule> rules) {
        var snapshot = jdbc.sql("""
                INSERT INTO merchant_policy_snapshot (merchant_id,snapshot_version,snapshot_hash,published_by_actor_id)
                VALUES (:merchantId,(SELECT COALESCE(MAX(snapshot_version),0)+1 FROM merchant_policy_snapshot
                    WHERE merchant_id=:merchantId),:hash,:actorId) RETURNING *
                """).param("merchantId", merchantId).param("hash", hash).param("actorId", actorId)
                .query((rs,n) -> mapPolicySnapshot(rs, List.of())).single();
        for (ProposedPolicyRule rule : rules) {
            jdbc.sql("""
                    INSERT INTO merchant_policy_snapshot_rule
                        (policy_snapshot_id,merchant_id,policy_rule_id,rule_version,rule_content_hash)
                    VALUES (:snapshotId,:merchantId,:ruleId,:version,:hash)
                    """).param("snapshotId", snapshot.policySnapshotId()).param("merchantId", merchantId)
                    .param("ruleId", rule.policyRuleId()).param("version", rule.ruleVersion())
                    .param("hash", rule.ruleContentHash()).update();
        }
        return new PolicySnapshot(snapshot.policySnapshotId(), merchantId, snapshot.snapshotVersion(),
                hash, actorId, snapshot.publishedAt(), List.copyOf(rules));
    }

    public Optional<PolicySnapshot> findLatestPolicySnapshot(UUID merchantId) {
        Optional<PolicySnapshot> base = jdbc.sql("""
                SELECT * FROM merchant_policy_snapshot WHERE merchant_id=:merchantId
                ORDER BY snapshot_version DESC LIMIT 1
                """).param("merchantId", merchantId)
                .query((rs,n) -> mapPolicySnapshot(rs, List.of())).optional();
        return base.map(snapshot -> new PolicySnapshot(snapshot.policySnapshotId(), snapshot.merchantId(),
                snapshot.snapshotVersion(), snapshot.snapshotHash(), snapshot.publishedByActorId(),
                snapshot.publishedAt(), findSnapshotRules(snapshot.policySnapshotId(), merchantId)));
    }

    public Optional<PolicySnapshot> findPolicySnapshot(UUID merchantId, UUID snapshotId) {
        return jdbc.sql("SELECT * FROM merchant_policy_snapshot WHERE merchant_id=:m AND policy_snapshot_id=:s")
                .param("m", merchantId).param("s", snapshotId)
                .query((rs,n) -> mapPolicySnapshot(rs, List.of())).optional()
                .map(s -> new PolicySnapshot(s.policySnapshotId(), s.merchantId(), s.snapshotVersion(),
                        s.snapshotHash(), s.publishedByActorId(), s.publishedAt(),
                        findSnapshotRules(s.policySnapshotId(), merchantId)));
    }

    private List<ProposedPolicyRule> findSnapshotRules(UUID snapshotId, UUID merchantId) {
        return jdbc.sql("""
                SELECT r.* FROM proposed_policy_rule r JOIN merchant_policy_snapshot_rule sr
                  ON sr.policy_rule_id=r.policy_rule_id AND sr.merchant_id=r.merchant_id
                WHERE sr.policy_snapshot_id=:snapshotId AND sr.merchant_id=:merchantId
                ORDER BY r.rule_type,r.rule_version
                """).param("snapshotId", snapshotId).param("merchantId", merchantId)
                .query(this::mapPolicyRule).list();
    }

    public ReadinessEvaluation createReadinessEvaluation(
            UUID merchantId, UUID runId, ReadinessCapability capability, CapabilityReadiness readiness,
            UUID mappingId, Integer mappingVersion, String mappingHash, UUID testId, UUID snapshotId,
            JsonNode required, JsonNode satisfied, JsonNode missing, JsonNode blocking,
            JsonNode refs, String evaluationHash) {
        return jdbc.sql("""
                INSERT INTO capability_readiness_evaluation (merchant_id,agentization_run_id,capability,
                    readiness,mapping_proposal_id,mapping_version,mapping_content_hash,contract_test_run_id,
                    policy_snapshot_id,required_evidence,satisfied_evidence,missing_requirements,
                    blocking_evidence,evidence_references,evaluation_hash)
                VALUES (:merchantId,:runId,:capability,:readiness,:mappingId,:mappingVersion,:mappingHash,
                    :testId,:snapshotId,CAST(:required AS jsonb),CAST(:satisfied AS jsonb),
                    CAST(:missing AS jsonb),CAST(:blocking AS jsonb),CAST(:refs AS jsonb),:hash)
                RETURNING *
                """).param("merchantId", merchantId).param("runId", runId)
                .param("capability", capability.name()).param("readiness", readiness.name())
                .param("mappingId", mappingId).param("mappingVersion", mappingVersion)
                .param("mappingHash", mappingHash).param("testId", testId).param("snapshotId", snapshotId)
                .param("required", mapper.writeValueAsString(required)).param("satisfied", mapper.writeValueAsString(satisfied))
                .param("missing", mapper.writeValueAsString(missing)).param("blocking", mapper.writeValueAsString(blocking))
                .param("refs", mapper.writeValueAsString(refs)).param("hash", evaluationHash)
                .query(this::mapReadiness).single();
    }

    public List<ReadinessEvaluation> findReadiness(UUID merchantId, UUID runId) {
        return jdbc.sql("""
                SELECT * FROM capability_readiness_evaluation WHERE merchant_id=:m AND agentization_run_id=:r
                ORDER BY evaluated_at, capability
                """).param("m", merchantId).param("r", runId).query(this::mapReadiness).list();
    }

    public AgentCommerceManifest createManifest(
            UUID merchantId, UUID runId, UUID snapshotId, UUID actorId, String hash,
            List<ManifestCapability> capabilities) {
        return createManifest(merchantId, runId, snapshotId, null, actorId, hash, capabilities);
    }

    public AgentCommerceManifest createManifest(
            UUID merchantId, UUID runId, UUID snapshotId, String catalogueVersion, UUID actorId, String hash,
            List<ManifestCapability> capabilities) {
        AgentCommerceManifest manifest = jdbc.sql("""
                INSERT INTO agent_commerce_manifest (merchant_id,agentization_run_id,manifest_version,
                    policy_snapshot_id,catalogue_version,publication_actor_id,publication_component,manifest_hash)
                VALUES (:merchantId,:runId,(SELECT COALESCE(MAX(manifest_version),0)+1
                    FROM agent_commerce_manifest WHERE merchant_id=:merchantId),:snapshotId,:catalogueVersion,:actorId,
                    'DETERMINISTIC_READINESS_REDUCER',:hash) RETURNING *
                """).param("merchantId", merchantId).param("runId", runId).param("snapshotId", snapshotId).param("catalogueVersion", catalogueVersion)
                .param("actorId", actorId).param("hash", hash)
                .query((rs,n) -> mapManifest(rs, List.of())).single();
        for (ManifestCapability capability : capabilities) {
            jdbc.sql("""
                    INSERT INTO agent_commerce_manifest_capability (manifest_id,merchant_id,capability,
                        advertised,readiness,executable_mapping_proposal_id,readiness_evaluation_id)
                    VALUES (:manifestId,:merchantId,:capability,:advertised,:readiness,:mappingId,:evaluationId)
                    """).param("manifestId", manifest.manifestId()).param("merchantId", merchantId)
                    .param("capability", capability.capability().name()).param("advertised", capability.advertised())
                    .param("readiness", capability.readiness().name())
                    .param("mappingId", capability.executableMappingProposalId())
                    .param("evaluationId", capability.readinessEvaluationId()).update();
        }
        return new AgentCommerceManifest(manifest.manifestId(), 1, merchantId, runId,
                manifest.manifestVersion(), snapshotId, catalogueVersion, actorId,
                "DETERMINISTIC_READINESS_REDUCER", manifest.publishedAt(), hash, List.copyOf(capabilities));
    }

    public List<AgentCommerceManifest> findManifests(UUID merchantId) {
        return jdbc.sql("SELECT * FROM agent_commerce_manifest WHERE merchant_id=:m ORDER BY manifest_version")
                .param("m", merchantId).query((rs,n) -> mapManifest(rs, List.of())).list().stream()
                .map(m -> withCapabilities(m, findManifestCapabilities(merchantId, m.manifestId(), false))).toList();
    }

    public Optional<AgentCommerceManifest> findLatestManifest(UUID merchantId) {
        return jdbc.sql("SELECT * FROM agent_commerce_manifest WHERE merchant_id=:m ORDER BY manifest_version DESC LIMIT 1")
                .param("m", merchantId).query((rs,n) -> mapManifest(rs, List.of())).optional()
                .map(m -> withCapabilities(m, findManifestCapabilities(merchantId, m.manifestId(), false)));
    }

    public List<ManifestCapability> findBuyerReadyCapabilities(UUID merchantId) {
        Optional<AgentCommerceManifest> latest = findLatestManifest(merchantId);
        return latest.map(m -> findManifestCapabilities(merchantId, m.manifestId(), true)).orElse(List.of());
    }

    private List<ManifestCapability> findManifestCapabilities(UUID merchantId, UUID manifestId, boolean readyOnly) {
        String filter = readyOnly ? " AND advertised AND readiness='READY'" : "";
        return jdbc.sql("""
                SELECT * FROM agent_commerce_manifest_capability
                WHERE merchant_id=:m AND manifest_id=:id
                """ + filter + " ORDER BY capability")
                .param("m", merchantId).param("id", manifestId)
                .query((rs,n) -> new ManifestCapability(ReadinessCapability.valueOf(rs.getString("capability")),
                        rs.getBoolean("advertised"), CapabilityReadiness.valueOf(rs.getString("readiness")),
                        rs.getObject("executable_mapping_proposal_id", UUID.class),
                        rs.getObject("readiness_evaluation_id", UUID.class))).list();
    }

    private static AgentCommerceManifest withCapabilities(AgentCommerceManifest m, List<ManifestCapability> c) {
        return new AgentCommerceManifest(m.manifestId(), m.schemaVersion(), m.merchantId(), m.runId(),
                m.manifestVersion(), m.policySnapshotId(), m.catalogueVersion(), m.publicationActorId(),
                m.publicationComponent(), m.publishedAt(), m.manifestHash(), c);
    }

    private MerchantClarification mapClarification(ResultSet rs, int n) throws SQLException {
        return new MerchantClarification(rs.getObject("clarification_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getObject("agentization_run_id", UUID.class),
                CanonicalCapability.valueOf(rs.getString("capability")),
                rs.getObject("mapping_proposal_id", UUID.class), rs.getObject("policy_document_id", UUID.class),
                rs.getObject("policy_rule_id", UUID.class), rs.getString("question"),
                mapper.readTree(rs.getString("evidence_references")),
                ClarificationKind.valueOf(rs.getString("clarification_kind")),
                AgentizationState.valueOf(rs.getString("continuation_state")),
                ClarificationStatus.valueOf(rs.getString("status")), rs.getString("merchant_response"),
                rs.getObject("responding_actor_id", UUID.class), instant(rs,"created_at"),
                nullableInstant(rs,"answered_at"), nullableInstant(rs,"cancelled_at"));
    }

    private MappingApprovalDecision mapMappingApproval(ResultSet rs, int n) throws SQLException {
        return new MappingApprovalDecision(rs.getObject("mapping_approval_id",UUID.class),
                rs.getObject("merchant_id",UUID.class),rs.getObject("agentization_run_id",UUID.class),
                rs.getObject("mapping_proposal_id",UUID.class),rs.getInt("mapping_version"),
                rs.getString("mapping_content_hash"),AuthorityDecision.valueOf(rs.getString("decision")),
                rs.getObject("approving_actor_id",UUID.class),rs.getString("merchant_note"),instant(rs,"decided_at"));
    }

    private PolicyDocument mapPolicyDocument(ResultSet rs, int n) throws SQLException {
        return new PolicyDocument(rs.getObject("policy_document_id",UUID.class),rs.getObject("merchant_id",UUID.class),
                PolicyDocumentType.valueOf(rs.getString("document_type")),rs.getInt("document_version"),
                rs.getString("title"),rs.getString("normalized_content"),rs.getString("content_hash"),
                rs.getObject("uploaded_by_actor_id",UUID.class),instant(rs,"created_at"));
    }

    private ProposedPolicyRule mapPolicyRule(ResultSet rs, int n) throws SQLException {
        return new ProposedPolicyRule(rs.getObject("policy_rule_id",UUID.class),rs.getObject("merchant_id",UUID.class),
                rs.getObject("policy_document_id",UUID.class),rs.getInt("document_version"),rs.getInt("rule_version"),
                PolicyRuleType.valueOf(rs.getString("rule_type")),rs.getString("source_clause"),
                mapper.readTree(rs.getString("applicability_conditions")),mapper.readTree(rs.getString("outcome_effect")),
                rs.getBigDecimal("model_confidence"),(Integer)rs.getObject("precedence_priority"),
                nullableInstant(rs,"effective_from"),nullableInstant(rs,"effective_to"),
                rs.getString("extraction_provider"),rs.getString("extraction_model"),rs.getString("rule_content_hash"),
                PolicyApprovalState.valueOf(rs.getString("approval_state")),instant(rs,"created_at"));
    }

    private PolicyRuleApprovalDecision mapRuleApproval(ResultSet rs, int n) throws SQLException {
        return new PolicyRuleApprovalDecision(rs.getObject("policy_rule_approval_id",UUID.class),
                rs.getObject("merchant_id",UUID.class),rs.getObject("policy_rule_id",UUID.class),
                rs.getInt("rule_version"),rs.getString("rule_content_hash"),
                AuthorityDecision.valueOf(rs.getString("decision")),rs.getObject("approving_actor_id",UUID.class),
                rs.getString("merchant_note"),instant(rs,"decided_at"));
    }

    private PolicySnapshot mapPolicySnapshot(ResultSet rs, List<ProposedPolicyRule> rules) throws SQLException {
        return new PolicySnapshot(rs.getObject("policy_snapshot_id",UUID.class),rs.getObject("merchant_id",UUID.class),
                rs.getInt("snapshot_version"),rs.getString("snapshot_hash"),
                rs.getObject("published_by_actor_id",UUID.class),instant(rs,"published_at"),rules);
    }

    private ReadinessEvaluation mapReadiness(ResultSet rs, int n) throws SQLException {
        return new ReadinessEvaluation(rs.getObject("readiness_evaluation_id",UUID.class),
                rs.getObject("merchant_id",UUID.class),rs.getObject("agentization_run_id",UUID.class),
                ReadinessCapability.valueOf(rs.getString("capability")),
                CapabilityReadiness.valueOf(rs.getString("readiness")),rs.getObject("mapping_proposal_id",UUID.class),
                (Integer)rs.getObject("mapping_version"),rs.getString("mapping_content_hash"),
                rs.getObject("contract_test_run_id",UUID.class),rs.getObject("policy_snapshot_id",UUID.class),
                mapper.readTree(rs.getString("required_evidence")),mapper.readTree(rs.getString("satisfied_evidence")),
                mapper.readTree(rs.getString("missing_requirements")),mapper.readTree(rs.getString("blocking_evidence")),
                mapper.readTree(rs.getString("evidence_references")),rs.getString("evaluation_hash"),
                instant(rs,"evaluated_at"));
    }

    private AgentCommerceManifest mapManifest(ResultSet rs, List<ManifestCapability> capabilities) throws SQLException {
        return new AgentCommerceManifest(rs.getObject("manifest_id",UUID.class),rs.getInt("schema_version"),
                rs.getObject("merchant_id",UUID.class),rs.getObject("agentization_run_id",UUID.class),
                rs.getInt("manifest_version"),rs.getObject("policy_snapshot_id",UUID.class),
                rs.getString("catalogue_version"),rs.getObject("publication_actor_id",UUID.class),
                rs.getString("publication_component"),instant(rs,"published_at"),rs.getString("manifest_hash"),capabilities);
    }

    private static OffsetDateTime offset(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    private static java.time.Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }
    private static java.time.Instant nullableInstant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
