package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.commerce.TransactionModels.EvidenceOutcome;
import dev.agenticcommerce.gateway.commerce.TransactionModels.ReversibilityOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class CommerceRequestModels {
    private CommerceRequestModels() {}

    public enum RequestStatus { RUNNING, COMPLETED, WAITING_FOR_USER, FAILED }

    public record CommerceRequestRecord(UUID commerceRequestId,UUID requestId,UUID buyerActorId,
            UUID requestedThreadId,UUID threadId,String normalizedText,String materialHash,
            RequestStatus status,JsonNode authoritativeResult,String failureCode,
            Instant createdAt,Instant updatedAt,Instant completedAt) {}

    public record MaterialRequirement(String field,ConstraintClassification classification,
            int startOffset,int endOffset,AmbiguityState ambiguity) {}

    public record AuthoritativeProductFact(UUID factId,String type,JsonNode value,String authorityTier,
            String source,String resolutionState,Instant observedAt,Instant expiresAt,String evidenceReference) {}

    public record AuthoritativeProductLine(UUID productId,String merchantSku,String productName,String brand,
            String variant,String sizeStorage,String colour,String category,int quantity,
            Long unitAmountMinor,Long lineAmountMinor,List<AuthoritativeProductFact> facts) {}

    public record ConstraintSummary(String key,ConstraintOutcome result,boolean safetyCritical,
            JsonNode normalizedRequirement,List<String> evidenceReferences) {}

    public record CommerceProgressStep(String code,String label,List<String> evidenceReferences) {}

    public record CommerceRequestResult(UUID requestId,UUID threadId,BuyerState state,RequestStatus requestStatus,
            boolean clarificationRequired,String clarificationQuestion,Integer currentIntentVersion,
            IntentGoal goal,String category,Long budgetAmountMinor,String budgetCurrency,
            List<MaterialRequirement> hardRequirements,List<String> softPreferences,
            UUID merchantId,String merchantDisplayName,String merchantLogoUrl,UUID catalogueVersionId,String catalogueVersion,
            UUID cartId,String cartHash,List<AuthoritativeProductLine> products,
            UUID quoteRecordId,String merchantQuoteId,String merchantQuoteVersion,
            Long subtotalMinor,Long taxMinor,Long deliveryMinor,Long feesMinor,
            Long authoritativeFinalAmountMinor,String authoritativeCurrency,Instant quoteExpiresAt,
            UUID availabilityRefreshId,EvidenceOutcome availabilityOutcome,String availabilityReasonCode,
            UUID serviceabilityEvidenceId,EvidenceOutcome serviceabilityOutcome,String serviceabilityReasonCode,
            UUID constraintCertificateId,String constraintCertificateHash,ConstraintOutcome constraintOverall,
            List<ConstraintSummary> constraints,UUID transactionProposalId,String transactionProposalHash,
            Instant proposalExpiresAt,ReversibilityOutcome riskOutcome,List<String> riskReasonCodes,
            boolean explicitAuthorizationRequired,boolean paymentReady,String authorizationState,String nextAction,
            List<CommerceProgressStep> progress,List<String> evidenceReferences,String failureCode,
            VisualCommerceModels.VisionObservationView visualObservation,String visualMatchType,
            List<String> visualMatchReasons) {}
}
