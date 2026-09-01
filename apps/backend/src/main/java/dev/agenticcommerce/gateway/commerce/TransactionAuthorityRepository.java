package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.intent.BuyerRepository;
import dev.agenticcommerce.gateway.onboarding.OnboardingModels.FulfilmentSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class TransactionAuthorityRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final BuyerRepository buyers;

    public TransactionAuthorityRepository(JdbcClient jdbc, ObjectMapper mapper, BuyerRepository buyers) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.buyers = buyers;
    }

    public Optional<MerchantAuthorityContext> currentMerchantAuthority(UUID merchantId) {
        return jdbc.sql("""
                WITH latest AS (
                    SELECT * FROM agent_commerce_manifest
                    WHERE merchant_id=:merchant
                    ORDER BY manifest_version DESC LIMIT 1
                )
                SELECT latest.merchant_id,latest.manifest_id,latest.manifest_version,
                    latest.manifest_hash,latest.catalogue_version,latest.policy_snapshot_id,
                    policy.snapshot_version,policy.snapshot_hash,
                    quote.advertised quote_advertised,quote.readiness quote_readiness,
                    quote.executable_mapping_proposal_id quote_mapping_id,
                    quote.readiness_evaluation_id quote_evaluation_id,
                    availability.advertised availability_advertised,
                    availability.readiness availability_readiness,
                    availability.executable_mapping_proposal_id availability_mapping_id,
                    availability.readiness_evaluation_id availability_evaluation_id,
                    purchase.readiness purchase_readiness
                FROM latest
                LEFT JOIN merchant_policy_snapshot policy
                    ON policy.policy_snapshot_id=latest.policy_snapshot_id
                    AND policy.merchant_id=latest.merchant_id
                LEFT JOIN agent_commerce_manifest_capability quote
                    ON quote.manifest_id=latest.manifest_id AND quote.capability='GET_QUOTE'
                LEFT JOIN agent_commerce_manifest_capability availability
                    ON availability.manifest_id=latest.manifest_id
                    AND availability.capability='GET_AVAILABILITY'
                LEFT JOIN agent_commerce_manifest_capability purchase
                    ON purchase.manifest_id=latest.manifest_id AND purchase.capability='PURCHASE'
                """).param("merchant", merchantId).query((rs, row) -> new MerchantAuthorityContext(
                rs.getObject("merchant_id", UUID.class), rs.getObject("manifest_id", UUID.class),
                rs.getInt("manifest_version"), rs.getString("manifest_hash").strip(),
                rs.getObject("policy_snapshot_id", UUID.class), (Integer) rs.getObject("snapshot_version"),
                strip(rs.getString("snapshot_hash")), rs.getString("catalogue_version"),
                capability("GET_QUOTE", rs, "quote"),
                capability("GET_AVAILABILITY", rs, "availability"),
                rs.getString("purchase_readiness"))).optional();
    }

    public AvailabilityRefresh createAvailability(
            CandidateCart cart, MerchantAuthorityContext context, EvidenceOutcome outcome,
            String reasonCode, Instant observedAt, Instant expiresAt, String evidenceHash,
            List<AvailabilityItemEvidence> items) {
        CapabilityBinding binding = context.availabilityCapability();
        AvailabilityRefresh base = jdbc.sql("""
                INSERT INTO authoritative_availability_refresh(
                    thread_id,buyer_actor_id,merchant_id,cart_id,cart_version,cart_hash,
                    manifest_id,manifest_version,readiness_evaluation_id,
                    executable_mapping_proposal_id,outcome,reason_code,observed_at,expires_at,evidence_hash)
                VALUES(:thread,:buyer,:merchant,:cart,:cartVersion,:cartHash,:manifest,:manifestVersion,
                    :evaluation,:mapping,:outcome,:reason,:observed,:expires,:hash)
                RETURNING *
                """).param("thread", cart.threadId()).param("buyer", cart.buyerActorId())
                .param("merchant", cart.merchantId()).param("cart", cart.cartId())
                .param("cartVersion", cart.cartVersion()).param("cartHash", cart.cartHash())
                .param("manifest", context.manifestId()).param("manifestVersion", context.manifestVersion())
                .param("evaluation", binding == null ? null : binding.readinessEvaluationId())
                .param("mapping", binding == null ? null : binding.executableMappingProposalId())
                .param("outcome", outcome.name()).param("reason", reasonCode)
                .param("observed", utc(observedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expires", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("hash", evidenceHash).query((rs, row) -> availability(rs, List.of())).single();
        for (AvailabilityItemEvidence item : items) {
            jdbc.sql("""
                    INSERT INTO authoritative_availability_item(
                        availability_refresh_id,thread_id,buyer_actor_id,merchant_id,catalogue_version_id,
                        product_id,merchant_sku,variant,requested_quantity,available,
                        authoritative_quantity,outcome,reason_code,merchant_observed_at,
                        merchant_expires_at,response_hash)
                    VALUES(:refresh,:thread,:buyer,:merchant,:catalogue,:product,:sku,:variant,:requested,
                        :available,:quantity,:outcome,:reason,:observed,:expires,:hash)
                    """).param("refresh", base.availabilityRefreshId()).param("thread", cart.threadId())
                    .param("buyer", cart.buyerActorId()).param("merchant", cart.merchantId())
                    .param("catalogue", item.catalogueVersionId()).param("product", item.productId())
                    .param("sku", item.merchantSku()).param("variant", item.variant())
                    .param("requested", item.requestedQuantity()).param("available", item.available())
                    .param("quantity", item.authoritativeQuantity()).param("outcome", item.outcome().name())
                    .param("reason", item.reasonCode())
                    .param("observed", utc(item.merchantObservedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("expires", utc(item.merchantExpiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("hash", item.responseHash()).update();
        }
        return findAvailability(base.availabilityRefreshId()).orElseThrow();
    }

    public Optional<AvailabilityRefresh> findAvailability(UUID refreshId) {
        return jdbc.sql("SELECT * FROM authoritative_availability_refresh WHERE availability_refresh_id=:id")
                .param("id", refreshId).query((rs, row) -> availability(rs, List.of())).optional()
                .map(value -> withAvailabilityItems(value, availabilityItems(value)));
    }

    public ServiceabilityEvidence createServiceability(
            CandidateCart cart, MerchantAuthorityContext context, EvidenceOutcome outcome,
            ServiceabilitySource source, String sourceReference, String locationReferenceHash,
            String reasonCode, Instant observedAt, Instant expiresAt, String evidenceHash,
            FulfilmentSnapshot snapshot) {
        return jdbc.sql("""
                INSERT INTO authoritative_serviceability_evidence(
                    thread_id,buyer_actor_id,merchant_id,cart_id,cart_version,cart_hash,
                    manifest_id,manifest_version,outcome,source_type,source_reference,
                    location_reference_hash,reason_code,observed_at,expires_at,evidence_hash,
                    fulfilment_snapshot_id,fulfilment_snapshot_hash,delivery_option)
                VALUES(:thread,:buyer,:merchant,:cart,:cartVersion,:cartHash,:manifest,:manifestVersion,
                    :outcome,:source,:reference,:locationHash,:reason,:observed,:expires,:hash,
                    :snapshot,:snapshotHash,:deliveryOption)
                RETURNING *
                """).param("thread", cart.threadId()).param("buyer", cart.buyerActorId())
                .param("merchant", cart.merchantId()).param("cart", cart.cartId())
                .param("cartVersion", cart.cartVersion()).param("cartHash", cart.cartHash())
                .param("manifest", context.manifestId()).param("manifestVersion", context.manifestVersion())
                .param("outcome", outcome.name()).param("source", source.name())
                .param("reference", sourceReference).param("locationHash", locationReferenceHash)
                .param("reason", reasonCode)
                .param("observed", utc(observedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expires", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("hash", evidenceHash).param("snapshot", snapshot.id())
                .param("snapshotHash", snapshot.snapshotHash()).param("deliveryOption", snapshot.deliveryOption())
                .query(this::serviceability).single();
    }

    public Optional<ServiceabilityEvidence> findServiceability(UUID evidenceId) {
        return jdbc.sql("SELECT * FROM authoritative_serviceability_evidence WHERE serviceability_evidence_id=:id")
                .param("id", evidenceId).query(this::serviceability).optional();
    }

    public AuthorityRefresh createAuthorityRefresh(
            CandidateCart cart, MerchantQuote quote, AvailabilityRefresh availability,
            ServiceabilityEvidence serviceability, ConstraintCertificate certificate,
            MerchantAuthorityContext context, EvidenceOutcome outcome, List<String> refs,
            String refreshHash, Instant refreshedAt) {
        UUID id = jdbc.sql("""
                INSERT INTO transaction_authority_refresh(
                    thread_id,buyer_actor_id,merchant_id,cart_id,cart_version,cart_hash,
                    quote_record_id,quote_hash,availability_refresh_id,availability_hash,
                    serviceability_evidence_id,serviceability_hash,constraint_certificate_id,
                    constraint_certificate_hash,manifest_id,manifest_version,policy_snapshot_id,
                    policy_snapshot_version,policy_snapshot_hash,outcome,evidence_references,
                    refresh_hash,refreshed_at)
                VALUES(:thread,:buyer,:merchant,:cart,:cartVersion,:cartHash,:quote,:quoteHash,
                    :availability,:availabilityHash,:serviceability,:serviceabilityHash,:certificate,
                    :certificateHash,:manifest,:manifestVersion,:policy,:policyVersion,:policyHash,
                    :outcome,CAST(:refs AS jsonb),:hash,:refreshed)
                RETURNING authority_refresh_id
                """).param("thread", cart.threadId()).param("buyer", cart.buyerActorId())
                .param("merchant", cart.merchantId()).param("cart", cart.cartId())
                .param("cartVersion", cart.cartVersion()).param("cartHash", cart.cartHash())
                .param("quote", quote.quoteRecordId()).param("quoteHash", quote.evidenceHash())
                .param("availability", availability.availabilityRefreshId())
                .param("availabilityHash", availability.evidenceHash())
                .param("serviceability", serviceability.serviceabilityEvidenceId())
                .param("serviceabilityHash", serviceability.evidenceHash())
                .param("certificate", certificate.certificateId())
                .param("certificateHash", certificate.certificateHash())
                .param("manifest", context.manifestId()).param("manifestVersion", context.manifestVersion())
                .param("policy", context.policySnapshotId()).param("policyVersion", context.policySnapshotVersion())
                .param("policyHash", context.policySnapshotHash()).param("outcome", outcome.name())
                .param("refs", mapper.writeValueAsString(refs)).param("hash", refreshHash)
                .param("refreshed", utc(refreshedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(UUID.class).single();
        attachAuthorityRefresh(cart.threadId(), cart.buyerActorId(), id,
                quote.quoteRecordId(), certificate.certificateId());
        return findAuthorityRefresh(cart.buyerActorId(), cart.threadId(), id).orElseThrow();
    }

    public Optional<AuthorityRefresh> latestAuthorityRefresh(UUID buyerId, UUID threadId) {
        return jdbc.sql("""
                SELECT refresh.* FROM transaction_authority_refresh refresh
                JOIN commerce_thread thread
                    ON thread.current_authority_refresh_id=refresh.authority_refresh_id
                    AND thread.thread_id=refresh.thread_id
                    AND thread.buyer_actor_id=refresh.buyer_actor_id
                WHERE thread.thread_id=:thread AND thread.buyer_actor_id=:buyer
                """).param("thread", threadId).param("buyer", buyerId)
                .query(this::authorityRefresh).optional();
    }

    public Optional<AuthorityRefresh> findAuthorityRefresh(UUID buyerId, UUID threadId, UUID id) {
        return jdbc.sql("""
                SELECT * FROM transaction_authority_refresh
                WHERE authority_refresh_id=:id AND thread_id=:thread AND buyer_actor_id=:buyer
                """).param("id", id).param("thread", threadId).param("buyer", buyerId)
                .query(this::authorityRefresh).optional();
    }

    public TransactionProposal createProposal(
            ProposalDraft draft, JsonNode canonicalMaterial, String proposalHash) {
        UUID proposalId = jdbc.sql("""
                INSERT INTO transaction_proposal(
                    buyer_actor_id,thread_id,merchant_id,authority_refresh_id,authority_refresh_hash,
                    intent_id,intent_version,intent_hash,cart_id,cart_version,cart_hash,
                    constraint_certificate_id,constraint_certificate_hash,quote_record_id,quote_hash,
                    merchant_quote_id,merchant_quote_version,availability_refresh_id,availability_hash,
                    serviceability_evidence_id,serviceability_hash,policy_snapshot_id,
                    policy_snapshot_version,policy_snapshot_hash,catalogue_version_id,action_type,
                    subtotal_minor,tax_minor,fees_minor,delivery_minor,final_amount_minor,currency,
                    quote_expires_at,proposal_expires_at,canonical_schema_version,canonical_material,
                    proposal_hash)
                VALUES(:buyer,:thread,:merchant,:refresh,:refreshHash,:intent,:intentVersion,:intentHash,
                    :cart,:cartVersion,:cartHash,:certificate,:certificateHash,:quote,:quoteHash,
                    :merchantQuoteId,:merchantQuoteVersion,:availability,:availabilityHash,
                    :serviceability,:serviceabilityHash,:policy,:policyVersion,:policyHash,:catalogue,
                    :action,:subtotal,:tax,:fees,:delivery,:final,:currency,:quoteExpiry,:proposalExpiry,
                    :schema,CAST(:material AS jsonb),:hash)
                RETURNING proposal_id
                """).param("buyer", draft.buyerActorId()).param("thread", draft.threadId())
                .param("merchant", draft.merchantId()).param("refresh", draft.authorityRefreshId())
                .param("refreshHash", draft.authorityRefreshHash()).param("intent", draft.intentId())
                .param("intentVersion", draft.intentVersion()).param("intentHash", draft.intentHash())
                .param("cart", draft.cartId()).param("cartVersion", draft.cartVersion())
                .param("cartHash", draft.cartHash()).param("certificate", draft.constraintCertificateId())
                .param("certificateHash", draft.constraintCertificateHash())
                .param("quote", draft.quoteRecordId()).param("quoteHash", draft.quoteHash())
                .param("merchantQuoteId", draft.merchantQuoteId())
                .param("merchantQuoteVersion", draft.merchantQuoteVersion())
                .param("availability", draft.availabilityRefreshId())
                .param("availabilityHash", draft.availabilityHash())
                .param("serviceability", draft.serviceabilityEvidenceId())
                .param("serviceabilityHash", draft.serviceabilityHash())
                .param("policy", draft.policySnapshotId()).param("policyVersion", draft.policySnapshotVersion())
                .param("policyHash", draft.policySnapshotHash()).param("catalogue", draft.catalogueVersionId())
                .param("action", draft.actionType().name()).param("subtotal", draft.subtotalMinor())
                .param("tax", draft.taxMinor()).param("fees", draft.feesMinor())
                .param("delivery", draft.deliveryMinor()).param("final", draft.finalAmountMinor())
                .param("currency", draft.currency())
                .param("quoteExpiry", utc(draft.quoteExpiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("proposalExpiry", utc(draft.proposalExpiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("schema", TransactionProposalCanonicalizer.SCHEMA_VERSION)
                .param("material", mapper.writeValueAsString(canonicalMaterial)).param("hash", proposalHash)
                .query(UUID.class).single();
        int lineNumber = 0;
        for (ProposalLineItem item : draft.lineItems()) {
            lineNumber++;
            jdbc.sql("""
                    INSERT INTO transaction_proposal_line_item(
                        proposal_id,buyer_actor_id,thread_id,merchant_id,catalogue_version_id,
                        line_number,product_id,merchant_sku,variant,quantity,
                        unit_amount_minor,line_amount_minor)
                    VALUES(:proposal,:buyer,:thread,:merchant,:catalogue,:line,:product,:sku,:variant,
                        :quantity,:unit,:lineAmount)
                    """).param("proposal", proposalId).param("buyer", draft.buyerActorId())
                    .param("thread", draft.threadId()).param("merchant", draft.merchantId())
                    .param("catalogue", draft.catalogueVersionId()).param("line", lineNumber)
                    .param("product", item.productId()).param("sku", item.merchantSku())
                    .param("variant", item.variant()).param("quantity", item.quantity())
                    .param("unit", item.unitAmountMinor()).param("lineAmount", item.lineAmountMinor()).update();
        }
        attachProposal(draft.threadId(), draft.buyerActorId(), proposalId);
        return findProposal(draft.buyerActorId(), proposalId).orElseThrow();
    }

    public Optional<TransactionProposal> findProposal(UUID buyerId, UUID proposalId) {
        return jdbc.sql("SELECT * FROM transaction_proposal WHERE proposal_id=:id AND buyer_actor_id=:buyer")
                .param("id", proposalId).param("buyer", buyerId)
                .query((rs, row) -> proposal(rs, List.of())).optional()
                .map(value -> withProposalLines(value, proposalLines(value)));
    }

    public Optional<TransactionProposal> findProposalForUpdate(UUID buyerId, UUID proposalId) {
        return jdbc.sql("SELECT * FROM transaction_proposal WHERE proposal_id=:id AND buyer_actor_id=:buyer FOR UPDATE")
                .param("id", proposalId).param("buyer", buyerId)
                .query((rs, row) -> proposal(rs, List.of())).optional()
                .map(value -> withProposalLines(value, proposalLines(value)));
    }

    public Optional<TransactionProposal> currentProposal(UUID buyerId, UUID threadId) {
        return jdbc.sql("""
                SELECT proposal.* FROM transaction_proposal proposal
                JOIN commerce_thread thread ON thread.current_proposal_id=proposal.proposal_id
                WHERE thread.thread_id=:thread AND thread.buyer_actor_id=:buyer
                """).param("thread", threadId).param("buyer", buyerId)
                .query((rs, row) -> proposal(rs, List.of())).optional()
                .map(value -> withProposalLines(value, proposalLines(value)));
    }

    public Optional<TransactionProposal> proposalForRefresh(UUID buyerId, UUID refreshId) {
        return jdbc.sql("""
                SELECT * FROM transaction_proposal
                WHERE authority_refresh_id=:refresh AND buyer_actor_id=:buyer
                """).param("refresh", refreshId).param("buyer", buyerId)
                .query((rs, row) -> proposal(rs, List.of())).optional()
                .map(value -> withProposalLines(value, proposalLines(value)));
    }

    public ReversibilityEvaluation createRiskEvaluation(
            TransactionProposal proposal, String policyVersion, ReversibilityOutcome outcome,
            List<String> reasons, JsonNode inputs, String inputHash, Instant evaluatedAt) {
        return jdbc.sql("""
                INSERT INTO reversibility_evaluation(
                    proposal_id,buyer_actor_id,thread_id,merchant_id,proposal_hash,action_type,
                    policy_version,outcome,reason_codes,normalized_inputs,input_hash,
                    additional_confirmation_required,payment_authorization_still_required,evaluated_at)
                VALUES(:proposal,:buyer,:thread,:merchant,:proposalHash,:action,:policy,:outcome,
                    CAST(:reasons AS jsonb),CAST(:inputs AS jsonb),:inputHash,:confirmation,true,:evaluated)
                RETURNING *
                """).param("proposal", proposal.proposalId()).param("buyer", proposal.buyerActorId())
                .param("thread", proposal.threadId()).param("merchant", proposal.merchantId())
                .param("proposalHash", proposal.proposalHash()).param("action", proposal.actionType().name())
                .param("policy", policyVersion).param("outcome", outcome.name())
                .param("reasons", mapper.writeValueAsString(reasons))
                .param("inputs", mapper.writeValueAsString(inputs)).param("inputHash", inputHash)
                .param("confirmation", outcome == ReversibilityOutcome.EXPLICIT_CONFIRMATION)
                .param("evaluated", utc(evaluatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::risk).single();
    }

    public Optional<ReversibilityEvaluation> riskForProposal(UUID buyerId, UUID proposalId) {
        return jdbc.sql("SELECT * FROM reversibility_evaluation WHERE proposal_id=:proposal AND buyer_actor_id=:buyer")
                .param("proposal", proposalId).param("buyer", buyerId).query(this::risk).optional();
    }

    public AuthorizationDecision createAuthorization(
            TransactionProposal proposal, String sessionBindingHash,
            AuthorizationDecisionType decision, AuthorizationMethod method,
            Instant issuedAt, Instant expiresAt, JsonNode material, String authorizationHash) {
        return jdbc.sql("""
                INSERT INTO authorization_decision(
                    buyer_actor_id,session_binding_hash,proposal_id,proposal_hash,action_type,
                    decision,authorization_method,issued_at,expires_at,authorization_material,
                    authorization_hash)
                VALUES(:buyer,:session,:proposal,:proposalHash,:action,:decision,:method,:issued,
                    :expires,CAST(:material AS jsonb),:hash)
                RETURNING *
                """).param("buyer", proposal.buyerActorId()).param("session", sessionBindingHash)
                .param("proposal", proposal.proposalId()).param("proposalHash", proposal.proposalHash())
                .param("action", proposal.actionType().name()).param("decision", decision.name())
                .param("method", method.name()).param("issued", utc(issuedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expires", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("material", mapper.writeValueAsString(material)).param("hash", authorizationHash)
                .query(this::authorization).single();
    }

    public Optional<AuthorizationDecision> authorizationForProposal(UUID buyerId, UUID proposalId) {
        return jdbc.sql("""
                SELECT decision.*,consumption.consumed_at,consumption.execution_id
                FROM authorization_decision decision
                LEFT JOIN authorization_consumption consumption
                    ON consumption.authorization_id=decision.authorization_id
                WHERE decision.proposal_id=:proposal AND decision.buyer_actor_id=:buyer
                """).param("proposal", proposalId).param("buyer", buyerId)
                .query(this::authorization).optional();
    }

    public TransactionExecution createExecution(
            TransactionProposal proposal, AuthorizationDecision authorization,
            String idempotencyKey, Instant now) {
        return jdbc.sql("""
                INSERT INTO transaction_execution(
                    proposal_id,proposal_hash,buyer_actor_id,merchant_id,action_type,
                    authorization_id,authorization_decision,status,idempotency_key,
                    provider_order_reference,created_at,updated_at)
                VALUES(:proposal,:proposalHash,:buyer,:merchant,:action,:authorization,'AUTHORIZED',
                    'RESERVED',:idempotency,NULL,:created,:updated)
                RETURNING *
                """).param("proposal", proposal.proposalId()).param("proposalHash", proposal.proposalHash())
                .param("buyer", proposal.buyerActorId()).param("merchant", proposal.merchantId())
                .param("action", proposal.actionType().name()).param("authorization", authorization.authorizationId())
                .param("idempotency", idempotencyKey)
                .param("created", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updated", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::execution).single();
    }

    public Optional<TransactionExecution> executionForProposal(UUID buyerId, UUID proposalId) {
        return jdbc.sql("SELECT * FROM transaction_execution WHERE proposal_id=:proposal AND buyer_actor_id=:buyer")
                .param("proposal", proposalId).param("buyer", buyerId).query(this::execution).optional();
    }

    public void consumeAuthorization(UUID authorizationId, UUID executionId, Instant consumedAt) {
        jdbc.sql("""
                INSERT INTO authorization_consumption(authorization_id,execution_id,consumed_at)
                VALUES(:authorization,:execution,:consumed)
                """).param("authorization", authorizationId).param("execution", executionId)
                .param("consumed", utc(consumedAt), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public void createGateEvidence(
            UUID buyerId, String sessionBindingHash, TransactionProposal proposal,
            AuthorizationDecision authorization, TransactionExecution execution,
            GateDecision decision, String reasonCode, boolean duplicate,
            List<String> refs, String gateHash, Instant evaluatedAt) {
        jdbc.sql("""
                INSERT INTO execution_gate_evidence(
                    buyer_actor_id,session_binding_hash,proposal_id,proposal_hash,action_type,
                    authorization_id,execution_id,decision,reason_code,duplicate_resolution,
                    evidence_references,gate_hash,evaluated_at)
                VALUES(:buyer,:session,:proposal,:proposalHash,:action,:authorization,:execution,
                    :decision,:reason,:duplicate,CAST(:refs AS jsonb),:hash,:evaluated)
                """).param("buyer", buyerId).param("session", sessionBindingHash)
                .param("proposal", proposal.proposalId()).param("proposalHash", proposal.proposalHash())
                .param("action", proposal.actionType().name())
                .param("authorization", authorization == null ? null : authorization.authorizationId())
                .param("execution", execution == null ? null : execution.executionId())
                .param("decision", decision.name()).param("reason", reasonCode)
                .param("duplicate", duplicate).param("refs", mapper.writeValueAsString(refs))
                .param("hash", gateHash).param("evaluated", utc(evaluatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public void attachRisk(UUID threadId, UUID buyerId, UUID proposalId, UUID evaluationId, String state) {
        int updated = jdbc.sql("""
                UPDATE commerce_thread SET current_reversibility_evaluation_id=:evaluation,
                    current_state=:state,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer AND current_proposal_id=:proposal
                """).param("evaluation", evaluationId).param("state", state)
                .param("thread", threadId).param("buyer", buyerId).param("proposal", proposalId).update();
        if (updated != 1) throw new IllegalStateException("Current proposal changed during risk evaluation");
    }

    public void attachAuthorization(UUID threadId, UUID buyerId, UUID proposalId, UUID authorizationId,
            String state) {
        int updated = jdbc.sql("""
                UPDATE commerce_thread SET current_authorization_id=:authorization,current_state=:state,
                    lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer AND current_proposal_id=:proposal
                """).param("authorization", authorizationId).param("state", state)
                .param("thread", threadId).param("buyer", buyerId).param("proposal", proposalId).update();
        if (updated != 1) throw new IllegalStateException("Current proposal changed during authorization");
    }

    public void attachExecution(UUID threadId, UUID buyerId, UUID proposalId, UUID executionId) {
        int updated = jdbc.sql("""
                UPDATE commerce_thread SET current_execution_id=:execution,
                    lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer AND current_proposal_id=:proposal
                """).param("execution", executionId).param("thread", threadId)
                .param("buyer", buyerId).param("proposal", proposalId).update();
        if (updated != 1) throw new IllegalStateException("Current proposal changed during reservation");
    }

    private void attachAuthorityRefresh(UUID threadId, UUID buyerId, UUID refreshId,
            UUID quoteId, UUID certificateId) {
        int updated = jdbc.sql("""
                UPDATE commerce_thread SET current_authority_refresh_id=:refresh,
                    current_quote_id=:quote,current_certificate_id=:certificate,
                    lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer
                """).param("refresh", refreshId).param("quote", quoteId)
                .param("certificate", certificateId).param("thread", threadId)
                .param("buyer", buyerId).update();
        if (updated != 1) throw new IllegalStateException("Buyer thread changed during authority refresh");
    }

    private void attachProposal(UUID threadId, UUID buyerId, UUID proposalId) {
        int updated = jdbc.sql("""
                UPDATE commerce_thread SET current_proposal_id=:proposal,
                    current_reversibility_evaluation_id=NULL,current_authorization_id=NULL,
                    current_execution_id=NULL,current_state='TRANSACTION_PROPOSED',
                    lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer
                """).param("proposal", proposalId).param("thread", threadId).param("buyer", buyerId).update();
        if (updated != 1) throw new IllegalStateException("Buyer thread changed during proposal creation");
    }

    private AuthorityRefresh authorityRefresh(ResultSet rs, int row) throws SQLException {
        UUID buyerId = rs.getObject("buyer_actor_id", UUID.class);
        UUID threadId = rs.getObject("thread_id", UUID.class);
        MerchantQuote quote = buyers.findQuote(buyerId, threadId,
                rs.getObject("quote_record_id", UUID.class)).orElseThrow();
        AvailabilityRefresh availability = findAvailability(
                rs.getObject("availability_refresh_id", UUID.class)).orElseThrow();
        ServiceabilityEvidence serviceability = findServiceability(
                rs.getObject("serviceability_evidence_id", UUID.class)).orElseThrow();
        ConstraintCertificate certificate = buyers.findCertificate(buyerId, threadId,
                rs.getObject("constraint_certificate_id", UUID.class)).orElseThrow();
        return new AuthorityRefresh(rs.getObject("authority_refresh_id", UUID.class), threadId, buyerId,
                rs.getObject("merchant_id", UUID.class), rs.getObject("cart_id", UUID.class),
                rs.getInt("cart_version"), rs.getString("cart_hash").strip(), quote, availability,
                serviceability, certificate, rs.getObject("manifest_id", UUID.class),
                rs.getInt("manifest_version"), rs.getObject("policy_snapshot_id", UUID.class),
                rs.getInt("policy_snapshot_version"), rs.getString("policy_snapshot_hash").strip(),
                EvidenceOutcome.valueOf(rs.getString("outcome")), strings(rs.getString("evidence_references")),
                rs.getString("refresh_hash").strip(), instant(rs, "refreshed_at"), instant(rs, "created_at"));
    }

    private AvailabilityRefresh availability(ResultSet rs, List<AvailabilityItemEvidence> items)
            throws SQLException {
        return new AvailabilityRefresh(rs.getObject("availability_refresh_id", UUID.class),
                rs.getObject("thread_id", UUID.class), rs.getObject("buyer_actor_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getObject("cart_id", UUID.class),
                rs.getInt("cart_version"), rs.getString("cart_hash").strip(),
                rs.getObject("manifest_id", UUID.class), rs.getInt("manifest_version"),
                rs.getObject("readiness_evaluation_id", UUID.class),
                rs.getObject("executable_mapping_proposal_id", UUID.class),
                EvidenceOutcome.valueOf(rs.getString("outcome")), rs.getString("reason_code"),
                instant(rs, "observed_at"), instant(rs, "expires_at"),
                rs.getString("evidence_hash").strip(), instant(rs, "created_at"), items);
    }

    private List<AvailabilityItemEvidence> availabilityItems(AvailabilityRefresh refresh) {
        return jdbc.sql("""
                SELECT * FROM authoritative_availability_item
                WHERE availability_refresh_id=:refresh ORDER BY merchant_sku,product_id
                """).param("refresh", refresh.availabilityRefreshId())
                .query((rs, row) -> new AvailabilityItemEvidence(
                        rs.getObject("availability_item_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("catalogue_version_id", UUID.class), rs.getString("merchant_sku"),
                        rs.getString("variant"), rs.getInt("requested_quantity"),
                        (Boolean) rs.getObject("available"), (Long) rs.getObject("authoritative_quantity"),
                        EvidenceOutcome.valueOf(rs.getString("outcome")), rs.getString("reason_code"),
                        instant(rs, "merchant_observed_at"), instant(rs, "merchant_expires_at"),
                        rs.getString("response_hash").strip())).list();
    }

    private ServiceabilityEvidence serviceability(ResultSet rs, int row) throws SQLException {
        return new ServiceabilityEvidence(rs.getObject("serviceability_evidence_id", UUID.class),
                rs.getObject("thread_id", UUID.class), rs.getObject("buyer_actor_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getObject("cart_id", UUID.class),
                rs.getInt("cart_version"), rs.getString("cart_hash").strip(),
                rs.getObject("manifest_id", UUID.class), rs.getInt("manifest_version"),
                EvidenceOutcome.valueOf(rs.getString("outcome")),
                ServiceabilitySource.valueOf(rs.getString("source_type")),
                rs.getString("source_reference"), rs.getString("location_reference_hash").strip(),
                rs.getString("reason_code"), instant(rs, "observed_at"), instant(rs, "expires_at"),
                rs.getString("evidence_hash").strip(), instant(rs, "created_at"));
    }

    private TransactionProposal proposal(ResultSet rs, List<ProposalLineItem> lines) throws SQLException {
        return new TransactionProposal(rs.getObject("proposal_id", UUID.class),
                rs.getObject("buyer_actor_id", UUID.class), rs.getObject("thread_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getObject("authority_refresh_id", UUID.class),
                rs.getString("authority_refresh_hash").strip(), rs.getObject("intent_id", UUID.class),
                rs.getInt("intent_version"), rs.getString("intent_hash").strip(),
                rs.getObject("cart_id", UUID.class), rs.getInt("cart_version"),
                rs.getString("cart_hash").strip(), rs.getObject("constraint_certificate_id", UUID.class),
                rs.getString("constraint_certificate_hash").strip(),
                rs.getObject("quote_record_id", UUID.class), rs.getString("quote_hash").strip(),
                rs.getString("merchant_quote_id"), rs.getString("merchant_quote_version"),
                rs.getObject("availability_refresh_id", UUID.class), rs.getString("availability_hash").strip(),
                rs.getObject("serviceability_evidence_id", UUID.class),
                rs.getString("serviceability_hash").strip(), rs.getObject("policy_snapshot_id", UUID.class),
                rs.getInt("policy_snapshot_version"), rs.getString("policy_snapshot_hash").strip(),
                rs.getObject("catalogue_version_id", UUID.class), ActionType.valueOf(rs.getString("action_type")),
                rs.getLong("subtotal_minor"), rs.getLong("tax_minor"), rs.getLong("fees_minor"),
                rs.getLong("delivery_minor"), rs.getLong("final_amount_minor"), rs.getString("currency").strip(),
                instant(rs, "quote_expires_at"), instant(rs, "proposal_expires_at"),
                rs.getInt("canonical_schema_version"), mapper.readTree(rs.getString("canonical_material")),
                rs.getString("proposal_hash").strip(), instant(rs, "created_at"), lines);
    }

    private List<ProposalLineItem> proposalLines(TransactionProposal proposal) {
        return jdbc.sql("""
                SELECT * FROM transaction_proposal_line_item
                WHERE proposal_id=:proposal ORDER BY line_number
                """).param("proposal", proposal.proposalId()).query((rs, row) -> new ProposalLineItem(
                        rs.getObject("proposal_line_item_id", UUID.class), rs.getInt("line_number"),
                        rs.getObject("product_id", UUID.class), rs.getString("merchant_sku"),
                        rs.getString("variant"), rs.getInt("quantity"),
                        rs.getLong("unit_amount_minor"), rs.getLong("line_amount_minor"))).list();
    }

    private ReversibilityEvaluation risk(ResultSet rs, int row) throws SQLException {
        return new ReversibilityEvaluation(rs.getObject("reversibility_evaluation_id", UUID.class),
                rs.getObject("proposal_id", UUID.class), rs.getObject("buyer_actor_id", UUID.class),
                rs.getObject("thread_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getString("proposal_hash").strip(), ActionType.valueOf(rs.getString("action_type")),
                rs.getString("policy_version"), ReversibilityOutcome.valueOf(rs.getString("outcome")),
                strings(rs.getString("reason_codes")), mapper.readTree(rs.getString("normalized_inputs")),
                rs.getString("input_hash").strip(), rs.getBoolean("additional_confirmation_required"),
                rs.getBoolean("payment_authorization_still_required"), instant(rs, "evaluated_at"));
    }

    private AuthorizationDecision authorization(ResultSet rs, int row) throws SQLException {
        return new AuthorizationDecision(rs.getObject("authorization_id", UUID.class),
                rs.getObject("buyer_actor_id", UUID.class), rs.getString("session_binding_hash").strip(),
                rs.getObject("proposal_id", UUID.class), rs.getString("proposal_hash").strip(),
                ActionType.valueOf(rs.getString("action_type")),
                AuthorizationDecisionType.valueOf(rs.getString("decision")),
                AuthorizationMethod.valueOf(rs.getString("authorization_method")),
                instant(rs, "issued_at"), instant(rs, "expires_at"),
                mapper.readTree(rs.getString("authorization_material")),
                rs.getString("authorization_hash").strip(), hasColumn(rs, "consumed_at") ? instant(rs, "consumed_at") : null,
                hasColumn(rs, "execution_id") ? rs.getObject("execution_id", UUID.class) : null);
    }

    private TransactionExecution execution(ResultSet rs, int row) throws SQLException {
        return new TransactionExecution(rs.getObject("execution_id", UUID.class),
                rs.getObject("proposal_id", UUID.class), rs.getString("proposal_hash").strip(),
                rs.getObject("buyer_actor_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                ActionType.valueOf(rs.getString("action_type")), rs.getObject("authorization_id", UUID.class),
                AuthorizationDecisionType.valueOf(rs.getString("authorization_decision")),
                ExecutionStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                rs.getString("provider_order_reference"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static CapabilityBinding capability(String name, ResultSet rs, String prefix) throws SQLException {
        String readiness = rs.getString(prefix + "_readiness");
        return readiness == null ? null : new CapabilityBinding(name,
                rs.getBoolean(prefix + "_advertised"), readiness,
                rs.getObject(prefix + "_mapping_id", UUID.class),
                rs.getObject(prefix + "_evaluation_id", UUID.class));
    }

    private static boolean hasColumn(ResultSet rs, String name) {
        try { rs.findColumn(name); return true; } catch (SQLException ignored) { return false; }
    }

    private static String strip(String value) { return value == null ? null : value.strip(); }
    private List<String> strings(String json) {
        return mapper.readValue(json, new TypeReference<List<String>>() {});
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
    private static AvailabilityRefresh withAvailabilityItems(
            AvailabilityRefresh value, List<AvailabilityItemEvidence> items) {
        return new AvailabilityRefresh(value.availabilityRefreshId(), value.threadId(), value.buyerActorId(),
                value.merchantId(), value.cartId(), value.cartVersion(), value.cartHash(), value.manifestId(),
                value.manifestVersion(), value.readinessEvaluationId(), value.executableMappingProposalId(),
                value.outcome(), value.reasonCode(), value.observedAt(), value.expiresAt(), value.evidenceHash(),
                value.createdAt(), items);
    }
    private static TransactionProposal withProposalLines(
            TransactionProposal value, List<ProposalLineItem> lines) {
        return new TransactionProposal(value.proposalId(), value.buyerActorId(), value.threadId(),
                value.merchantId(), value.authorityRefreshId(), value.authorityRefreshHash(),
                value.intentId(), value.intentVersion(), value.intentHash(), value.cartId(),
                value.cartVersion(), value.cartHash(), value.constraintCertificateId(),
                value.constraintCertificateHash(), value.quoteRecordId(), value.quoteHash(),
                value.merchantQuoteId(), value.merchantQuoteVersion(), value.availabilityRefreshId(),
                value.availabilityHash(), value.serviceabilityEvidenceId(), value.serviceabilityHash(),
                value.policySnapshotId(), value.policySnapshotVersion(), value.policySnapshotHash(),
                value.catalogueVersionId(), value.actionType(), value.subtotalMinor(), value.taxMinor(),
                value.feesMinor(), value.deliveryMinor(), value.finalAmountMinor(), value.currency(),
                value.quoteExpiresAt(), value.proposalExpiresAt(), value.canonicalSchemaVersion(),
                value.canonicalMaterial(), value.proposalHash(), value.createdAt(), lines);
    }
}
