import type { CommerceRequestResult } from "./types";

export type VoiceOrbState =
  | "IDLE"
  | "CONNECTING"
  | "LISTENING"
  | "USER_SPEAKING"
  | "THINKING"
  | "AGENT_SPEAKING"
  | "COMMERCE_RUNNING"
  | "AWAITING_AUTHORIZATION"
  | "ERROR"
  | "RECONNECTING";

export type VoiceSessionState = {
  orb: VoiceOrbState;
  connected: boolean;
  commerceRunning: boolean;
  awaitingAuthorization: boolean;
  error: string | null;
};

export type VoiceSessionAction =
  | { type: "CONNECT" }
  | { type: "CONNECTED" }
  | { type: "USER_ACTIVITY"; active: boolean }
  | { type: "MODEL_THINKING" }
  | { type: "MODEL_AUDIO_STARTED" }
  | { type: "MODEL_AUDIO_ENDED" }
  | { type: "INTERRUPTED" }
  | { type: "COMMERCE_STARTED" }
  | { type: "COMMERCE_FINISHED"; awaitingAuthorization: boolean }
  | { type: "RECONNECT" }
  | { type: "FAIL"; message: string }
  | { type: "STOP" };

export const initialVoiceSessionState: VoiceSessionState = {
  orb: "IDLE",
  connected: false,
  commerceRunning: false,
  awaitingAuthorization: false,
  error: null,
};

export function reduceVoiceSession(
  state: VoiceSessionState,
  action: VoiceSessionAction,
): VoiceSessionState {
  switch (action.type) {
    case "CONNECT":
      return { orb: "CONNECTING", connected: false, commerceRunning: false, awaitingAuthorization: false, error: null };
    case "CONNECTED":
      return { ...state, orb: "LISTENING", connected: true, error: null };
    case "USER_ACTIVITY":
      return { ...state, orb: action.active ? "USER_SPEAKING" : "THINKING" };
    case "MODEL_THINKING":
      return { ...state, orb: "THINKING" };
    case "MODEL_AUDIO_STARTED":
      return { ...state, orb: "AGENT_SPEAKING" };
    case "MODEL_AUDIO_ENDED":
      return { ...state, orb: state.commerceRunning ? "COMMERCE_RUNNING" : "LISTENING" };
    case "INTERRUPTED":
      return { ...state, orb: "LISTENING" };
    case "COMMERCE_STARTED":
      return { ...state, orb: "COMMERCE_RUNNING", commerceRunning: true, awaitingAuthorization: false };
    case "COMMERCE_FINISHED":
      return {
        ...state,
        // The authoritative application result is now a new Live turn. Keep the session in a
        // conversational state while Gemini narrates it; once playback drains it returns to LISTENING.
        orb: "THINKING",
        commerceRunning: false,
        awaitingAuthorization: action.awaitingAuthorization,
      };
    case "RECONNECT":
      return { ...state, orb: "RECONNECTING", connected: false, error: null };
    case "FAIL":
      return { orb: "ERROR", connected: false, commerceRunning: false, awaitingAuthorization: false, error: action.message };
    case "STOP":
      return initialVoiceSessionState;
  }
}

export class VoiceToolCallGuard {
  private readonly seen = new Set<string>();

  begin(callId: string): boolean {
    if (this.seen.has(callId)) return false;
    this.seen.add(callId);
    return true;
  }

  clear(): void {
    this.seen.clear();
  }
}

export function validateVoiceCommerceQuery(value: unknown): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > 2_000) {
    throw new Error("INVALID_COMMERCE_REQUEST");
  }
  return value.trim();
}

export async function executeProductionVoiceCommerce(
  callId: string,
  query: unknown,
  guard: VoiceToolCallGuard,
  execute: (query: string) => Promise<CommerceRequestResult>,
): Promise<CommerceRequestResult | null> {
  if (!guard.begin(callId)) return null;
  return execute(validateVoiceCommerceQuery(query));
}

export function toAuthoritativeVoiceResult(result: CommerceRequestResult) {
  return {
    type: "APP_COMMERCE_RESULT",
    requestId: result.requestId,
    requestStatus: result.requestStatus,
    state: result.state,
    clarificationRequired: result.clarificationRequired,
    clarificationQuestion: result.clarificationQuestion,
    merchant: result.merchantId
      ? { id: result.merchantId, displayName: result.merchantDisplayName }
      : null,
    products: result.products.map((product) => ({
      productId: product.productId,
      merchantSku: product.merchantSku,
      name: product.productName,
      brand: product.brand,
      variant: product.variant,
      colour: product.colour,
      sizeStorage: product.sizeStorage,
      quantity: product.quantity,
      unitAmountMinor: product.unitAmountMinor,
    })),
    amount: {
      subtotalMinor: result.subtotalMinor,
      deliveryMinor: result.deliveryMinor,
      taxMinor: result.taxMinor,
      feesMinor: result.feesMinor,
      finalAmountMinor: result.authoritativeFinalAmountMinor,
      currency: result.authoritativeCurrency,
    },
    verification: {
      availability: result.availabilityOutcome,
      serviceability: result.serviceabilityOutcome,
      constraints: result.constraintOverall,
      risk: result.riskOutcome,
    },
    proposal: result.transactionProposalId
      ? {
          proposalId: result.transactionProposalId,
          paymentReady: result.paymentReady,
          explicitAuthorizationRequired: result.explicitAuthorizationRequired,
          authorizationBoundary: "ON_SCREEN_ONLY",
        }
      : null,
    paymentState: "NOT_STARTED",
    failureCode: result.failureCode,
  } as const;
}
