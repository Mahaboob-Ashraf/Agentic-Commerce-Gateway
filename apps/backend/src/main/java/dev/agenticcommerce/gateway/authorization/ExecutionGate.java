package dev.agenticcommerce.gateway.authorization;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.CatalogueVersion;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityException;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityRepository;
import dev.agenticcommerce.gateway.commerce.TransactionProposalCanonicalizer;
import dev.agenticcommerce.gateway.intent.BuyerRepository;
import dev.agenticcommerce.gateway.intent.BuyerThreadService;
import dev.agenticcommerce.gateway.onboarding.OnboardingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The only normal PURCHASE execution-reservation path. This component has no AI dependency. */
@Service
public class ExecutionGate {
    private final TransactionAuthorityRepository repository;
    private final BuyerRepository buyers;
    private final CatalogueRepository catalogues;
    private final BuyerThreadService threads;
    private final TransactionProposalCanonicalizer canonicalizer;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    private final OnboardingService onboarding;

    public ExecutionGate(
            TransactionAuthorityRepository repository, BuyerRepository buyers,
            CatalogueRepository catalogues,
            BuyerThreadService threads, TransactionProposalCanonicalizer canonicalizer,
            CanonicalJsonService canonical, ObjectMapper mapper,OnboardingService onboarding) {
        this.repository = repository;
        this.buyers = buyers;
        this.catalogues = catalogues;
        this.threads = threads;
        this.canonicalizer = canonicalizer;
        this.canonical = canonical;
        this.mapper = mapper;
        this.onboarding=onboarding;
    }

    @Transactional
    public ExecutionGateResult reserve(
            UUID buyerId, UUID proposalId, String sessionBindingHash) {
        TransactionProposal proposal = repository.findProposalForUpdate(buyerId, proposalId)
                .orElseThrow(() -> new TransactionAuthorityException("TRANSACTION_PROPOSAL_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Transaction proposal was not found"));
        CommerceThread thread = threads.requireForUpdate(buyerId, proposal.threadId());
        List<String> refs = new ArrayList<>();
        refs.add("proposal:" + proposal.proposalId() + ":" + proposal.proposalHash());
        if (proposal.actionType() != ActionType.PURCHASE) return deny(
                buyerId, sessionBindingHash, proposal, null, "ACTION_TYPE_MISMATCH", refs);
        if (!proposal.proposalId().equals(repository.currentProposal(buyerId, proposal.threadId())
                .map(TransactionProposal::proposalId).orElse(null))) return deny(
                buyerId, sessionBindingHash, proposal, null, "PROPOSAL_NOT_CURRENT", refs);
        if (!proposal.authorityRefreshId().equals(thread.currentAuthorityRefreshId())
                || !proposal.constraintCertificateId().equals(thread.currentCertificateId())
                || !proposal.quoteRecordId().equals(thread.currentQuoteId())) return deny(
                buyerId, sessionBindingHash, proposal, null,
                "AUTHORITATIVE_EVIDENCE_NOT_CURRENT", refs);
        if (!canonical.hash(proposal.canonicalMaterial()).equals(proposal.proposalHash())
                || !onboarding.validProposalBinding(proposal)) return deny(
                buyerId, sessionBindingHash, proposal, null, "PROPOSAL_HASH_MISMATCH", refs);
        AuthorizationDecision authorization = repository.authorizationForProposal(buyerId, proposalId)
                .orElse(null);
        if (authorization == null) return deny(
                buyerId, sessionBindingHash, proposal, null, "AUTHORIZATION_MISSING", refs);
        refs.add("authorization:" + authorization.authorizationId() + ":" + authorization.authorizationHash());
        if (!authorization.buyerActorId().equals(buyerId)
                || !authorization.proposalId().equals(proposal.proposalId())
                || !authorization.proposalHash().equals(proposal.proposalHash())
                || authorization.actionType() != proposal.actionType()) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "AUTHORIZATION_BINDING_MISMATCH", refs);
        if (!authorization.sessionBindingHash().equals(sessionBindingHash)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "AUTHORIZATION_SESSION_MISMATCH", refs);
        if (authorization.decision() != AuthorizationDecisionType.AUTHORIZED) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "AUTHORIZATION_DENIED", refs);
        TransactionExecution existing = repository.executionForProposal(buyerId, proposalId).orElse(null);
        if (existing != null) {
            refs.add("execution:" + existing.executionId() + ":" + existing.idempotencyKey());
            audit(buyerId, sessionBindingHash, proposal, authorization, existing,
                    GateDecision.ALLOW, "EXECUTION_ALREADY_RESERVED", true, refs);
            return new ExecutionGateResult(GateDecision.ALLOW, "EXECUTION_ALREADY_RESERVED",
                    existing, true, List.copyOf(refs));
        }
        Instant now = Instant.now();
        if (!proposal.proposalExpiresAt().isAfter(now)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "PROPOSAL_EXPIRED", refs);
        if (!authorization.expiresAt().isAfter(now)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "AUTHORIZATION_EXPIRED", refs);
        if (!authorizationHashMatches(authorization)) return deny(
                buyerId, sessionBindingHash, proposal, authorization,
                "AUTHORIZATION_HASH_MISMATCH", refs);
        if (thread.state() != BuyerState.READY_TO_EXECUTE
                || !authorization.authorizationId().equals(thread.currentAuthorizationId())) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "BUYER_AUTHORITY_STATE_INVALID", refs);
        ConstraintCertificate certificate = buyers.findCertificate(buyerId, proposal.threadId(),
                proposal.constraintCertificateId()).orElse(null);
        if (certificate == null || !certificate.executable()
                || certificate.overallResult() != ConstraintOutcome.PASS
                || !certificate.certificateHash().equals(proposal.constraintCertificateHash())) return deny(
                buyerId, sessionBindingHash, proposal, authorization,
                "CONSTRAINT_CERTIFICATE_NOT_PASS", refs);
        refs.add("certificate:" + certificate.certificateId() + ":" + certificate.certificateHash());
        MerchantQuote quote = buyers.findQuote(buyerId, proposal.threadId(), proposal.quoteRecordId()).orElse(null);
        if (quote == null || !quote.evidenceHash().equals(proposal.quoteHash())
                || !quote.cartHash().equals(proposal.cartHash())
                || !quote.merchantId().equals(proposal.merchantId())
                || !quote.expiresAt().isAfter(now) || !"INR".equals(quote.currency())
                || quote.finalAmountMinor() == null
                || quote.finalAmountMinor() != proposal.finalAmountMinor()) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "QUOTE_EXPIRED_OR_MISMATCH", refs);
        AvailabilityRefresh availability = repository.findAvailability(
                proposal.availabilityRefreshId()).orElse(null);
        if (availability == null || !availability.evidenceHash().equals(proposal.availabilityHash())
                || !availability.cartHash().equals(proposal.cartHash())) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "AVAILABILITY_BINDING_MISMATCH", refs);
        if (availability.outcome() == EvidenceOutcome.FAIL) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "STOCK_UNAVAILABLE", refs);
        if (availability.outcome() != EvidenceOutcome.PASS || availability.expiresAt() == null
                || !availability.expiresAt().isAfter(now)
                || availability.items().stream().anyMatch(item -> item.outcome() != EvidenceOutcome.PASS)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "STOCK_UNKNOWN", refs);
        ServiceabilityEvidence serviceability = repository.findServiceability(
                proposal.serviceabilityEvidenceId()).orElse(null);
        if (serviceability == null || !serviceability.evidenceHash().equals(proposal.serviceabilityHash())
                || !serviceability.cartHash().equals(proposal.cartHash())) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "SERVICEABILITY_BINDING_MISMATCH", refs);
        if (serviceability.outcome() == EvidenceOutcome.FAIL) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "SERVICEABILITY_FAIL", refs);
        if (serviceability.outcome() != EvidenceOutcome.PASS || serviceability.expiresAt() == null
                || !serviceability.expiresAt().isAfter(now)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "SERVICEABILITY_UNKNOWN", refs);
        MerchantAuthorityContext authority = repository.currentMerchantAuthority(proposal.merchantId())
                .orElse(null);
        if (!capabilitiesMatch(authority, quote, availability)) return deny(
                buyerId, sessionBindingHash, proposal, authorization,
                "MERCHANT_CAPABILITY_NOT_READY", refs);
        if (!policyMatches(authority, proposal)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "POLICY_EVIDENCE_MISMATCH", refs);
        if (!catalogueMatches(authority, proposal)) return deny(
                buyerId, sessionBindingHash, proposal, authorization, "CATALOGUE_BINDING_NOT_CURRENT", refs);
        refs.add("manifest:" + authority.manifestId() + ":v" + authority.manifestVersion());
        String idempotency = canonical.hashText("purchase-execution-v1|" + proposal.proposalId()
                + "|" + proposal.proposalHash());
        TransactionExecution execution = repository.createExecution(proposal, authorization, idempotency, now);
        repository.consumeAuthorization(authorization.authorizationId(), execution.executionId(), now);
        repository.attachExecution(proposal.threadId(), buyerId, proposalId, execution.executionId());
        refs.add("execution:" + execution.executionId() + ":" + execution.idempotencyKey());
        audit(buyerId, sessionBindingHash, proposal, authorization, execution,
                GateDecision.ALLOW, "ALL_EXECUTION_PREREQUISITES_PASS", false, refs);
        return new ExecutionGateResult(GateDecision.ALLOW, "ALL_EXECUTION_PREREQUISITES_PASS",
                execution, false, List.copyOf(refs));
    }

    public TransactionExecution requireExecution(UUID buyerId, UUID proposalId) {
        repository.findProposal(buyerId, proposalId)
                .orElseThrow(() -> new TransactionAuthorityException("TRANSACTION_PROPOSAL_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Transaction proposal was not found"));
        return repository.executionForProposal(buyerId, proposalId)
                .orElseThrow(() -> new TransactionAuthorityException("EXECUTION_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Execution reservation was not found"));
    }

    private ExecutionGateResult deny(
            UUID buyerId, String sessionBindingHash, TransactionProposal proposal,
            AuthorizationDecision authorization, String reason, List<String> refs) {
        audit(buyerId, sessionBindingHash, proposal, authorization, null,
                GateDecision.DENY, reason, false, refs);
        return new ExecutionGateResult(GateDecision.DENY, reason, null, false, List.copyOf(refs));
    }

    private void audit(
            UUID buyerId, String sessionBindingHash, TransactionProposal proposal,
            AuthorizationDecision authorization, TransactionExecution execution,
            GateDecision decision, String reason, boolean duplicate, List<String> refs) {
        Instant now = Instant.now();
        var material = mapper.createObjectNode();
        material.put("buyerActorId", buyerId.toString());
        material.put("sessionBindingHash", sessionBindingHash);
        material.put("proposalId", proposal.proposalId().toString());
        material.put("proposalHash", proposal.proposalHash());
        material.put("authorizationId", authorization == null ? null : authorization.authorizationId().toString());
        material.put("executionId", execution == null ? null : execution.executionId().toString());
        material.put("decision", decision.name());
        material.put("reasonCode", reason);
        material.put("duplicateResolution", duplicate);
        material.put("evaluatedAt", now.toString());
        repository.createGateEvidence(buyerId, sessionBindingHash, proposal, authorization, execution,
                decision, reason, duplicate, List.copyOf(refs), canonical.hash(material), now);
    }

    private static boolean capabilitiesMatch(
            MerchantAuthorityContext authority, MerchantQuote quote, AvailabilityRefresh availability) {
        return authority != null && authority.quoteCapability() != null
                && authority.quoteCapability().ready() && authority.availabilityCapability() != null
                && authority.availabilityCapability().ready()
                && authority.quoteCapability().executableMappingProposalId().equals(
                        quote.executableMappingProposalId())
                && authority.availabilityCapability().executableMappingProposalId().equals(
                        availability.executableMappingProposalId());
    }

    private static boolean policyMatches(
            MerchantAuthorityContext authority, TransactionProposal proposal) {
        return authority != null && proposal.policySnapshotId().equals(authority.policySnapshotId())
                && authority.policySnapshotVersion() != null
                && proposal.policySnapshotVersion() == authority.policySnapshotVersion()
                && proposal.policySnapshotHash().equals(authority.policySnapshotHash());
    }

    private boolean catalogueMatches(
            MerchantAuthorityContext authority, TransactionProposal proposal) {
        CatalogueVersion current = catalogues.latestPublished(proposal.merchantId()).orElse(null);
        return authority != null && current != null
                && current.id().equals(proposal.catalogueVersionId())
                && ("v" + current.version() + ":" + current.contentHash())
                        .equals(authority.catalogueVersion());
    }

    private boolean authorizationHashMatches(AuthorizationDecision value) {
        var material = mapper.createObjectNode();
        material.put("schemaVersion", 1);
        material.put("buyerActorId", value.buyerActorId().toString());
        material.put("sessionBindingHash", value.sessionBindingHash());
        material.put("proposalId", value.proposalId().toString());
        material.put("proposalHash", value.proposalHash());
        material.put("actionType", value.actionType().name());
        material.put("decision", value.decision().name());
        material.put("authorizationMethod", value.authorizationMethod().name());
        material.put("issuedAt", value.issuedAt().toString());
        material.put("expiresAt", value.expiresAt().toString());
        return canonical.hash(value.authorizationMaterial()).equals(value.authorizationHash())
                && canonical.hash(material).equals(value.authorizationHash());
    }
}
