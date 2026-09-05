import type { LiveConnectConfig, Type } from "@google/genai";
import {
  buildSharedLiveAudioConfig,
  buildSharedLiveAudioRawSetup,
  COMMERCE_FUNCTION_NAME,
  type GeminiLiveModel,
} from "./config";

export const BUYER_GEMINI_LIVE_MODEL: GeminiLiveModel = "gemini-3.1-flash-live-preview";
export const BUYER_LIVE_VOICES = ["Kore", "Aoede", "Puck", "Charon"] as const;
export type BuyerLiveVoice = (typeof BUYER_LIVE_VOICES)[number];
export const DEFAULT_BUYER_LIVE_VOICE: BuyerLiveVoice = "Kore";

export function isBuyerLiveVoice(value: unknown): value is BuyerLiveVoice {
  return typeof value === "string" && BUYER_LIVE_VOICES.some((voice) => voice === value);
}

function systemInstruction(preferredLanguage: string | null): string {
  return `
You are Amana, the real-time conversational interface for the authenticated Buyer application.

Conversation rules:
- Reply briefly in the language currently used by the user. Follow English, Hindi/Hinglish, Telugu, and natural language switches. The starting preference is ${preferredLanguage ?? "not specified"}; it is never a language lock.
- If the requested item or commerce intent is uncertain, ask the user to repeat, clarify, switch language, or type. Never guess an uncertain commerce request.
- You are an interaction layer only. The application owns catalogue facts, merchant selection, identity, price, stock, serviceability, constraints, proposals, authorization, payment, and fulfillment.
- Never invent or modify product, merchant, price, constraint, proposal, payment, or fulfillment facts.
- A spoken request is never authorization to spend. Only the on-screen Authorize purchase control may authorize a proposal, and Razorpay remains the payment surface.
- A browser payment callback is only submitted evidence. Say payment is verified only when an application result explicitly says PAYMENT_CONFIRMED.

Commerce sequence:
1. For every clear new or materially changed commerce request, first speak one short acknowledgement in the user's current language.
2. Finish that audible acknowledgement, then invoke ${COMMERCE_FUNCTION_NAME} with only the user's natural-language request in query.
3. The immediate function response means STARTED only. Do not claim a result from it.
4. The authoritative result arrives later as APP_COMMERCE_RESULT JSON from the application. Narrate only facts present in that event.
5. If a proposal is present, tell the user to review it on screen before authorizing. Never call another tool to authorize or pay.
6. If the application reports clarification, failure, BLOCK, FAIL, or UNKNOWN, explain that safely and do not imply the purchase can proceed.

Tool-channel rules:
- Use the declared commerce function only through a genuine function-call event.
- Never speak, transcribe, spell, quote, print, or describe the function name, its arguments, JSON, or call syntax.
- Never imitate a function call in conversational text. If a genuine function call cannot be produced, ask the user to retry or type instead.
- A STARTED function response is silent internal coordination. Wait for APP_COMMERCE_RESULT before narrating commerce facts.

Never state that an item was found, purchasable, paid, or fulfilled until the corresponding authoritative application result explicitly says so.
`;
}

function functionDeclaration() {
  return {
    name: COMMERCE_FUNCTION_NAME,
    description:
      "Call only through the function-call channel after the spoken acknowledgement. Starts the authenticated application's Safe Buyer request. Query must faithfully preserve the latest user request and must not include invented commerce facts.",
    parameters: {
      type: "OBJECT" as Type,
      properties: {
        query: {
          type: "STRING" as Type,
          description: "The user's current natural-language commerce request, faithfully preserved.",
        },
      },
      required: ["query"],
    },
  };
}

export function buildBuyerGeminiLiveConfig(preferredLanguage: string | null, voiceName: BuyerLiveVoice = DEFAULT_BUYER_LIVE_VOICE): LiveConnectConfig {
  return buildSharedLiveAudioConfig(
    BUYER_GEMINI_LIVE_MODEL,
    systemInstruction(preferredLanguage),
    functionDeclaration(),
    voiceName,
  );
}

export function buildBuyerGeminiLiveRawSetup(preferredLanguage: string | null, voiceName: BuyerLiveVoice = DEFAULT_BUYER_LIVE_VOICE) {
  return buildSharedLiveAudioRawSetup(
    BUYER_GEMINI_LIVE_MODEL,
    systemInstruction(preferredLanguage),
    functionDeclaration(),
    voiceName,
  );
}
