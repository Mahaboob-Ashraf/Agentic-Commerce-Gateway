package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class TransactionProposalCanonicalizer {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;
    private final CanonicalJsonService canonical;

    public TransactionProposalCanonicalizer(ObjectMapper mapper, CanonicalJsonService canonical) {
        this.mapper = mapper;
        this.canonical = canonical;
    }

    public CanonicalProposal canonicalize(ProposalDraft draft) {
        ObjectNode material = material(draft);
        return new CanonicalProposal(material, canonical.hash(material));
    }

    public CanonicalProposal canonicalize(TransactionProposal proposal) {
        return canonicalize(new ProposalDraft(
                proposal.buyerActorId(), proposal.threadId(), proposal.merchantId(),
                proposal.authorityRefreshId(), proposal.authorityRefreshHash(),
                proposal.intentId(), proposal.intentVersion(), proposal.intentHash(),
                proposal.cartId(), proposal.cartVersion(), proposal.cartHash(),
                proposal.constraintCertificateId(), proposal.constraintCertificateHash(),
                proposal.quoteRecordId(), proposal.quoteHash(), proposal.merchantQuoteId(),
                proposal.merchantQuoteVersion(), proposal.availabilityRefreshId(),
                proposal.availabilityHash(), proposal.serviceabilityEvidenceId(),
                proposal.serviceabilityHash(), proposal.policySnapshotId(),
                proposal.policySnapshotVersion(), proposal.policySnapshotHash(),
                proposal.catalogueVersionId(), proposal.actionType(), proposal.subtotalMinor(),
                proposal.taxMinor(), proposal.feesMinor(), proposal.deliveryMinor(),
                proposal.finalAmountMinor(), proposal.currency(), proposal.quoteExpiresAt(),
                proposal.proposalExpiresAt(), proposal.lineItems()));
    }

    private ObjectNode material(ProposalDraft draft) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("buyerActorId", draft.buyerActorId().toString());
        root.put("threadId", draft.threadId().toString());
        root.put("merchantId", draft.merchantId().toString());
        root.put("actionType", draft.actionType().name());
        reference(root, "authorityRefresh", draft.authorityRefreshId(), draft.authorityRefreshHash());
        ObjectNode intent = root.putObject("intent");
        intent.put("id", draft.intentId().toString());
        intent.put("version", draft.intentVersion());
        intent.put("hash", draft.intentHash());
        ObjectNode cart = root.putObject("cart");
        cart.put("id", draft.cartId().toString());
        cart.put("version", draft.cartVersion());
        cart.put("hash", draft.cartHash());
        reference(root, "constraintCertificate", draft.constraintCertificateId(),
                draft.constraintCertificateHash());
        ObjectNode quote = root.putObject("quote");
        quote.put("recordId", draft.quoteRecordId().toString());
        quote.put("evidenceHash", draft.quoteHash());
        quote.put("merchantQuoteId", normalized(draft.merchantQuoteId()));
        nullable(quote, "merchantQuoteVersion", draft.merchantQuoteVersion());
        quote.put("expiresAt", persistedInstant(draft.quoteExpiresAt()));
        reference(root, "availability", draft.availabilityRefreshId(), draft.availabilityHash());
        reference(root, "serviceability", draft.serviceabilityEvidenceId(), draft.serviceabilityHash());
        ObjectNode policy = root.putObject("policy");
        policy.put("snapshotId", draft.policySnapshotId().toString());
        policy.put("version", draft.policySnapshotVersion());
        policy.put("hash", draft.policySnapshotHash());
        root.put("catalogueVersionId", draft.catalogueVersionId().toString());
        ObjectNode money = root.putObject("money");
        money.put("subtotalMinor", draft.subtotalMinor());
        money.put("taxMinor", draft.taxMinor());
        money.put("feesMinor", draft.feesMinor());
        money.put("deliveryMinor", draft.deliveryMinor());
        money.put("finalAmountMinor", draft.finalAmountMinor());
        money.put("currency", normalized(draft.currency()).toUpperCase(java.util.Locale.ROOT));
        root.put("proposalExpiresAt", persistedInstant(draft.proposalExpiresAt()));
        var lines = root.putArray("lineItems");
        draft.lineItems().stream()
                .sorted(Comparator.comparing(ProposalLineItem::merchantSku)
                        .thenComparing(ProposalLineItem::productId))
                .forEach(item -> {
                    ObjectNode line = lines.addObject();
                    line.put("productId", item.productId().toString());
                    line.put("merchantSku", normalized(item.merchantSku()));
                    nullable(line, "variant", item.variant());
                    line.put("quantity", item.quantity());
                    line.put("unitAmountMinor", item.unitAmountMinor());
                    line.put("lineAmountMinor", item.lineAmountMinor());
                });
        return root;
    }

    private static void reference(ObjectNode root, String name, java.util.UUID id, String hash) {
        ObjectNode value = root.putObject(name);
        value.put("id", id.toString());
        value.put("hash", hash);
    }

    private static void nullable(ObjectNode node, String name, String value) {
        if (value == null) node.putNull(name); else node.put(name, normalized(value));
    }

    private static String normalized(String value) {
        return value.strip();
    }

    /** PostgreSQL TIMESTAMPTZ preserves microseconds; hash the exact value that can round-trip. */
    private static String persistedInstant(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS).toString();
    }

    public record CanonicalProposal(JsonNode material, String hash) {}
}
