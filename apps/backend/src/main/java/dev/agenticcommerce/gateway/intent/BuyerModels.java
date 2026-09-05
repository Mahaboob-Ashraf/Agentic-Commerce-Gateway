package dev.agenticcommerce.gateway.intent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class BuyerModels {
    private BuyerModels() {}

    public enum BuyerState {
        UNDERSTANDING, SEARCHING, CART_PROPOSED, CONSTRAINTS_VERIFIED, WAITING_FOR_USER,
        TRANSACTION_PROPOSED, RISK_EVALUATED, READY_TO_EXECUTE
    }
    public enum BuyerTool {
        COMPILE_INTENT, REQUEST_CLARIFICATION, DISCOVER_MERCHANTS, SEARCH_PRODUCTS,
        BUILD_CANDIDATE_CART, GET_QUOTE, VERIFY_CONSTRAINTS
    }
    public enum ActionOutcome { SUCCESS, FAILURE, DENIED, WAITING }
    public enum IntentGoal { PURCHASE_PRODUCT, PURCHASE_FOOD }
    public enum ConstraintClassification { HARD, HARD_SAFETY, SOFT }
    public enum MaterialFieldKey {
        BUDGET, CATEGORY, MERCHANT_SKU, GTIN, BRAND, VARIANT, SIZE_STORAGE, COLOUR,
        VEGETARIAN, ALLERGEN, EXCLUDED_MATERIAL, PREFERENCES
    }
    public enum AmbiguityState { CLEAR, AMBIGUOUS }
    public enum SubstitutionPolicy { ALLOW, PROHIBIT, UNKNOWN }
    public enum ConstraintOutcome { PASS, FAIL, UNKNOWN }
    public enum ConstraintType { USER, BUYER_AUTHORITY, MERCHANT_PRODUCT, SAFETY_COMPLIANCE }
    public enum DiscoveryOutcome { ELIGIBLE, NO_ELIGIBLE_MERCHANT, NEEDS_MULTI_MERCHANT, NO_SINGLE_MERCHANT }

    public record EvidenceSpan(UUID sourceMessageId, int startOffset, int endOffset) {}
    public record MaterialField(String field, ConstraintClassification classification,
            EvidenceSpan evidence, BigDecimal modelSignal, AmbiguityState ambiguity) {}

    public record CompiledIntent(IntentGoal goal, String categoryRequest, Long budgetAmountMinor,
            String currency, String exactMerchantSku, String exactGtin, String exactBrand,
            String exactVariant, String exactSizeStorage, String exactColour,
            Boolean vegetarian, String prohibitedAllergen, Integer quantity, Integer people,
            SubstitutionPolicy substitutionPolicy, String deliveryHint, List<String> excludedMaterials,List<String> softPreferences,
            List<MaterialField> materialFields, AmbiguityState ambiguityState,
            String clarificationQuestion, String provider, String model) {
        public CompiledIntent(IntentGoal goal, String categoryRequest, Long budgetAmountMinor,
                String currency, String exactMerchantSku, String exactGtin, String exactBrand,
                String exactVariant, String exactSizeStorage, String exactColour,
                Boolean vegetarian, String prohibitedAllergen, Integer quantity, Integer people,
                SubstitutionPolicy substitutionPolicy, String deliveryHint, List<String> softPreferences,
                List<MaterialField> materialFields, AmbiguityState ambiguityState,
                String clarificationQuestion, String provider, String model) {
            this(goal,categoryRequest,budgetAmountMinor,currency,exactMerchantSku,exactGtin,exactBrand,
                    exactVariant,exactSizeStorage,exactColour,vegetarian,prohibitedAllergen,quantity,people,
                    substitutionPolicy,deliveryHint,List.of(),softPreferences,materialFields,ambiguityState,
                    clarificationQuestion,provider,model);
        }
        public CompiledIntent(IntentGoal goal, String categoryRequest, Long budgetAmountMinor,
                String currency, String exactMerchantSku, String exactGtin, String exactVariant,
                Boolean vegetarian, String prohibitedAllergen, Integer quantity, Integer people,
                SubstitutionPolicy substitutionPolicy, String deliveryHint, List<String> softPreferences,
                List<MaterialField> materialFields, AmbiguityState ambiguityState,
                String clarificationQuestion, String provider, String model) {
            this(goal, categoryRequest, budgetAmountMinor, currency, exactMerchantSku, exactGtin,
                    null, exactVariant, null, null, vegetarian, prohibitedAllergen, quantity, people,
                    substitutionPolicy, deliveryHint, List.of(),softPreferences, materialFields, ambiguityState,
                    clarificationQuestion, provider, model);
        }
    }

    public record CommerceThread(UUID threadId, UUID buyerActorId, String title, BuyerState state,
            Integer currentIntentVersion, Integer currentCartVersion, UUID currentQuoteId,
            UUID currentCertificateId, UUID currentAuthorityRefreshId, UUID currentProposalId,
            UUID currentReversibilityEvaluationId, UUID currentAuthorizationId,
            UUID currentExecutionId, int stepCount, int maximumSteps,
            int repeatedFailureCount, Instant wallClockDeadline, long lockVersion,
            Instant createdAt, Instant updatedAt) {}

    public record ThreadMessage(UUID messageId, UUID threadId, UUID buyerActorId,
            int messageNumber, String inputSource, String normalizedText,
            String contentHash, Instant createdAt) {}

    public record BuyerIntent(UUID intentId, UUID threadId, UUID buyerActorId, int intentVersion,
            UUID sourceMessageId, CompiledIntent compiled, String modelOutputHash,
            String intentHash, Instant createdAt) {}

    public record MerchantCandidate(UUID merchantId, String displayName, UUID manifestId,
            int manifestVersion, UUID catalogueVersionId, String catalogueVersion,
            UUID quoteMappingId, boolean serviceabilityResolved) {}

    public record MerchantDiscovery(UUID discoveryId, UUID threadId, UUID buyerActorId,
            UUID intentId, int intentVersion, DiscoveryOutcome outcome,
            List<String> requiredCapabilities, List<MerchantCandidate> eligibleMerchants,
            List<String> evidenceReferences, String discoveryHash, Instant createdAt) {}

    public record CandidateCartItem(UUID cartItemId, UUID productId, String merchantSku,
            String variant, int quantity, String selectionRationale,
            List<String> evidenceReferences) {}

    public record CandidateCart(UUID cartId, UUID threadId, UUID buyerActorId, UUID intentId,
            int intentVersion, UUID merchantId, int cartVersion, UUID catalogueVersionId,
            List<String> selectionEvidenceReferences, JsonNode alternatives, String cartHash,
            Instant createdAt, List<CandidateCartItem> items) {}

    public record MerchantQuoteItem(UUID quoteItemId, UUID productId, String merchantSku,
            int quantity, Long unitAmountMinor, Long lineAmountMinor) {}

    public record MerchantQuote(UUID quoteRecordId, UUID merchantId, UUID threadId,
            UUID buyerActorId, UUID cartId, int cartVersion, String cartHash,
            String merchantQuoteId, String merchantQuoteVersion, Long subtotalMinor,
            Long taxMinor, Long deliveryMinor, Long feesMinor, Long finalAmountMinor,
            String currency, Instant expiresAt, Boolean stockGuaranteed,
            Boolean priceGuaranteed, UUID executableMappingProposalId, String evidenceHash,
            Instant observedAt, Instant createdAt, List<MerchantQuoteItem> items) {}

    public record ConstraintResult(UUID constraintResultId, String constraintKey,
            ConstraintType constraintType, JsonNode normalizedRequirement,
            ConstraintOutcome result, boolean safetyCritical,
            List<String> evidenceReferences, Instant evaluatedAt) {}

    public record ConstraintCertificate(UUID certificateId, UUID threadId, UUID buyerActorId,
            int certificateVersion, UUID intentId, int intentVersion, String intentHash,
            UUID cartId, int cartVersion, String cartHash, UUID quoteRecordId,
            String quoteHash, UUID catalogueVersionId, UUID merchantId, UUID policySnapshotId,
            UUID availabilityRefreshId, String availabilityEvidenceHash,
            UUID serviceabilityEvidenceId, String serviceabilityEvidenceHash, boolean executable,
            JsonNode sourceFreshness, List<String> evidenceReferences,
            ConstraintOutcome overallResult, String certificateHash,
            Instant evaluatedAt, List<ConstraintResult> results) {}

    public record BuyerAgentAction(UUID actionId, UUID threadId, UUID buyerActorId,
            int stepNumber, BuyerState stateBefore, BuyerState stateAfter, BuyerTool selectedTool,
            String inputHash, List<String> resultEvidenceReferences, String conciseRationale,
            ActionOutcome outcome, String actionSignature, String providerName,
            String providerModel, Instant createdAt) {}

    public record AdvanceResult(CommerceThread thread, BuyerAgentAction action) {}
}
