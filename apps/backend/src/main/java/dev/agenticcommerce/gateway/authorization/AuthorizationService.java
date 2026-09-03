package dev.agenticcommerce.gateway.authorization;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.BuyerState;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityException;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityRepository;
import dev.agenticcommerce.gateway.intent.BuyerStateMachine;
import dev.agenticcommerce.gateway.intent.BuyerThreadService;
import dev.agenticcommerce.gateway.risk.TransactionAuthorityPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthorizationService {
    private final TransactionAuthorityRepository repository;
    private final BuyerThreadService threads;
    private final BuyerStateMachine states;
    private final TransactionAuthorityPolicy policy;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public AuthorizationService(
            TransactionAuthorityRepository repository, BuyerThreadService threads,
            BuyerStateMachine states, TransactionAuthorityPolicy policy,
            CanonicalJsonService canonical, ObjectMapper mapper) {
        this.repository = repository;
        this.threads = threads;
        this.states = states;
        this.policy = policy;
        this.canonical = canonical;
        this.mapper = mapper;
    }

    public String bindSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new TransactionAuthorityException("AUTHENTICATED_SESSION_REQUIRED", HttpStatus.UNAUTHORIZED,
                    "A current authenticated server session is required");
        }
        return canonical.hashText("transaction-session-binding-v1|" + sessionId);
    }

    @Transactional
    public AuthorizationDecision confirm(UUID buyerId, UUID proposalId, String sessionBindingHash) {
        TransactionProposal proposal = lock(buyerId, proposalId);
        requireProposalIntegrity(proposal);
        AuthorizationDecision existing = repository.authorizationForProposal(buyerId, proposalId).orElse(null);
        if (existing != null) {
            return requireIdempotentReplay(proposal, existing, sessionBindingHash,
                    AuthorizationDecisionType.AUTHORIZED, AuthorizationMethod.EXPLICIT_CONFIRMATION);
        }
        var thread = threads.requireForUpdate(buyerId, proposal.threadId());
        ReversibilityEvaluation risk = repository.riskForProposal(buyerId, proposalId)
                .orElseThrow(() -> conflict("RISK_EVALUATION_REQUIRED",
                        "Deterministic reversibility evaluation is required"));
        if (risk.outcome() != ReversibilityOutcome.EXPLICIT_CONFIRMATION) {
            throw conflict("EXPLICIT_CONFIRMATION_NOT_PERMITTED",
                    "This proposal is not awaiting explicit confirmation");
        }
        if (thread.state() != BuyerState.WAITING_FOR_USER) {
            throw conflict("CONFIRMATION_STATE_INVALID", "Proposal is not waiting for buyer confirmation");
        }
        return create(proposal, sessionBindingHash, AuthorizationDecisionType.AUTHORIZED,
                AuthorizationMethod.EXPLICIT_CONFIRMATION, BuyerState.READY_TO_EXECUTE);
    }

    @Transactional
    public AuthorizationDecision deny(UUID buyerId, UUID proposalId, String sessionBindingHash) {
        TransactionProposal proposal = lock(buyerId, proposalId);
        requireProposalIntegrity(proposal);
        AuthorizationDecision existing = repository.authorizationForProposal(buyerId, proposalId).orElse(null);
        if (existing != null) {
            return requireIdempotentReplay(proposal, existing, sessionBindingHash,
                    AuthorizationDecisionType.DENIED, AuthorizationMethod.BUYER_DENIAL);
        }
        threads.requireForUpdate(buyerId, proposal.threadId());
        return create(proposal, sessionBindingHash, AuthorizationDecisionType.DENIED,
                AuthorizationMethod.BUYER_DENIAL, BuyerState.WAITING_FOR_USER);
    }

    @Transactional
    public AuthorizationDecision authorizeAuto(
            TransactionProposal proposal, String sessionBindingHash) {
        ReversibilityEvaluation risk = repository.riskForProposal(
                proposal.buyerActorId(), proposal.proposalId()).orElseThrow();
        if (risk.outcome() != ReversibilityOutcome.AUTO_EXECUTE) {
            throw conflict("AUTO_EXECUTE_NOT_PERMITTED", "Risk policy did not permit AUTO_EXECUTE");
        }
        return create(proposal, sessionBindingHash, AuthorizationDecisionType.AUTHORIZED,
                AuthorizationMethod.AUTO_EXECUTE_POLICY, BuyerState.READY_TO_EXECUTE);
    }

    public AuthorizationDecision current(UUID buyerId, UUID proposalId) {
        repository.findProposal(buyerId, proposalId)
                .orElseThrow(() -> notFound("TRANSACTION_PROPOSAL_NOT_FOUND", "Proposal was not found"));
        return repository.authorizationForProposal(buyerId, proposalId)
                .orElseThrow(() -> notFound("AUTHORIZATION_NOT_FOUND", "Authorization decision was not found"));
    }

    private AuthorizationDecision create(
            TransactionProposal proposal, String sessionBindingHash,
            AuthorizationDecisionType decision, AuthorizationMethod method, BuyerState targetState) {
        AuthorizationDecision existing = repository.authorizationForProposal(
                proposal.buyerActorId(), proposal.proposalId()).orElse(null);
        if (existing != null) return existing;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!proposal.proposalExpiresAt().isAfter(now)) {
            throw conflict("PROPOSAL_EXPIRED", "Expired proposal cannot be authorized");
        }
        Instant expires = min(proposal.proposalExpiresAt(), now.plus(policy.authorizationTtl()))
                .truncatedTo(ChronoUnit.MICROS);
        var material = mapper.createObjectNode();
        material.put("schemaVersion", 1);
        material.put("buyerActorId", proposal.buyerActorId().toString());
        material.put("sessionBindingHash", sessionBindingHash);
        material.put("proposalId", proposal.proposalId().toString());
        material.put("proposalHash", proposal.proposalHash());
        material.put("actionType", proposal.actionType().name());
        material.put("decision", decision.name());
        material.put("authorizationMethod", method.name());
        material.put("issuedAt", now.toString());
        material.put("expiresAt", expires.toString());
        AuthorizationDecision created = repository.createAuthorization(proposal, sessionBindingHash,
                decision, method, now, expires, material, canonical.hash(material));
        BuyerState from = threads.require(proposal.buyerActorId(), proposal.threadId()).state();
        if (targetState != from) states.require(from, targetState);
        repository.attachAuthorization(proposal.threadId(), proposal.buyerActorId(),
                proposal.proposalId(), created.authorizationId(), targetState.name());
        return created;
    }

    private AuthorizationDecision requireIdempotentReplay(
            TransactionProposal proposal, AuthorizationDecision existing, String sessionBindingHash,
            AuthorizationDecisionType decision, AuthorizationMethod method) {
        Instant now = Instant.now();
        if (!proposal.proposalExpiresAt().isAfter(now) || !existing.expiresAt().isAfter(now)) {
            throw conflict("AUTHORIZATION_EXPIRED", "Expired authorization cannot be confirmed again");
        }
        if (!existing.buyerActorId().equals(proposal.buyerActorId())
                || !existing.proposalId().equals(proposal.proposalId())
                || !existing.proposalHash().equals(proposal.proposalHash())
                || existing.actionType() != proposal.actionType()
                || !existing.sessionBindingHash().equals(sessionBindingHash)
                || existing.decision() != decision
                || existing.authorizationMethod() != method) {
            throw conflict("AUTHORIZATION_REPLAY_MISMATCH",
                    "Existing authorization does not match this explicit decision and session");
        }
        return existing;
    }

    private void requireProposalIntegrity(TransactionProposal proposal) {
        if (!canonical.hash(proposal.canonicalMaterial()).equals(proposal.proposalHash())) {
            throw conflict("PROPOSAL_HASH_MISMATCH",
                    "Transaction proposal material no longer matches its immutable hash");
        }
    }

    private TransactionProposal lock(UUID buyerId, UUID proposalId) {
        return repository.findProposalForUpdate(buyerId, proposalId)
                .orElseThrow(() -> notFound("TRANSACTION_PROPOSAL_NOT_FOUND", "Proposal was not found"));
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
    private static TransactionAuthorityException conflict(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.CONFLICT, message);
    }
    private static TransactionAuthorityException notFound(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.NOT_FOUND, message);
    }
}
