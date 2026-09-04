import type {
  ActorSession,
  AddressInput,
  BuyerAddress,
  BuyerProfile,
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

type CsrfToken = { headerName: string; token: string };
export type BuyerVoiceToken = { token: string; model: string; preferredLanguage: string | null };

export class BuyerApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
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
      code = body.code;
      message = body.message ?? body.code ?? body.error ?? message;
    } catch {
      // Preserve the bounded status-based message for non-JSON failures.
    }
    throw new BuyerApiError(response.status, message, code);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

async function csrf(): Promise<CsrfToken> {
  return json<CsrfToken>(
    await fetch("/api/auth/csrf", { credentials: "include", cache: "no-store" }),
  );
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    const token = await csrf();
    headers.set(token.headerName, token.token);
  }
  return json<T>(
    await fetch(path, { ...init, headers, credentials: "include", cache: "no-store" }),
  );
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
  createCommerceRequest: (requestId: string, text: string, threadId?: string) =>
    request<CommerceRequestResult>("/api/buyer/commerce-requests", {
      method: "POST",
      body: JSON.stringify({ requestId, threadId: threadId ?? null, text }),
    }),
  latestCommerceRequest: (threadId: string) =>
    request<CommerceRequestResult>(`/api/buyer/commerce-requests/thread/${threadId}`),
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
  createVoiceToken: (preferredLanguage: string | null) =>
    request<BuyerVoiceToken>("/api/buyer-voice/token", {
      method: "POST",
      body: JSON.stringify({ preferredLanguage }),
    }),
};

function transactionPath(threadId: string, proposalId: string, suffix: string) {
  return `/api/buyer/threads/${threadId}/transaction/proposals/${proposalId}${suffix}`;
}

export const demoMerchants = [
  {
    key: "amazing",
    name: "Amazing",
    description: "Everyday electronics, home and lifestyle",
    merchantId: process.env.NEXT_PUBLIC_AMAZING_MERCHANT_ID ?? null,
  },
  {
    key: "freshbasket",
    name: "FreshBasket",
    description: "Groceries, pantry staples and fresh food",
    merchantId: process.env.NEXT_PUBLIC_FRESHBASKET_MERCHANT_ID ?? null,
  },
] as const;
