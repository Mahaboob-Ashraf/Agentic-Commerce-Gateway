package dev.agenticcommerce.gateway.risk;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.authorization.AuthorizationService;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityException;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityRepository;
import dev.agenticcommerce.gateway.commerce.TransactionProposalCanonicalizer;
import dev.agenticcommerce.gateway.intent.BuyerRepository;
import dev.agenticcommerce.gateway.intent.BuyerStateMachine;
import dev.agenticcommerce.gateway.intent.BuyerThreadService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReversibilityService {
    private final TransactionAuthorityRepository repository;
    private final BuyerRepository buyers;
    private final BuyerThreadService threads;
    private final BuyerStateMachine states;
    private final ReversibilityEngine engine;
    private final TransactionAuthorityPolicy policy;
    private final TransactionProposalCanonicalizer canonicalizer;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    private final AuthorizationService authorization;

    public ReversibilityService(
            TransactionAuthorityRepository repository, BuyerRepository buyers,
            BuyerThreadService threads, BuyerStateMachine states, ReversibilityEngine engine,
            TransactionAuthorityPolicy policy, TransactionProposalCanonicalizer canonicalizer,
            CanonicalJsonService canonical, ObjectMapper mapper, AuthorizationService authorization) {
        this.repository = repository;
        this.buyers = buyers;
        this.threads = threads;
        this.states = states;
        this.engine = engine;
        this.policy = policy;
        this.canonicalizer = canonicalizer;
        this.canonical = canonical;
        this.mapper = mapper;
        this.authorization = authorization;
    }

    @Transactional
    public ReversibilityEvaluation evaluate(
            UUID buyerId, UUID proposalId, String sessionBindingHash) {
        TransactionProposal proposal = repository.findProposalForUpdate(buyerId, proposalId)
                .orElseThrow(() -> notFound("TRANSACTION_PROPOSAL_NOT_FOUND", "Proposal was not found"));
        ReversibilityEvaluation existing = repository.riskForProposal(buyerId, proposalId).orElse(null);
        if (existing != null) return existing;
        CommerceThread thread = threads.requireForUpdate(buyerId, proposal.threadId());
        if (!proposal.proposalId().equals(repository.currentProposal(buyerId, proposal.threadId())
                .map(TransactionProposal::proposalId).orElse(null))) {
            throw conflict("PROPOSAL_NOT_CURRENT", "Only the current exact proposal may be evaluated");
        }
        if (thread.state() != BuyerState.TRANSACTION_PROPOSED) {
            throw conflict("RISK_STATE_INVALID", "Risk evaluation requires TRANSACTION_PROPOSED");
        }
        RiskInput input = input(proposal);
        ReversibilityEngine.Decision decision = engine.evaluate(input);
        var normalized = mapper.valueToTree(input);
        String inputHash = canonical.hash(normalized);
        Instant now = Instant.now();
        ReversibilityEvaluation evaluation = repository.createRiskEvaluation(proposal,
                policy.version(), decision.outcome(), decision.reasonCodes(), normalized, inputHash, now);
        states.require(BuyerState.TRANSACTION_PROPOSED, BuyerState.RISK_EVALUATED);
        BuyerState finalState = switch (decision.outcome()) {
            case AUTO_EXECUTE -> BuyerState.RISK_EVALUATED;
            case EXPLICIT_CONFIRMATION, CLARIFY, BLOCK -> BuyerState.WAITING_FOR_USER;
        };
        if (finalState != BuyerState.RISK_EVALUATED) {
            states.require(BuyerState.RISK_EVALUATED, finalState);
        }
        repository.attachRisk(proposal.threadId(), buyerId, proposalId,
                evaluation.reversibilityEvaluationId(), finalState.name());
        if (decision.outcome() == ReversibilityOutcome.AUTO_EXECUTE) {
            authorization.authorizeAuto(proposal, sessionBindingHash);
        }
        return evaluation;
    }

    public ReversibilityEvaluation require(UUID buyerId, UUID proposalId) {
        repository.findProposal(buyerId, proposalId)
                .orElseThrow(() -> notFound("TRANSACTION_PROPOSAL_NOT_FOUND", "Proposal was not found"));
        return repository.riskForProposal(buyerId, proposalId)
                .orElseThrow(() -> notFound("RISK_EVALUATION_NOT_FOUND", "Risk evaluation was not found"));
    }

    private RiskInput input(TransactionProposal proposal) {
        ConstraintCertificate certificate = buyers.findCertificate(proposal.buyerActorId(),
                proposal.threadId(), proposal.constraintCertificateId()).orElseThrow();
        AvailabilityRefresh stock = repository.findAvailability(proposal.availabilityRefreshId()).orElseThrow();
        ServiceabilityEvidence serviceability = repository.findServiceability(
                proposal.serviceabilityEvidenceId()).orElseThrow();
        BuyerIntent intent = buyers.latestIntent(proposal.buyerActorId(), proposal.threadId()).orElseThrow();
        MerchantAuthorityContext authority = repository.currentMerchantAuthority(proposal.merchantId())
                .orElse(null);
        var recomputed = canonicalizer.canonicalize(proposal);
        boolean valid = recomputed.hash().equals(proposal.proposalHash())
                && canonical.hash(proposal.canonicalMaterial()).equals(proposal.proposalHash())
                && repository.latestAuthorityRefresh(proposal.buyerActorId(), proposal.threadId())
                        .map(refresh -> refresh.authorityRefreshId().equals(proposal.authorityRefreshId())
                                && refresh.constraintCertificate().certificateId().equals(
                                        proposal.constraintCertificateId())
                                && refresh.quote().quoteRecordId().equals(proposal.quoteRecordId()))
                        .orElse(false);
        boolean safetyUnknown = certificate.results().stream().anyMatch(result ->
                result.safetyCritical() && result.result() == ConstraintOutcome.UNKNOWN);
        ConstraintOutcome identity = certificate.results().stream()
                .filter(result -> result.constraintKey().equals("EXACT_IDENTITY"))
                .map(ConstraintResult::result).findFirst().orElse(ConstraintOutcome.UNKNOWN);
        boolean capabilitiesReady = authority != null && authority.quoteCapability() != null
                && authority.quoteCapability().ready() && authority.availabilityCapability() != null
                && authority.availabilityCapability().ready()
                && authority.quoteCapability().executableMappingProposalId().equals(
                        buyers.findQuote(proposal.buyerActorId(), proposal.threadId(), proposal.quoteRecordId())
                                .orElseThrow().executableMappingProposalId())
                && authority.availabilityCapability().executableMappingProposalId().equals(
                        stock.executableMappingProposalId());
        boolean policyCurrent = authority != null
                && proposal.policySnapshotId().equals(authority.policySnapshotId())
                && proposal.policySnapshotVersion() == authority.policySnapshotVersion()
                && proposal.policySnapshotHash().equals(authority.policySnapshotHash());
        boolean exactRequested = intent.compiled().exactMerchantSku() != null
                || intent.compiled().exactGtin() != null || intent.compiled().exactVariant() != null;
        boolean exactSatisfied = !exactRequested || proposal.lineItems().stream().allMatch(line ->
                intent.compiled().exactMerchantSku() == null
                        || intent.compiled().exactMerchantSku().equals(line.merchantSku()));
        return new RiskInput(proposal.actionType(), proposal.finalAmountMinor(), valid,
                !proposal.proposalExpiresAt().isAfter(Instant.now()), outcome(certificate.overallResult()),
                safetyUnknown, outcome(identity), stock.outcome(), serviceability.outcome(),
                capabilitiesReady, policyCurrent,
                intent.compiled().ambiguityState() == AmbiguityState.AMBIGUOUS,
                exactRequested && !exactSatisfied
                        && intent.compiled().substitutionPolicy() == SubstitutionPolicy.UNKNOWN,
                true);
    }

    private static EvidenceOutcome outcome(ConstraintOutcome value) {
        return value == ConstraintOutcome.PASS ? EvidenceOutcome.PASS
                : value == ConstraintOutcome.FAIL ? EvidenceOutcome.FAIL : EvidenceOutcome.UNKNOWN;
    }
    private static TransactionAuthorityException conflict(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.CONFLICT, message);
    }
    private static TransactionAuthorityException notFound(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.NOT_FOUND, message);
    }
}
