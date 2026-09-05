import type {
  ActorSession,
  AddressInput,
  BuyerAddress,
  BuyerProfile,
  BuyerVoicePreference,
  CallbackResult,
  CallbackSubmission,
  CheckoutInitialization,
  CommerceRequestResult,
  CommerceThread,
  ExecutionGateResult,
  FulfillmentView,
  MerchantAccountLink,
  OnboardingStatus,
  PaymentStateView,
  ProfileInput,
  ReconciliationResult,
  ThreadMessage,
  AuthorizationDecision,
} from "./types";
import { CommerceResponseContractError, parseCommerceRequestResult } from "./commerce-contract";

type CsrfToken = { headerName: string; token: string };
export type BuyerVoiceToken = { token: string; model: string; preferredLanguage: string | null; voiceName: BuyerVoicePreference["voiceName"] };

export class BuyerApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly requestPath?: string,
    readonly requestMethod?: string,
  ) {
    super(message);
  }
}

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    let code: string | undefined;
    try {
      const body = (await response.json()) as { code?: string; error?: string; message?: string };
      code = body.code ?? body.error;
      message = response.status === 403 && body.error === "forbidden"
        ? "Your secure Buyer session did not authorize this request. Refresh the conversation and try again."
        : body.message ?? body.code ?? body.error ?? message;
    } catch {
      // Preserve the bounded status-based message for non-JSON failures.
    }
    throw new BuyerApiError(response.status, message, code);
  }
  if (response.status === 204) return undefined as T;
  try {
    return await response.json() as T;
  } catch {
    throw new BuyerApiError(502, "Amana received an unreadable response. Please retry.", "INVALID_API_RESPONSE");
  }
}

async function csrf(): Promise<CsrfToken> {
  return json<CsrfToken>(
    await fetch("/api/auth/csrf", { credentials: "include", cache: "no-store" }),
  );
}

async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const mutating = !["GET", "HEAD", "OPTIONS"].includes(method);
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");
    if (mutating) {
      const token = await csrf();
      headers.set(token.headerName, token.token);
    }
    const response = await fetch(path, { ...init, headers, credentials: "include", cache: "no-store" });
    if (response.status === 403 && mutating && attempt === 0) {
      // A denied request never reached its controller. Re-prove the authenticated session and
      // retry once with a newly emitted masked token; authorization remains Spring-owned.
      const actor = await fetch("/api/auth/me", { credentials: "include", cache: "no-store" });
      if (actor.ok) continue;
    }
    try {
      return await json<T>(response);
    } catch (error) {
      if (error instanceof BuyerApiError) {
        if (error.status === 403 && process.env.NODE_ENV !== "production") {
          console.warn(`[buyer-api] denied method=${method} path=${path}`);
        }
        throw new BuyerApiError(error.status, error.message, error.code, path, method);
      }
      throw error;
    }
  }
  throw new BuyerApiError(403, "forbidden", "forbidden", path, method);
}

export const buyerApi = {
  me: () => request<ActorSession>("/api/auth/me"),
  async login(identityHandle: string, password: string) {
    const token = await csrf();
    const response = await fetch("/api/auth/login", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json", [token.headerName]: token.token },
      body: JSON.stringify({ identityHandle, password }),
    });
    const actor = await json<ActorSession>(response);
    await csrf();
    return actor;
  },
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),
  threads: () => request<CommerceThread[]>("/api/buyer/threads"),
  thread: (id: string) => request<CommerceThread>(`/api/buyer/threads/${id}`),
  messages: (id: string) => request<ThreadMessage[]>(`/api/buyer/threads/${id}/messages`),
  async createCommerceRequest(
    requestId: string,
    text: string,
    threadId?: string,
  ) {
    const value = await request<unknown>("/api/buyer/commerce-requests", {
      method: "POST",
      body: JSON.stringify({ requestId, threadId: threadId ?? null, text }),
    });
    return validatedCommerceResponse(value);
  },
  async createVisualCommerceRequest(requestId: string, image: File, text: string, threadId?: string) {
    const body = new FormData();
    body.set("requestId", requestId);
    if (threadId) body.set("threadId", threadId);
    if (text.trim()) body.set("text", text.trim());
    body.set("image", image, image.name);
    const value = await request<unknown>("/api/buyer/commerce-requests/visual", { method: "POST", body });
    return validatedCommerceResponse(value);
  },
  async commerceRequest(requestId: string) {
    const value = await request<unknown>(`/api/buyer/commerce-requests/${requestId}`);
    return validatedCommerceResponse(value);
  },
  async latestCommerceRequest(threadId: string) {
    const value = await request<unknown>(`/api/buyer/commerce-requests/thread/${threadId}`);
    return validatedCommerceResponse(value);
  },
  authorize: (threadId: string, proposalId: string) =>
    request<AuthorizationDecision>(transactionPath(threadId, proposalId, "/confirm"), { method: "POST" }),
  authorization: (threadId: string, proposalId: string) =>
    request<AuthorizationDecision>(transactionPath(threadId, proposalId, "/authorization")),
  reserveExecution: (threadId: string, proposalId: string) =>
    request<ExecutionGateResult>(transactionPath(threadId, proposalId, "/executions"), { method: "POST" }),
  execution: (threadId: string, proposalId: string) =>
    request<ExecutionGateResult["execution"]>(transactionPath(threadId, proposalId, "/execution")),
  createPaymentOrder: (threadId: string, proposalId: string) =>
    request<PaymentStateView>(transactionPath(threadId, proposalId, "/payment/order"), { method: "POST" }),
  checkout: (threadId: string, proposalId: string) =>
    request<CheckoutInitialization>(transactionPath(threadId, proposalId, "/payment/checkout")),
  submitPaymentCallback: (threadId: string, proposalId: string, submission: CallbackSubmission) =>
    request<CallbackResult>(transactionPath(threadId, proposalId, "/payment/callback"), {
      method: "POST",
      body: JSON.stringify(submission),
    }),
  payment: (threadId: string, proposalId: string) =>
    request<PaymentStateView>(transactionPath(threadId, proposalId, "/payment")),
  reconcilePayment: (threadId: string, proposalId: string) =>
    request<ReconciliationResult>(transactionPath(threadId, proposalId, "/payment/reconcile"), { method: "POST" }),
  fulfillment: (threadId: string, proposalId: string) =>
    request<FulfillmentView>(transactionPath(threadId, proposalId, "/fulfillment")),
  profile: () => request<BuyerProfile>("/api/buyer/onboarding/profile"),
  saveProfile: (input: ProfileInput) =>
    request<BuyerProfile>("/api/buyer/onboarding/profile", {
      method: "PUT",
      body: JSON.stringify(input),
    }),
  addresses: () => request<BuyerAddress[]>("/api/buyer/onboarding/addresses"),
  addAddress: (input: AddressInput) =>
    request<BuyerAddress>("/api/buyer/onboarding/addresses", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  selectAddress: (id: string) =>
    request<BuyerAddress>(`/api/buyer/onboarding/addresses/${id}/select`, { method: "POST" }),
  links: () => request<MerchantAccountLink[]>("/api/buyer/onboarding/merchant-links"),
  linkMerchant: (merchantId: string, username: string, password: string) =>
    request<MerchantAccountLink>("/api/buyer/onboarding/merchant-links", {
      method: "POST",
      body: JSON.stringify({ merchantId, username, password }),
    }),
  revokeLink: (id: string) =>
    request<MerchantAccountLink>(`/api/buyer/onboarding/merchant-links/${id}/revoke`, {
      method: "POST",
    }),
  onboardingStatus: () => request<OnboardingStatus>("/api/buyer/onboarding/status"),
  voicePreference: () => request<BuyerVoicePreference>("/api/buyer/settings/voice"),
  saveVoicePreference: (voiceName: BuyerVoicePreference["voiceName"]) =>
    request<BuyerVoicePreference>("/api/buyer/settings/voice", {
      method: "PUT",
      body: JSON.stringify({ voiceName }),
    }),
  createVoiceToken: (preferredLanguage: string | null) =>
    request<BuyerVoiceToken>("/api/buyer-voice/token", {
      method: "POST",
      body: JSON.stringify({ preferredLanguage }),
    }),
};

function validatedCommerceResponse(value: unknown): CommerceRequestResult {
  try {
    return parseCommerceRequestResult(value);
  } catch (error) {
    if (error instanceof CommerceResponseContractError) {
      throw new BuyerApiError(502, error.message, error.code);
    }
    throw error;
  }
}

function transactionPath(threadId: string, proposalId: string, suffix: string) {
  return `/api/buyer/threads/${threadId}/transaction/proposals/${proposalId}${suffix}`;
}

export const demoMerchants = [
  {
    key: "amazing",
    name: "Amazing",
    description: "Everyday electronics, home and lifestyle",
    merchantId: process.env.NEXT_PUBLIC_AMAZING_MERCHANT_ID ?? null,
    logoUrl: "/amana/merchant/amazing.png",
  },
  {
    key: "freshbasket",
    name: "FreshBasket",
    description: "Groceries, pantry staples and fresh food",
    merchantId: process.env.NEXT_PUBLIC_FRESHBASKET_MERCHANT_ID ?? null,
    logoUrl: null,
  },
] as const;
