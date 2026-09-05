import "server-only";

import { existsSync } from "node:fs";
import { loadEnvFile } from "node:process";
import { resolve } from "node:path";
import { GoogleGenAI } from "@google/genai";
import { NextResponse } from "next/server";
import {
  BUYER_GEMINI_LIVE_MODEL,
  buildBuyerGeminiLiveConfig,
  DEFAULT_BUYER_LIVE_VOICE,
  isBuyerLiveVoice,
  type BuyerLiveVoice,
} from "@/lib/gemini-live/buyer-config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function failure(code: string, status: number) {
  return NextResponse.json(
    { error: code },
    { status, headers: { "Cache-Control": "no-store, private", Pragma: "no-cache" } },
  );
}

function providerStatus(error: unknown): number | undefined {
  if (!error || typeof error !== "object") return undefined;
  const candidate = error as { status?: unknown; code?: unknown };
  if (typeof candidate.status === "number") return candidate.status;
  return typeof candidate.code === "number" ? candidate.code : undefined;
}

function safeProviderMessage(error: unknown): string | null {
  if (!(error instanceof Error)) return null;
  return error.message
    .replace(/AIza[\w-]+/g, "[redacted]")
    .replace(/(api[_-]?key|authorization|cookie)\s*[:=]\s*\S+/gi, "$1=[redacted]")
    .slice(0, 240);
}

function diagnostic(stage: string, code: string, details: Record<string, unknown> = {}) {
  if (process.env.NODE_ENV !== "production") {
    console.warn(`[buyer-voice] ${JSON.stringify({ stage, code, model: BUYER_GEMINI_LIVE_MODEL, ...details })}`);
  }
}

function loadDevelopmentWorkspaceEnvironment() {
  if (process.env.NODE_ENV === "production" || process.env.GEMINI_API_KEY?.trim()) return;
  const workspaceEnvironment = resolve(process.cwd(), "..", "..", ".env");
  if (existsSync(workspaceEnvironment)) loadEnvFile(workspaceEnvironment);
}

export async function POST(request: Request) {
  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") {
    return failure("CROSS_SITE_REQUEST_REJECTED", 403);
  }

  const cookie = request.headers.get("cookie");
  if (!cookie) return failure("BUYER_AUTHENTICATION_REQUIRED", 401);
  const submittedCsrf = request.headers.get("X-CSRF-TOKEN");
  if (!submittedCsrf) return failure("CSRF_VALIDATION_FAILED", 403);
  const backendOrigin = (process.env.AMANA_BACKEND_ORIGIN ?? "http://localhost:8080").replace(/\/$/, "");

  let voiceName: BuyerLiveVoice = DEFAULT_BUYER_LIVE_VOICE;
  try {
    const actorResponse = await fetch(`${backendOrigin}/api/auth/buyer-session-validation`, {
      method: "POST",
      headers: {
        Accept: "application/json",
        Cookie: cookie,
        "X-CSRF-TOKEN": submittedCsrf,
      },
      cache: "no-store",
    });
    if (actorResponse.status === 403) return failure("CSRF_OR_BUYER_AUTHORITY_INVALID", 403);
    if (!actorResponse.ok) return failure("BUYER_AUTHENTICATION_REQUIRED", 401);
    const actor = (await actorResponse.json()) as { role?: unknown };
    if (actor.role !== "BUYER") return failure("BUYER_AUTHORITY_REQUIRED", 403);
    const preferenceResponse = await fetch(`${backendOrigin}/api/buyer/settings/voice`, {
      method: "GET",
      headers: { Accept: "application/json", Cookie: cookie },
      cache: "no-store",
    });
    if (!preferenceResponse.ok) return failure("BUYER_VOICE_PREFERENCE_UNAVAILABLE", 503);
    const preference = (await preferenceResponse.json()) as { voiceName?: unknown };
    if (!isBuyerLiveVoice(preference.voiceName)) return failure("BUYER_VOICE_PREFERENCE_INVALID", 503);
    voiceName = preference.voiceName;
  } catch {
    diagnostic("session-validation", "BUYER_SESSION_VALIDATION_UNAVAILABLE");
    return failure("BUYER_SESSION_VALIDATION_UNAVAILABLE", 503);
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return failure("INVALID_LIVE_SESSION_OPTIONS", 400);
  }
  const preferredLanguage =
    body && typeof body === "object" && typeof (body as { preferredLanguage?: unknown }).preferredLanguage === "string"
      ? (body as { preferredLanguage: string }).preferredLanguage.trim().slice(0, 80)
      : null;

  loadDevelopmentWorkspaceEnvironment();
  const configuredLiveModel = process.env.GEMINI_LIVE_MODEL?.trim() || BUYER_GEMINI_LIVE_MODEL;
  if (configuredLiveModel !== BUYER_GEMINI_LIVE_MODEL) {
    diagnostic("configuration", "GEMINI_LIVE_MODEL_CONSTRAINT_MISMATCH", { configuredLiveModel });
    return failure("GEMINI_LIVE_MODEL_CONSTRAINT_MISMATCH", 503);
  }
  const apiKey = process.env.GEMINI_API_KEY?.trim();
  if (!apiKey) {
    diagnostic("configuration", "GEMINI_API_KEY_MISSING");
    return failure("GEMINI_API_KEY_MISSING", 503);
  }

  try {
    const client = new GoogleGenAI({ apiKey, apiVersion: "v1beta" });
    const token = await client.authTokens.create({
      config: {
        uses: 1,
        liveConnectConstraints: {
          model: BUYER_GEMINI_LIVE_MODEL,
          config: buildBuyerGeminiLiveConfig(preferredLanguage, voiceName),
        },
      },
    });
    if (!token.name) return failure("INVALID_EPHEMERAL_TOKEN_RESPONSE", 502);
    return NextResponse.json(
      { token: token.name, model: BUYER_GEMINI_LIVE_MODEL, preferredLanguage, voiceName },
      { headers: { "Cache-Control": "no-store, private", Pragma: "no-cache" } },
    );
  } catch (error) {
    const status = providerStatus(error);
    diagnostic("ephemeral-token", "GEMINI_EPHEMERAL_TOKEN_FAILED", {
      exception: error instanceof Error ? error.constructor.name : "UnknownError",
      status: status ?? null,
      message: safeProviderMessage(error),
    });
    if (status === 429) return failure("GEMINI_QUOTA_OR_RATE_LIMIT", 429);
    if (status === 401 || status === 403) return failure("GEMINI_AUTHENTICATION_FAILED", 502);
    return failure("GEMINI_EPHEMERAL_TOKEN_FAILED", 502);
  }
}
