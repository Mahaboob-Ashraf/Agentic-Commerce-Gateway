package dev.agenticcommerce.gateway.risk;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Deterministic action-risk authority. It has no model/provider dependency. */
@Service
public class ReversibilityEngine {
    private final TransactionAuthorityPolicy policy;

    public ReversibilityEngine(TransactionAuthorityPolicy policy) {
        this.policy = policy;
    }

    public Decision evaluate(RiskInput input) {
        List<String> blocking = blocking(input);
        if (!blocking.isEmpty()) return new Decision(ReversibilityOutcome.BLOCK, blocking);
        List<String> clarification = new ArrayList<>();
        if (input.unresolvedMaterialAmbiguity()) clarification.add("MATERIAL_INTENT_AMBIGUOUS");
        if (input.substitutionDecisionRequired()) clarification.add("SUBSTITUTION_DECISION_REQUIRED");
        if (!clarification.isEmpty()) return new Decision(ReversibilityOutcome.CLARIFY, clarification);
        if (!input.reversible()) {
            return new Decision(ReversibilityOutcome.EXPLICIT_CONFIRMATION,
                    List.of("ACTION_NOT_LOW_COST_REVERSIBLE"));
        }
        if (input.finalAmountMinor() > policy.autoExecuteMaximumAmountMinor()) {
            return new Decision(ReversibilityOutcome.EXPLICIT_CONFIRMATION,
                    List.of("AMOUNT_ABOVE_AUTO_EXECUTE_THRESHOLD"));
        }
        return new Decision(ReversibilityOutcome.AUTO_EXECUTE,
                List.of("LOW_RISK_REVERSIBLE_AUTHORITY_POLICY_PASS"));
    }

    private static List<String> blocking(RiskInput input) {
        List<String> reasons = new ArrayList<>();
        if (input.actionType() != ActionType.PURCHASE) reasons.add("UNSUPPORTED_ACTION_TYPE");
        if (!input.proposalValid()) reasons.add("PROPOSAL_EVIDENCE_INVALID");
        if (input.proposalExpired()) reasons.add("PROPOSAL_EXPIRED");
        if (input.hardConstraints() != EvidenceOutcome.PASS) reasons.add(
                input.hardConstraints() == EvidenceOutcome.FAIL
                        ? "HARD_CONSTRAINT_FAILED" : "HARD_CONSTRAINT_UNKNOWN");
        if (input.safetyCriticalUnknown()) reasons.add("SAFETY_CRITICAL_UNKNOWN");
        if (input.exactIdentity() != EvidenceOutcome.PASS) reasons.add("EXACT_IDENTITY_NOT_PASS");
        if (input.availability() != EvidenceOutcome.PASS) reasons.add(
                input.availability() == EvidenceOutcome.FAIL ? "STOCK_UNAVAILABLE" : "STOCK_UNKNOWN");
        if (input.serviceability() != EvidenceOutcome.PASS) reasons.add(
                input.serviceability() == EvidenceOutcome.FAIL
                        ? "SERVICEABILITY_FAILED" : "SERVICEABILITY_UNKNOWN");
        if (!input.merchantCapabilitiesReady()) reasons.add("MERCHANT_CAPABILITY_NOT_READY");
        if (!input.policyCoverageCurrent()) reasons.add("POLICY_EVIDENCE_NOT_CURRENT");
        return List.copyOf(reasons);
    }

    public record Decision(ReversibilityOutcome outcome, List<String> reasonCodes) {}
}
