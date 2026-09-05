import type { CommerceRequestResult } from "./types";

const requestStatuses = new Set(["RUNNING", "COMPLETED", "WAITING_FOR_USER", "FAILED"]);

export class CommerceResponseContractError extends Error {
  readonly code = "INVALID_COMMERCE_RESPONSE";

  constructor() {
    super("Amana received an invalid commerce response. Your request was not accepted; please retry.");
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Validates the durable identity and collection fields the Buyer UI relies on before any state is
 * mutated. A missing/204 or malformed upstream response therefore fails closed instead of becoming
 * a JavaScript property-access failure in the conversation workspace.
 */
export function parseCommerceRequestResult(value: unknown): CommerceRequestResult {
  if (
    !isRecord(value)
    || typeof value.requestId !== "string"
    || value.requestId.length === 0
    || typeof value.threadId !== "string"
    || value.threadId.length === 0
    || typeof value.state !== "string"
    || !requestStatuses.has(String(value.requestStatus))
    || typeof value.clarificationRequired !== "boolean"
    || !Array.isArray(value.hardRequirements)
    || !Array.isArray(value.softPreferences)
    || !Array.isArray(value.products)
    || !Array.isArray(value.constraints)
    || !Array.isArray(value.riskReasonCodes)
    || !Array.isArray(value.progress)
    || !Array.isArray(value.evidenceReferences)
    || typeof value.explicitAuthorizationRequired !== "boolean"
    || typeof value.paymentReady !== "boolean"
    || typeof value.authorizationState !== "string"
    || typeof value.nextAction !== "string"
    || (value.merchantLogoUrl !== null && value.merchantLogoUrl !== undefined && (
      typeof value.merchantLogoUrl !== "string"
      || !((value.merchantLogoUrl.startsWith("/") && !value.merchantLogoUrl.startsWith("//")) || value.merchantLogoUrl.startsWith("https://"))
    ))
    || (value.visualObservation !== null && value.visualObservation !== undefined && !isRecord(value.visualObservation))
  ) {
    throw new CommerceResponseContractError();
  }
  return {
    ...value,
    merchantLogoUrl: value.merchantLogoUrl ?? null,
    visualObservation: value.visualObservation ?? null,
    visualMatchType: value.visualMatchType ?? null,
    visualMatchReasons: Array.isArray(value.visualMatchReasons) ? value.visualMatchReasons : [],
  } as CommerceRequestResult;
}
