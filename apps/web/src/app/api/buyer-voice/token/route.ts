import "server-only";

import { timingSafeEqual } from "node:crypto";
import { GoogleGenAI } from "@google/genai";
import { NextResponse } from "next/server";
import {
  BUYER_GEMINI_LIVE_MODEL,
  buildBuyerGeminiLiveConfig,
} from "@/lib/gemini-live/buyer-config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type CsrfResponse = { headerName?: unknown; token?: unknown };

function failure(code: string, status: number) {
  return NextResponse.json(
    { error: code },
    { status, headers: { "Cache-Control": "no-store, private", Pragma: "no-cache" } },
  );
}

function equalSecret(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left);
  const rightBytes = Buffer.from(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function providerStatus(error: unknown): number | undefined {
  if (!error || typeof error !== "object") return undefined;
  const candidate = error as { status?: unknown; code?: unknown };
  if (typeof candidate.status === "number") return candidate.status;
  return typeof candidate.code === "number" ? candidate.code : undefined;
}

export async function POST(request: Request) {
  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") {
    return failure("CROSS_SITE_REQUEST_REJECTED", 403);
  }

  const cookie = request.headers.get("cookie");
  if (!cookie) return failure("BUYER_AUTHENTICATION_REQUIRED", 401);
  const backendOrigin = (process.env.AMANA_BACKEND_ORIGIN ?? "http://localhost:8080").replace(/\/$/, "");

  try {
    const [csrfResponse, actorResponse] = await Promise.all([
      fetch(`${backendOrigin}/api/auth/csrf`, {
        headers: { Accept: "application/json", Cookie: cookie },
        cache: "no-store",
      }),
      fetch(`${backendOrigin}/api/auth/me`, {
        headers: { Accept: "application/json", Cookie: cookie },
        cache: "no-store",
      }),
    ]);
    if (!actorResponse.ok) return failure("BUYER_AUTHENTICATION_REQUIRED", 401);
    const actor = (await actorResponse.json()) as { role?: unknown };
    if (actor.role !== "BUYER") return failure("BUYER_AUTHORITY_REQUIRED", 403);
    if (!csrfResponse.ok) return failure("CSRF_VALIDATION_FAILED", 403);
    const csrf = (await csrfResponse.json()) as CsrfResponse;
    if (typeof csrf.headerName !== "string" || typeof csrf.token !== "string") {
      return failure("CSRF_VALIDATION_FAILED", 403);
    }
    const submittedCsrf = request.headers.get(csrf.headerName);
    if (!submittedCsrf || !equalSecret(submittedCsrf, csrf.token)) {
      return failure("CSRF_VALIDATION_FAILED", 403);
    }
  } catch {
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

  const apiKey = process.env.GEMINI_API_KEY?.trim();
  if (!apiKey) return failure("GEMINI_API_KEY_MISSING", 503);

  try {
    const client = new GoogleGenAI({ apiKey, apiVersion: "v1beta" });
    const token = await client.authTokens.create({
      config: {
        uses: 1,
        liveConnectConstraints: {
          model: BUYER_GEMINI_LIVE_MODEL,
          config: buildBuyerGeminiLiveConfig(preferredLanguage),
        },
      },
    });
    if (!token.name) return failure("INVALID_EPHEMERAL_TOKEN_RESPONSE", 502);
    return NextResponse.json(
      { token: token.name, model: BUYER_GEMINI_LIVE_MODEL, preferredLanguage },
      { headers: { "Cache-Control": "no-store, private", Pragma: "no-cache" } },
    );
  } catch (error) {
    const status = providerStatus(error);
    if (status === 429) return failure("GEMINI_QUOTA_OR_RATE_LIMIT", 429);
    if (status === 401 || status === 403) return failure("GEMINI_AUTHENTICATION_FAILED", 502);
    return failure("GEMINI_EPHEMERAL_TOKEN_FAILED", 502);
  }
}
