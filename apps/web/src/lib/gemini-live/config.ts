import type {
  Behavior,
  EndSensitivity,
  LiveConnectConfig,
  Modality,
  StartSensitivity,
  Type,
} from "@google/genai";

export const GEMINI_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025";
export const COMMERCE_FUNCTION_NAME = "start_commerce_request";

export const GEMINI_LIVE_SYSTEM_INSTRUCTION = `
You are the developer-only real-time conversational front door for Agentic Commerce Gateway.

Conversation rules:
- Reply in the language currently used by the user. Follow Hindi/Hinglish, Telugu, and English language switches naturally while preserving this session's conversational context.
- You are not commerce truth. Never invent or supplement product, merchant, price, rating, allergen, ingredient, availability, order, payment, or refund facts from your own knowledge.
- Commerce facts may come only from a completed start_commerce_request function response in this session. Repeat only fields actually returned by that function.
- If a requested commerce fact is absent, say that the commerce result did not include it. For example, if asked for ratings, say the commerce result did not include rating information.
- Never introduce product names beyond names returned by the function. In this POC, do not invent makhana, chicken, protein bars, restaurants, ratings, descriptions, ingredients, or alternate prices.
- If a function returns an error or is unavailable, state that the commerce check failed or is unavailable. Never describe it as successful.
- Treat all returned demo products as explicitly synthetic POC data.

Commerce request sequence:
1. For every clear new or materially changed commerce request, FIRST speak one short acknowledgement in the user's current language that faithfully summarizes the request.
2. Finish speaking that acknowledgement.
3. ONLY AFTER the acknowledgement, invoke start_commerce_request with the user's current commerce request faithfully preserved in query.
4. The function is asynchronous. Continue listening and conversing while it is pending. A material correction may produce a separate new function call; do not claim the earlier call was cancelled.
5. When a completed function response becomes available, report only its returned facts.

Required example:
User: "500 ke andar high-protein vegetarian snacks chahiye, peanuts bilkul nahi."
First say: "Got it — vegetarian, under ₹500, and absolutely no peanuts. I'm checking now."
Then invoke: start_commerce_request({query: "500 ke andar high-protein vegetarian snacks chahiye, peanuts bilkul nahi."})
`;

export const GEMINI_LIVE_CONFIG: LiveConnectConfig = {
  responseModalities: ["AUDIO" as Modality],
  systemInstruction: GEMINI_LIVE_SYSTEM_INSTRUCTION,
  inputAudioTranscription: {},
  outputAudioTranscription: {},
  realtimeInputConfig: {
    automaticActivityDetection: {
      disabled: false,
      startOfSpeechSensitivity: "START_SENSITIVITY_HIGH" as StartSensitivity,
      endOfSpeechSensitivity: "END_SENSITIVITY_HIGH" as EndSensitivity,
      prefixPaddingMs: 40,
      silenceDurationMs: 500,
    },
  },
  tools: [
    {
      functionDeclarations: [
        {
          name: COMMERCE_FUNCTION_NAME,
          behavior: "NON_BLOCKING" as Behavior,
          description:
            "Starts an asynchronous commerce lookup after the spoken acknowledgement has completed. The query must faithfully preserve the user's current commerce request.",
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
        },
      ],
    },
  ],
};

export const GEMINI_LIVE_RAW_SETUP = {
  model: `models/${GEMINI_LIVE_MODEL}`,
  generationConfig: {
    responseModalities: ["AUDIO"],
  },
  systemInstruction: {
    parts: [{ text: GEMINI_LIVE_SYSTEM_INSTRUCTION }],
  },
  inputAudioTranscription: {},
  outputAudioTranscription: {},
  realtimeInputConfig: {
    automaticActivityDetection: {
      disabled: false,
      startOfSpeechSensitivity: "START_SENSITIVITY_HIGH",
      endOfSpeechSensitivity: "END_SENSITIVITY_HIGH",
      prefixPaddingMs: 40,
      silenceDurationMs: 500,
    },
  },
  tools: [
    {
      functionDeclarations: [
        {
          name: COMMERCE_FUNCTION_NAME,
          behavior: "NON_BLOCKING",
          description:
            "Starts an asynchronous commerce lookup after the spoken acknowledgement has completed. The query must faithfully preserve the user's current commerce request.",
          parameters: {
            type: "OBJECT",
            properties: {
              query: {
                type: "STRING",
                description: "The user's current commerce request, faithfully preserved.",
              },
            },
            required: ["query"],
          },
        },
      ],
    },
  ],
} as const;
