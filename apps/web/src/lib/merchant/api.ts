import type {
  AgentCommerceManifest,
  CatalogueHealth,
  MerchantActor,
  MerchantAccess,
  MerchantOperationalSnapshot,
  PolicyDocument,
  WorkbenchRunData,
  AgentizationRun,
  AgentObservation,
  CapabilityMappingProposal,
  CapabilityContractTestRun,
  ReadinessEvaluation,
  MerchantClarification,
} from "./types";

type CsrfToken = { headerName: string; token: string };

export class MerchantApiError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string) {
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
      // Keep the status-based error when the backend did not return JSON.
    }
    throw new MerchantApiError(response.status, message, code);
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

async function optional<T>(requestPromise: Promise<T>): Promise<T | null> {
  try {
    return await requestPromise;
  } catch (error) {
    if (error instanceof MerchantApiError && error.status === 404) return null;
    throw error;
  }
}

export const merchantApi = {
  me: () => request<MerchantActor>("/api/auth/me"),
  async login(identityHandle: string, password: string) {
    const token = await csrf();
    const response = await fetch("/api/auth/login", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json", [token.headerName]: token.token },
      body: JSON.stringify({ identityHandle, password }),
    });
    return json<MerchantActor>(response);
  },
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),
  merchants: () => request<MerchantAccess[]>("/api/merchants"),
  latestManifest: (merchantId: string) =>
    optional(request<AgentCommerceManifest>(`/api/merchants/${merchantId}/agentization/manifests/latest`)),
  catalogueHealth: (merchantId: string) =>
    optional(request<CatalogueHealth>(`/api/merchants/${merchantId}/catalogue/health`)),
  policies: (merchantId: string) =>
    request<PolicyDocument[]>(`/api/merchants/${merchantId}/agentization/policies`),
  run: (merchantId: string, runId: string) =>
    request<AgentizationRun>(`/api/merchants/${merchantId}/agentization/runs/${runId}`),
  observations: (merchantId: string, runId: string) =>
    request<AgentObservation[]>(`/api/merchants/${merchantId}/agentization/runs/${runId}/observations`),
  mappings: (merchantId: string, runId: string) =>
    request<CapabilityMappingProposal[]>(`/api/merchants/${merchantId}/agentization/runs/${runId}/mapping-proposals`),
  tests: (merchantId: string, runId: string) =>
    request<CapabilityContractTestRun[]>(`/api/merchants/${merchantId}/agentization/runs/${runId}/contract-tests`),
  readiness: (merchantId: string, runId: string) =>
    request<ReadinessEvaluation[]>(`/api/merchants/${merchantId}/agentization/runs/${runId}/readiness`),
  clarifications: (merchantId: string, runId: string) =>
    request<MerchantClarification[]>(`/api/merchants/${merchantId}/agentization/runs/${runId}/clarifications`),
};

export async function loadWorkbenchRun(
  merchantId: string,
  runId: string,
): Promise<WorkbenchRunData> {
  const [run, observations, mappings, tests, readiness, clarifications] = await Promise.all([
    merchantApi.run(merchantId, runId),
    merchantApi.observations(merchantId, runId),
    merchantApi.mappings(merchantId, runId),
    merchantApi.tests(merchantId, runId),
    merchantApi.readiness(merchantId, runId),
    merchantApi.clarifications(merchantId, runId),
  ]);
  return { run, observations, mappings, tests, readiness, clarifications };
}

export async function loadOperationalSnapshot(
  merchantId: string,
): Promise<MerchantOperationalSnapshot> {
  const [catalogueResult, manifestResult, policiesResult] = await Promise.allSettled([
    merchantApi.catalogueHealth(merchantId),
    merchantApi.latestManifest(merchantId),
    merchantApi.policies(merchantId),
  ]);

  const errors: string[] = [];
  if (catalogueResult.status === "rejected") errors.push("Catalogue health is unavailable");
  if (manifestResult.status === "rejected") errors.push("Manifest status is unavailable");
  if (policiesResult.status === "rejected") errors.push("Policy status is unavailable");

  return {
    catalogue: catalogueResult.status === "fulfilled" ? catalogueResult.value : null,
    manifest: manifestResult.status === "fulfilled" ? manifestResult.value : null,
    policies: policiesResult.status === "fulfilled" ? policiesResult.value : [],
    errors,
  };
}
