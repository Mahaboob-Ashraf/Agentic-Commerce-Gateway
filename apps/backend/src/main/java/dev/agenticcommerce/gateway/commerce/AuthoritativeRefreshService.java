package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.CatalogueVersion;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.intent.AuthoritativeQuoteService;
import dev.agenticcommerce.gateway.intent.BuyerRepository;
import dev.agenticcommerce.gateway.intent.BuyerThreadService;
import dev.agenticcommerce.gateway.intent.ConstraintCertificateService;
import dev.agenticcommerce.gateway.risk.TransactionAuthorityPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthoritativeRefreshService {
    private final BuyerThreadService threads;
    private final BuyerRepository buyers;
    private final CatalogueRepository catalogues;
    private final TransactionAuthorityRepository repository;
    private final AuthoritativeQuoteService quotes;
    private final AuthoritativeAvailabilityService availability;
    private final AuthoritativeServiceabilityService serviceability;
    private final ConstraintCertificateService constraints;
    private final TransactionAuthorityPolicy policy;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public AuthoritativeRefreshService(
            BuyerThreadService threads, BuyerRepository buyers, CatalogueRepository catalogues,
            TransactionAuthorityRepository repository, AuthoritativeQuoteService quotes,
            AuthoritativeAvailabilityService availability,
            AuthoritativeServiceabilityService serviceability,
            ConstraintCertificateService constraints, TransactionAuthorityPolicy policy,
            CanonicalJsonService canonical, ObjectMapper mapper) {
        this.threads = threads;
        this.buyers = buyers;
        this.catalogues = catalogues;
        this.repository = repository;
        this.quotes = quotes;
        this.availability = availability;
        this.serviceability = serviceability;
        this.constraints = constraints;
        this.policy = policy;
        this.canonical = canonical;
        this.mapper = mapper;
    }

    @Transactional
    public AuthorityRefresh refresh(UUID buyerId, UUID threadId) {
        CommerceThread thread = threads.requireForUpdate(buyerId, threadId);
        if (!allowed(thread.state())) {
            throw conflict("AUTHORITATIVE_REFRESH_STATE_INVALID",
                    "Authoritative refresh requires a constraint-verified transaction state");
        }
        TransactionProposal current = repository.currentProposal(buyerId, threadId).orElse(null);
        if (current != null && repository.executionForProposal(buyerId, current.proposalId()).isPresent()) {
            throw conflict("EXECUTION_ALREADY_RESERVED", "Executed proposal evidence cannot be refreshed in place");
        }
        BuyerIntent intent = buyers.latestIntent(buyerId, threadId)
                .orElseThrow(() -> conflict("BUYER_INTENT_REQUIRED", "Current buyer intent is required"));
        CandidateCart cart = buyers.currentCart(buyerId, threadId)
                .orElseThrow(() -> conflict("CANDIDATE_CART_REQUIRED", "Current candidate cart is required"));
        MerchantAuthorityContext context = repository.currentMerchantAuthority(cart.merchantId())
                .orElseThrow(() -> conflict("MERCHANT_MANIFEST_REQUIRED", "Current merchant manifest is required"));
        requirePolicyAndCatalogue(context, cart);
        if (context.quoteCapability() == null || !context.quoteCapability().ready()) {
            throw conflict("GET_QUOTE_NOT_READY", "Current manifest does not advertise READY GET_QUOTE");
        }
        MerchantQuote currentQuote = buyers.currentQuote(buyerId, threadId).orElse(null);
        MerchantQuote quote = quoteUsable(currentQuote, cart, context, Instant.now())
                ? currentQuote : quotes.quote(cart, merchant(cart, context));
        requireCompleteQuote(quote, cart);
        AvailabilityRefresh stock = availability.refresh(cart, context);
        ServiceabilityEvidence delivery = serviceability.refresh(cart, intent, context);
        ConstraintCertificate certificate = constraints.evaluateExecutable(
                thread, intent, cart, quote, stock, delivery, context.policySnapshotId());
        EvidenceOutcome outcome = reduce(certificate, stock, delivery);
        List<String> refs = new ArrayList<>();
        refs.add("manifest:" + context.manifestId() + ":v" + context.manifestVersion());
        refs.add("policy:" + context.policySnapshotId() + ":v" + context.policySnapshotVersion()
                + ":" + context.policySnapshotHash());
        refs.add("quote:" + quote.quoteRecordId() + ":" + quote.evidenceHash());
        refs.add("availability:" + stock.availabilityRefreshId() + ":" + stock.evidenceHash());
        refs.add("serviceability:" + delivery.serviceabilityEvidenceId() + ":" + delivery.evidenceHash());
        refs.add("certificate:" + certificate.certificateId() + ":" + certificate.certificateHash());
        Instant now = Instant.now();
        var material = mapper.createObjectNode();
        material.put("threadId", threadId.toString());
        material.put("buyerActorId", buyerId.toString());
        material.put("merchantId", cart.merchantId().toString());
        material.put("cartHash", cart.cartHash());
        material.put("quoteHash", quote.evidenceHash());
        material.put("availabilityHash", stock.evidenceHash());
        material.put("serviceabilityHash", delivery.evidenceHash());
        material.put("constraintCertificateHash", certificate.certificateHash());
        material.put("manifestId", context.manifestId().toString());
        material.put("manifestVersion", context.manifestVersion());
        material.put("policySnapshotHash", context.policySnapshotHash());
        material.put("outcome", outcome.name());
        material.put("refreshedAt", now.toString());
        return repository.createAuthorityRefresh(cart, quote, stock, delivery, certificate,
                context, outcome, List.copyOf(refs), canonical.hash(material), now);
    }

    public AuthorityRefresh current(UUID buyerId, UUID threadId) {
        threads.require(buyerId, threadId);
        return repository.latestAuthorityRefresh(buyerId, threadId)
                .orElseThrow(() -> new TransactionAuthorityException("AUTHORITATIVE_REFRESH_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "Authoritative transaction refresh was not found"));
    }

    private boolean quoteUsable(
            MerchantQuote quote, CandidateCart cart, MerchantAuthorityContext context, Instant now) {
        return quote != null && quote.merchantId().equals(cart.merchantId())
                && quote.cartId().equals(cart.cartId()) && quote.cartHash().equals(cart.cartHash())
                && quote.executableMappingProposalId().equals(
                        context.quoteCapability().executableMappingProposalId())
                && quote.expiresAt().isAfter(now.plus(policy.minimumQuoteRemaining()))
                && "INR".equals(quote.currency()) && Boolean.TRUE.equals(quote.priceGuaranteed())
                && completeMoney(quote) && completeLines(quote, cart);
    }

    private static void requireCompleteQuote(MerchantQuote quote, CandidateCart cart) {
        if (quote == null || !quote.merchantId().equals(cart.merchantId())
                || !quote.cartId().equals(cart.cartId()) || !quote.cartHash().equals(cart.cartHash())
                || !quote.expiresAt().isAfter(Instant.now()) || !"INR".equals(quote.currency())
                || !Boolean.TRUE.equals(quote.priceGuaranteed()) || !completeMoney(quote)
                || !completeLines(quote, cart)) {
            throw unprocessable("QUOTE_NOT_EXECUTABLE",
                    "Exact fresh quote with complete integer INR money and line evidence is required");
        }
    }

    private static boolean completeMoney(MerchantQuote quote) {
        if (quote.subtotalMinor() == null || quote.taxMinor() == null || quote.deliveryMinor() == null
                || quote.feesMinor() == null || quote.finalAmountMinor() == null) return false;
        try {
            return Math.addExact(Math.addExact(quote.subtotalMinor(), quote.taxMinor()),
                    Math.addExact(quote.deliveryMinor(), quote.feesMinor())) == quote.finalAmountMinor();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean completeLines(MerchantQuote quote, CandidateCart cart) {
        if (quote.items().size() != cart.items().size()) return false;
        return cart.items().stream().allMatch(cartItem -> quote.items().stream().anyMatch(quoteItem ->
                quoteItem.productId().equals(cartItem.productId())
                        && quoteItem.merchantSku().equals(cartItem.merchantSku())
                        && quoteItem.quantity() == cartItem.quantity()
                        && quoteItem.unitAmountMinor() != null && quoteItem.lineAmountMinor() != null
                        && quoteItem.lineAmountMinor() == quoteItem.unitAmountMinor() * quoteItem.quantity()));
    }

    private static MerchantCandidate merchant(CandidateCart cart, MerchantAuthorityContext context) {
        return new MerchantCandidate(cart.merchantId(), "authoritative-refresh", context.manifestId(),
                context.manifestVersion(), cart.catalogueVersionId(), context.catalogueVersion(),
                context.quoteCapability().executableMappingProposalId(), false);
    }

    private static EvidenceOutcome reduce(
            ConstraintCertificate certificate, AvailabilityRefresh availability,
            ServiceabilityEvidence serviceability) {
        if (certificate.overallResult() == ConstraintOutcome.FAIL
                || availability.outcome() == EvidenceOutcome.FAIL
                || serviceability.outcome() == EvidenceOutcome.FAIL) return EvidenceOutcome.FAIL;
        if (certificate.overallResult() == ConstraintOutcome.UNKNOWN
                || availability.outcome() == EvidenceOutcome.UNKNOWN
                || serviceability.outcome() == EvidenceOutcome.UNKNOWN) return EvidenceOutcome.UNKNOWN;
        return EvidenceOutcome.PASS;
    }

    private void requirePolicyAndCatalogue(
            MerchantAuthorityContext context, CandidateCart cart) {
        if (context.policySnapshotId() == null || context.policySnapshotVersion() == null
                || context.policySnapshotHash() == null) {
            throw conflict("POLICY_SNAPSHOT_REQUIRED", "Current immutable merchant policy snapshot is required");
        }
        if (context.catalogueVersion() == null || context.catalogueVersion().isBlank()) {
            throw conflict("CATALOGUE_VERSION_REQUIRED", "Current manifest catalogue binding is required");
        }
        CatalogueVersion current = catalogues.latestPublished(cart.merchantId())
                .orElseThrow(() -> conflict("CATALOGUE_VERSION_REQUIRED",
                        "A current published merchant catalogue is required"));
        String expectedManifestBinding = "v" + current.version() + ":" + current.contentHash();
        if (!current.id().equals(cart.catalogueVersionId())
                || !expectedManifestBinding.equals(context.catalogueVersion())) {
            throw conflict("CATALOGUE_BINDING_NOT_CURRENT",
                    "Cart and manifest must bind the current exact published catalogue");
        }
    }

    private static boolean allowed(BuyerState state) {
        return state == BuyerState.CONSTRAINTS_VERIFIED || state == BuyerState.TRANSACTION_PROPOSED
                || state == BuyerState.RISK_EVALUATED || state == BuyerState.WAITING_FOR_USER
                || state == BuyerState.READY_TO_EXECUTE;
    }

    private static TransactionAuthorityException conflict(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.CONFLICT, message);
    }
    private static TransactionAuthorityException unprocessable(String code, String message) {
        return new TransactionAuthorityException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
