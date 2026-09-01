import "server-only";

import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { GoogleGenAI } from "@google/genai";
import { NextResponse } from "next/server";
import { GEMINI_LIVE_CONFIG, GEMINI_LIVE_MODEL } from "@/lib/gemini-live/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function loadWorkspaceEnvironment(): void {
  if (process.env.GEMINI_API_KEY?.trim()) {
    return;
  }
  const workspaceEnvironment = resolve(process.cwd(), "..", "..", ".env");
  if (existsSync(workspaceEnvironment)) {
    process.loadEnvFile(workspaceEnvironment);
  }
}

function failure(code: string, status: number): NextResponse {
  return NextResponse.json(
    { error: code },
    { status, headers: { "Cache-Control": "no-store" } },
  );
}

function providerStatus(error: unknown): number | undefined {
  if (!error || typeof error !== "object") {
    return undefined;
  }
  const candidate = error as { status?: unknown; code?: unknown };
  if (typeof candidate.status === "number") {
    return candidate.status;
  }
  if (typeof candidate.code === "number") {
    return candidate.code;
  }
  return undefined;
}

export async function POST(request: Request): Promise<NextResponse> {
  if (process.env.NODE_ENV === "production") {
    return failure("GEMINI_LIVE_POC_DISABLED", 404);
  }

  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") {
    return failure("CROSS_SITE_REQUEST_REJECTED", 403);
  }

  loadWorkspaceEnvironment();
  const apiKey = process.env.GEMINI_API_KEY?.trim();
  if (!apiKey) {
    return failure("GEMINI_API_KEY_MISSING", 503);
  }

  try {
    const client = new GoogleGenAI({ apiKey, apiVersion: "v1beta" });
    const token = await client.authTokens.create({
      config: {
        uses: 1,
        liveConnectConstraints: {
          model: GEMINI_LIVE_MODEL,
          config: GEMINI_LIVE_CONFIG,
        },
      },
    });

    if (!token.name) {
      return failure("INVALID_EPHEMERAL_TOKEN_RESPONSE", 502);
    }

    return NextResponse.json(
      { token: token.name, model: GEMINI_LIVE_MODEL },
      { headers: { "Cache-Control": "no-store, private", Pragma: "no-cache" } },
    );
  } catch (error) {
    const status = providerStatus(error);
    if (status === 429) {
      return failure("GEMINI_QUOTA_OR_RATE_LIMIT", 429);
    }
    if (status === 401 || status === 403) {
      return failure("GEMINI_AUTHENTICATION_FAILED", 502);
    }
    return failure("GEMINI_EPHEMERAL_TOKEN_FAILED", 502);
  }
}
