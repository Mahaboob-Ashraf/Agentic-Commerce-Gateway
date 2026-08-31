package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;

import dev.agenticcommerce.gateway.authorization.AuthorizationService;
import dev.agenticcommerce.gateway.authorization.ExecutionGate;
import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import dev.agenticcommerce.gateway.risk.ReversibilityService;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/buyer/threads/{threadId}/transaction")
public class TransactionAuthorityApiController {
    private final AuthoritativeRefreshService refreshes;
    private final TransactionProposalService proposals;
    private final ReversibilityService reversibility;
    private final AuthorizationService authorizations;
    private final ExecutionGate executionGate;

    public TransactionAuthorityApiController(
            AuthoritativeRefreshService refreshes, TransactionProposalService proposals,
            ReversibilityService reversibility, AuthorizationService authorizations,
            ExecutionGate executionGate) {
        this.refreshes = refreshes;
        this.proposals = proposals;
        this.reversibility = reversibility;
        this.authorizations = authorizations;
        this.executionGate = executionGate;
    }

    @PostMapping("/refresh")
    public AuthorityRefresh refresh(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId) {
        return refreshes.refresh(principal.actorId(), threadId);
    }

    @GetMapping("/refresh")
    public AuthorityRefresh currentRefresh(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId) {
        return refreshes.current(principal.actorId(), threadId);
    }

    @PostMapping("/proposals")
    public TransactionProposal createProposal(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId) {
        return proposals.create(principal.actorId(), threadId);
    }

    @GetMapping("/proposal")
    public TransactionProposal currentProposal(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId) {
        return proposals.current(principal.actorId(), threadId);
    }

    @PostMapping("/proposals/{proposalId}/risk-evaluation")
    public ReversibilityEvaluation evaluateRisk(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId, HttpSession session) {
        requireThread(principal.actorId(), threadId, proposalId);
        return reversibility.evaluate(principal.actorId(), proposalId,
                authorizations.bindSession(session.getId()));
    }

    @GetMapping("/proposals/{proposalId}/risk-evaluation")
    public ReversibilityEvaluation risk(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        requireThread(principal.actorId(), threadId, proposalId);
        return reversibility.require(principal.actorId(), proposalId);
    }

    @PostMapping("/proposals/{proposalId}/confirm")
    public AuthorizationDecision confirm(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId, HttpSession session) {
        requireThread(principal.actorId(), threadId, proposalId);
        return authorizations.confirm(principal.actorId(), proposalId,
                authorizations.bindSession(session.getId()));
    }

    @PostMapping("/proposals/{proposalId}/deny")
    public AuthorizationDecision deny(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId, HttpSession session) {
        requireThread(principal.actorId(), threadId, proposalId);
        return authorizations.deny(principal.actorId(), proposalId,
                authorizations.bindSession(session.getId()));
    }

    @GetMapping("/proposals/{proposalId}/authorization")
    public AuthorizationDecision authorization(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        requireThread(principal.actorId(), threadId, proposalId);
        return authorizations.current(principal.actorId(), proposalId);
    }

    @PostMapping("/proposals/{proposalId}/executions")
    public ExecutionGateResult execute(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId, HttpSession session) {
        requireThread(principal.actorId(), threadId, proposalId);
        return executionGate.reserve(principal.actorId(), proposalId,
                authorizations.bindSession(session.getId()));
    }

    @GetMapping("/proposals/{proposalId}/execution")
    public TransactionExecution execution(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        requireThread(principal.actorId(), threadId, proposalId);
        return executionGate.requireExecution(principal.actorId(), proposalId);
    }

    private void requireThread(UUID buyerId, UUID threadId, UUID proposalId) {
        TransactionProposal proposal = proposals.require(buyerId, proposalId);
        if (!proposal.threadId().equals(threadId)) {
            throw new TransactionAuthorityException("TRANSACTION_PROPOSAL_NOT_FOUND",
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Transaction proposal was not found in this buyer thread");
        }
    }
}
