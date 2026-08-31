package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.intent.BuyerRepository;
import dev.agenticcommerce.gateway.intent.BuyerStateMachine;
import dev.agenticcommerce.gateway.intent.BuyerThreadService;
import dev.agenticcommerce.gateway.risk.TransactionAuthorityPolicy;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionProposalService {
    private final BuyerThreadService threads;
    private final BuyerRepository buyers;
    private final BuyerStateMachine states;
    private final TransactionAuthorityRepository repository;
    private final TransactionProposalCanonicalizer canonicalizer;
    private final TransactionAuthorityPolicy policy;

    public TransactionProposalService(
            BuyerThreadService threads, BuyerRepository buyers, BuyerStateMachine states,
            TransactionAuthorityRepository repository,
            TransactionProposalCanonicalizer canonicalizer, TransactionAuthorityPolicy policy) {
        this.threads = threads;
        this.buyers = buyers;
        this.states = states;
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.policy = policy;
    }

    @Transactional
    public TransactionProposal create(UUID buyerId, UUID threadId) {
        CommerceThread thread = threads.requireForUpdate(buyerId, threadId);
        if (!allowed(thread.state())) {
            throw conflict("PROPOSAL_STATE_INVALID", "Current buyer state cannot create a proposal");
        }
        TransactionProposal current = repository.currentProposal(buyerId, threadId).orElse(null);
        if (current != null && repository.executionForProposal(buyerId, current.proposalId()).isPresent()) {
            throw conflict("EXECUTION_ALREADY_RESERVED", "A reserved execution cannot be replaced");
        }
        AuthorityRefresh refresh = repository.latestAuthorityRefresh(buyerId, threadId)
                .orElseThrow(() -> conflict("AUTHORITATIVE_REFRESH_REQUIRED",
                        "Authoritative refresh is required before proposal creation"));
        TransactionProposal existing = repository.proposalForRefresh(buyerId,
                refresh.authorityRefreshId()).orElse(null);
        if (existing != null) return existing;
        validateRefresh(refresh, thread);
        BuyerIntent intent = buyers.latestIntent(buyerId, threadId).orElseThrow();
        CandidateCart cart = buyers.findCart(buyerId, threadId, refresh.cartId()).orElseThrow();
        MerchantQuote quote = refresh.quote();
        List<ProposalLineItem> lines = cart.items().stream()
                .sorted(Comparator.comparing(CandidateCartItem::merchantSku)
                        .thenComparing(CandidateCartItem::productId))
                .map(item -> line(item, quote)).toList();
        Instant now = Instant.now();
        Instant proposalExpiry = min(quote.expiresAt(), now.plus(policy.proposalTtl()));
        if (!proposalExpiry.isAfter(now)) {
            throw conflict("QUOTE_EXPIRED", "Quote expired before proposal creation");
        }
        ProposalDraft draft = new ProposalDraft(
                buyerId, threadId, cart.merchantId(), refresh.authorityRefreshId(), refresh.refreshHash(),
                intent.intentId(), intent.intentVersion(), intent.intentHash(), cart.cartId(),
                cart.cartVersion(), cart.cartHash(), refresh.constraintCertificate().certificateId(),
                refresh.constraintCertificate().certificateHash(), quote.quoteRecordId(),
                quote.evidenceHash(), quote.merchantQuoteId(), quote.merchantQuoteVersion(),
                refresh.availability().availabilityRefreshId(), refresh.availability().evidenceHash(),
                refresh.serviceability().serviceabilityEvidenceId(), refresh.serviceability().evidenceHash(),
                refresh.policySnapshotId(), refresh.policySnapshotVersion(), refresh.policySnapshotHash(),
                cart.catalogueVersionId(), ActionType.PURCHASE, quote.subtotalMinor(), quote.taxMinor(),
                quote.feesMinor(), quote.deliveryMinor(), quote.finalAmountMinor(), quote.currency(),
                quote.expiresAt(), proposalExpiry, lines);
        var canonical = canonicalizer.canonicalize(draft);
        states.require(thread.state(), BuyerState.TRANSACTION_PROPOSED);
        return repository.createProposal(draft, canonical.material(), canonical.hash());
    }

    public TransactionProposal current(UUID buyerId, UUID threadId) {
        threads.require(buyerId, threadId);
        return repository.currentProposal(buyerId, threadId)
                .orElseThrow(() -> new TransactionAuthorityException("TRANSACTION_PROPOSAL_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Current transaction proposal was not found"));
    }

    public TransactionProposal require(UUID buyerId, UUID proposalId) {
        return repository.findProposal(buyerId, proposalId)
                .orElseThrow(() -> new TransactionAuthorityException("TRANSACTION_PROPOSAL_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Transaction proposal was not found"));
    }

    private static ProposalLineItem line(CandidateCartItem item, MerchantQuote quote) {
        MerchantQuoteItem priced = quote.items().stream()
                .filter(value -> value.productId().equals(item.productId())
                        && value.merchantSku().equals(item.merchantSku())
                        && value.quantity() == item.quantity())
                .findFirst().orElseThrow(() -> conflict("QUOTE_LINE_MISMATCH",
                        "Quote does not price the exact cart line"));
        if (priced.unitAmountMinor() == null || priced.lineAmountMinor() == null) {
            throw conflict("QUOTE_LINE_MONEY_UNKNOWN", "Exact quote line money is required");
        }
        return new ProposalLineItem(null, 0, item.productId(), item.merchantSku(), item.variant(),
                item.quantity(), priced.unitAmountMinor(), priced.lineAmountMinor());
    }

    private static void validateRefresh(AuthorityRefresh refresh, CommerceThread thread) {
        Instant now = Instant.now();
        if (refresh.outcome() != EvidenceOutcome.PASS
                || refresh.constraintCertificate().overallResult() != ConstraintOutcome.PASS
                || !refresh.constraintCertificate().executable()
                || refresh.availability().outcome() != EvidenceOutcome.PASS
                || refresh.serviceability().outcome() != EvidenceOutcome.PASS) {
            throw conflict("EXECUTABLE_EVIDENCE_NOT_PASS",
                    "Only fully PASS executable evidence can feed a transaction proposal");
        }
        if (!refresh.quote().expiresAt().isAfter(now)
                || refresh.availability().expiresAt() == null
                || !refresh.availability().expiresAt().isAfter(now)
                || refresh.serviceability().expiresAt() == null
                || !refresh.serviceability().expiresAt().isAfter(now)) {
            throw conflict("AUTHORITATIVE_EVIDENCE_EXPIRED",
                    "Mutable authority evidence expired before proposal creation");
        }
        if (!refresh.threadId().equals(thread.threadId())
                || !refresh.buyerActorId().equals(thread.buyerActorId())) {
            throw conflict("AUTHORITATIVE_REFRESH_OWNER_MISMATCH",
                    "Refresh does not belong to the current buyer thread");
        }
    }

    private static boolean allowed(BuyerState state) {
        return state == BuyerState.CONSTRAINTS_VERIFIED || state == BuyerState.TRANSACTION_PROPOSED
                || state == BuyerState.RISK_EVALUATED || state == BuyerState.WAITING_FOR_USER
                || state == BuyerState.READY_TO_EXECUTE;
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static TransactionAuthorityException conflict(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.CONFLICT, message);
    }
}
