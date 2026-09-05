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

export type CommerceSubmissionPhase = "IDLE" | "SUBMITTING" | "PROCESSING";

export function isSubmissionBusy(phase: CommerceSubmissionPhase) {
  return phase !== "IDLE";
}

export function isUserMessageSending(phase: CommerceSubmissionPhase) {
  return phase === "SUBMITTING";
}

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
  const currentProposalId = result.transactionProposalId;
  const currentAuthorization = authorization?.proposalId === currentProposalId ? authorization : null;
  const currentPayment = payment?.proposalId === currentProposalId ? payment : null;
  const currentFulfillment = currentPayment?.paymentState === "PAYMENT_CONFIRMED"
    && fulfillment?.paymentState === "PAYMENT_CONFIRMED"
    && fulfillment.executionId === currentPayment.executionId
    ? fulfillment
    : null;
  const expired = result.proposalExpiresAt ? new Date(result.proposalExpiresAt).getTime() <= now : true;
  const unsafeUnknown = hasSafetyCriticalUnknown(result);
  const canAuthorize = Boolean(
    result.paymentReady && result.transactionProposalId && !expired && !unsafeUnknown && !currentAuthorization,
  );
  const canOpenCheckout = Boolean(
    currentAuthorization?.decision === "AUTHORIZED" &&
      checkout &&
      !callbackAccepted &&
      (!currentPayment || ["NOT_STARTED", "ORDER_CREATED"].includes(currentPayment.paymentState)),
  );
  const canReconcile = Boolean(
    currentPayment &&
      ["PAYMENT_PENDING", "PAYMENT_UNCERTAIN"].includes(currentPayment.paymentState) &&
      currentPayment.reconciliationAttempts < currentPayment.reconciliationMaximumAttempts,
  );

  if (currentPayment?.paymentState === "PAYMENT_CONFIRMED") {
    if (currentFulfillment?.fulfillmentState === "FULFILLED") return { phase: "FULFILLED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
    if (currentFulfillment?.fulfillmentState === "TERMINAL_FAILURE" || currentFulfillment?.fulfillmentState === "COMPENSATION_REQUIRED") {
      return { phase: "PAYMENT_VERIFIED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
    }
    return { phase: "FINALIZING", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
  }
  if (currentPayment?.paymentState === "PAYMENT_FAILED") return { phase: "PAYMENT_FAILED", canAuthorize: false, canOpenCheckout: false, canReconcile: false };
  if (currentPayment?.paymentState === "PAYMENT_UNCERTAIN") return { phase: "PAYMENT_UNCERTAIN", canAuthorize: false, canOpenCheckout, canReconcile };
  if (callbackAccepted || currentPayment?.paymentState === "PAYMENT_PENDING") return { phase: "PAYMENT_SUBMITTED", canAuthorize: false, canOpenCheckout, canReconcile };
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
