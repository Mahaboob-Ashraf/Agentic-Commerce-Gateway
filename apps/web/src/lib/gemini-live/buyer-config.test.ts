import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
const config = readFileSync(new URL("./buyer-config.ts", import.meta.url), "utf8");
const sharedConfig = readFileSync(new URL("./config.ts", import.meta.url), "utf8");

test("selected persisted voice is applied to both constrained token and raw Live setup", () => {
  assert.match(config, /buildSharedLiveAudioConfig\([\s\S]*voiceName/);
  assert.match(config, /buildSharedLiveAudioRawSetup\([\s\S]*voiceName/);
  assert.match(sharedConfig, /speechConfig:\s*\{\s*voiceConfig:\s*\{\s*prebuiltVoiceConfig:\s*\{\s*voiceName/);
});

test("Kore is the stable default and commerce is declared as a real function tool", () => {
  assert.match(config, /DEFAULT_BUYER_LIVE_VOICE[^=]*=\s*"Kore"/);
  assert.match(config, /name:\s*COMMERCE_FUNCTION_NAME/);
  assert.match(config, /genuine function-call event/);
  assert.match(config, /Never imitate a function call/);
});

test("voice token route reads the server preference rather than browser storage", () => {
  const route = readFileSync(new URL("../../app/api/buyer-voice/token/route.ts", import.meta.url), "utf8");
  assert.match(route, /\/api\/buyer\/settings\/voice/);
  assert.match(route, /buildBuyerGeminiLiveConfig\(preferredLanguage, voiceName\)/);
});
