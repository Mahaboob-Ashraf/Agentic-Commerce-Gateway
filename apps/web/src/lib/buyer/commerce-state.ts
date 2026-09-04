import type {
  AuthorizationDecision,
  CommerceRequestResult,
  FulfillmentView,
  PaymentStateView,
} from "./types";

export type CommercePhase =
  | "EMPTY"
  | "NO_MATCH"
  | "BLOCKED"
  | "PROPOSAL"
  | "CHECKOUT_READY"
  | "PAYMENT_SUBMITTED"
  | "PAYMENT_UNCERTAIN"
  | "PAYMENT_FAILED"
  | "PAYMENT_VERIFIED"
  | "FINALIZING"
  | "FULFILLED";

export type JourneySnapshot = {
  phase: CommercePhase;
  canAuthorize: boolean;
  canOpenCheckout: boolean;
  canReconcile: boolean;
};

export function hasSafetyCriticalUnknown(result: CommerceRequestResult | null) {
  return Boolean(result?.constraints.some((constraint) => constraint.safetyCritical && constraint.result === "UNKNOWN"));
}

export function deriveJourneySnapshot(
  result: CommerceRequestResult | null,
  authorization: AuthorizationDecision | null,
  checkout: boolean,
  callbackAccepted: boolean,
  payment: PaymentStateView | null,
  fulfillment: FulfillmentView | null,
  now = Date.now(),
): JourneySnapshot {
  if (!result) return { phase: "EMPTY", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
  const expired = result.proposalExpiresAt ? new Date(result.proposalExpiresAt).getTime() <= now : true;
  const unsafeUnknown = hasSafetyCriticalUnknown(result);
  const canAuthorize = Boolean(
    result.paymentReady && result.transactionProposalId && !expired && !unsafeUnknown && !authorization,
  );
  const canOpenCheckout = Boolean(
    authorization?.decision === "AUTHORIZED" &&
      checkout &&
      !callbackAccepted &&
      (!payment || ["NOT_STARTED", "ORDER_CREATED"].includes(payment.paymentState)),
  );
  const canReconcile = Boolean(
    payment &&
      ["PAYMENT_PENDING", "PAYMENT_UNCERTAIN"].includes(payment.paymentState) &&
      payment.reconciliationAttempts < payment.reconciliationMaximumAttempts,
  );

  if (payment?.paymentState === "PAYMENT_CONFIRMED") {
    if (fulfillment?.fulfillmentState === "FULFILLED") return { phase: "FULFILLED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
    if (fulfillment?.fulfillmentState === "TERMINAL_FAILURE" || fulfillment?.fulfillmentState === "COMPENSATION_REQUIRED") {
      return { phase: "PAYMENT_VERIFIED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
    }
    return { phase: "FINALIZING", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
  }
  if (payment?.paymentState === "PAYMENT_FAILED") return { phase: "PAYMENT_FAILED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
  if (payment?.paymentState === "PAYMENT_UNCERTAIN") return { phase: "PAYMENT_UNCERTAIN", canAuthorize: false, canOpenCheckout, canReconcile };
  if (callbackAccepted || payment?.paymentState === "PAYMENT_PENDING") return { phase: "PAYMENT_SUBMITTED", canAuthorize: false, canOpenCheckout, canReconcile };
  if (checkout) return { phase: "CHECKOUT_READY", canAuthorize: false, canOpenCheckout, canReconcile: false };
  if (result.transactionProposalId) return { phase: unsafeUnknown || !result.paymentReady ? "BLOCKED" : "PROPOSAL", canAuthorize, canOpenCheckout: false, canReconcile: false };
  if (result.clarificationRequired) return { phase: "NO_MATCH", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
  return { phase: "BLOCKED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
}

export class SingleFlightGate {
  private readonly active = new Set<string>();

  enter(key: string) {
    if (this.active.has(key)) return false;
    this.active.add(key);
    return true;
  }

  leave(key: string) {
    this.active.delete(key);
  }
}
