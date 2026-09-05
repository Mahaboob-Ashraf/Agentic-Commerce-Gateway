import type {
  Behavior,
  EndSensitivity,
  LiveConnectConfig,
  Modality,
  StartSensitivity,
  Type,
} from "@google/genai";

export const GEMINI_LIVE_MODELS = [
  "gemini-2.5-flash-native-audio-preview-12-2025",
  "gemini-3.1-flash-live-preview",
] as const;

export type GeminiLiveModel = (typeof GEMINI_LIVE_MODELS)[number];
export type GeminiLiveAsyncMode = "APP_MANAGED" | "GEMINI_NON_BLOCKING";

export const DEFAULT_GEMINI_LIVE_MODEL: GeminiLiveModel = GEMINI_LIVE_MODELS[0];
export const DEFAULT_ASYNC_MODE: GeminiLiveAsyncMode = "APP_MANAGED";
export const DEFAULT_PROACTIVE_AUDIO = false;
export const COMMERCE_FUNCTION_NAME = "start_commerce_request";

const LIVE_AUDIO_ACTIVITY_CONFIG = {
  automaticActivityDetection: {
    disabled: false,
    startOfSpeechSensitivity: "START_SENSITIVITY_LOW" as StartSensitivity,
    endOfSpeechSensitivity: "END_SENSITIVITY_LOW" as EndSensitivity,
    prefixPaddingMs: 100,
    silenceDurationMs: 700,
  },
} as const;

export interface GeminiLiveSessionOptions {
  model: GeminiLiveModel;
  asyncMode: GeminiLiveAsyncMode;
  proactiveAudio: boolean;
}

export function isGeminiLiveModel(value: unknown): value is GeminiLiveModel {
  return typeof value === "string" && GEMINI_LIVE_MODELS.some((model) => model === value);
}

export function isGeminiLiveAsyncMode(value: unknown): value is GeminiLiveAsyncMode {
  return value === "APP_MANAGED" || value === "GEMINI_NON_BLOCKING";
}

export function normalizeSessionOptions(
  options: GeminiLiveSessionOptions,
): GeminiLiveSessionOptions {
  return {
    model: options.model,
    asyncMode:
      options.model === "gemini-3.1-flash-live-preview"
        ? "APP_MANAGED"
        : options.asyncMode,
    proactiveAudio: false,
  };
}

function systemInstruction(asyncMode: GeminiLiveAsyncMode): string {
  const completionSource =
    asyncMode === "APP_MANAGED"
      ? "The function response only confirms that the application-owned job STARTED. Do not claim results from it. A completed result arrives later as a strict APP_COMMERCE_RESULT JSON event inserted by the application. Announce only that latest completed event."
      : "The function is NON_BLOCKING. Its later completed function response contains the only result you may announce. Continue listening and conversing while it is pending.";

  return `
You are the developer-only real-time conversational front door for Agentic Commerce Gateway.

Conversation rules:
- Reply briefly in the language currently used by the user. Follow Hindi/Hinglish, Telugu, and English switches naturally.
- You are a conversational interface only. The application owns commerce work; you do not execute, cancel, complete, or validate a commerce job.
- Never invent or supplement product, merchant, price, rating, review, allergen, ingredient, protein, brand, description, availability, restaurant, order, payment, or refund facts from your own knowledge.
- The only possible synthetic products are facts explicitly supplied in a completed commerce result. Never infer any missing field.
- If a requested field is absent, say that the commerce result did not include that information. If asked for ratings, say rating information was not included.
- If work fails, times out, or is superseded, never describe it as successful.
- Treat all returned products as synthetic developer-POC data.

Commerce request sequence:
1. For every clear new or materially changed commerce request, FIRST speak one short acknowledgement in the user's current language that faithfully summarizes the request.
2. Finish speaking that acknowledgement.
3. Then invoke ${COMMERCE_FUNCTION_NAME} with the current request faithfully preserved in query.
4. A material correction should produce a new function call. The application applies latest-request semantics.
5. ${completionSource}

Never state that products were found until a completed result containing those exact products has arrived.
`;
}

function functionDeclaration(asyncMode: GeminiLiveAsyncMode) {
  return {
    name: COMMERCE_FUNCTION_NAME,
    ...(asyncMode === "GEMINI_NON_BLOCKING"
      ? { behavior: "NON_BLOCKING" as Behavior }
      : {}),
    description:
      "Starts application-owned synthetic commerce work after the spoken acknowledgement. Query must faithfully preserve the user's current request.",
    parameters: {
      type: "OBJECT" as Type,
      properties: {
        query: {
          type: "STRING" as Type,
          description: "The user's current commerce request, faithfully preserved.",
        },
      },
      required: ["query"],
    },
  };
}

export function buildSharedLiveAudioConfig(
  model: GeminiLiveModel,
  instruction: string,
  declaration: ReturnType<typeof functionDeclaration>,
  voiceName?: string,
): LiveConnectConfig {
  return {
    responseModalities: ["AUDIO" as Modality],
    systemInstruction: instruction,
    inputAudioTranscription: {},
    outputAudioTranscription: {},
    realtimeInputConfig: LIVE_AUDIO_ACTIVITY_CONFIG,
    ...(voiceName ? { speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } } } : {}),
    ...(model === "gemini-2.5-flash-native-audio-preview-12-2025"
      ? { thinkingConfig: { thinkingBudget: 0 } }
      : {}),
    tools: [{ functionDeclarations: [declaration] }],
  };
}

export function buildSharedLiveAudioRawSetup(
  model: GeminiLiveModel,
  instruction: string,
  declaration: ReturnType<typeof functionDeclaration>,
  voiceName?: string,
) {
  return {
    model: `models/${model}`,
    generationConfig: {
      responseModalities: ["AUDIO"],
      ...(voiceName ? { speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } } } : {}),
      ...(model === "gemini-2.5-flash-native-audio-preview-12-2025"
        ? { thinkingConfig: { thinkingBudget: 0 } }
        : {}),
    },
    systemInstruction: { parts: [{ text: instruction }] },
    inputAudioTranscription: {},
    outputAudioTranscription: {},
    realtimeInputConfig: LIVE_AUDIO_ACTIVITY_CONFIG,
    tools: [{ functionDeclarations: [declaration] }],
  } as const;
}

export function buildGeminiLiveConfig(
  rawOptions: GeminiLiveSessionOptions,
): LiveConnectConfig {
  const options = normalizeSessionOptions(rawOptions);
  return buildSharedLiveAudioConfig(
    options.model,
    systemInstruction(options.asyncMode),
    functionDeclaration(options.asyncMode),
  );
}

export function buildGeminiLiveTokenConstraintConfig(
  options: GeminiLiveSessionOptions,
): LiveConnectConfig {
  // Kept separate because the constrained-token schema and Live setup schema evolve independently.
  return buildGeminiLiveConfig(options);
}

export function buildGeminiLiveRawSetup(rawOptions: GeminiLiveSessionOptions) {
  const options = normalizeSessionOptions(rawOptions);
  return buildSharedLiveAudioRawSetup(
    options.model,
    systemInstruction(options.asyncMode),
    functionDeclaration(options.asyncMode),
  );
}
