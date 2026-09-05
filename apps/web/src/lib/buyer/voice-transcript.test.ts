import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { VoiceTranscriptTurns, visibleAssistantTranscript } from "./voice-transcript.ts";

function transcript() {
  let next = 0;
  return new VoiceTranscriptTurns(() => `turn-${++next}`);
}

test("separate Live user activities create separate finalized transcript bubbles", () => {
  const turns = transcript();
  turns.beginUserTurn();
  turns.ingest("USER", { text: "Find Ora Link buds" });
  turns.finalize("USER");
  turns.beginUserTurn();
  turns.ingest("USER", { text: "No, Auralink. A-U-R-A-L-I-N-K.", finished: true });

  assert.deepEqual(turns.snapshot().map(({ role, text, final }) => ({ role, text, final })), [
    { role: "USER", text: "Find Ora Link buds", final: true },
    { role: "USER", text: "No, Auralink. A-U-R-A-L-I-N-K.", final: true },
  ]);
});

test("assistant transcription segments remain one canonical bubble until model turn completion", () => {
  const turns = transcript();
  turns.beginAssistantTurn();
  turns.ingest("AMANA", { text: "I found", finished: true });
  turns.ingest("AMANA", { text: "I found Auralink Buds.", finished: true });
  turns.finishModelTurn();

  assert.deepEqual(turns.snapshot().map(({ role, text, final }) => ({ role, text, final })), [
    { role: "AMANA", text: "I found Auralink Buds.", final: true },
  ]);
});

test("tool call syntax is internal and never becomes visible assistant content", () => {
  const turns = transcript();
  turns.ingest("AMANA", { text: "Let me look. start_commerce_request({query: 'buds'})" });
  turns.finishModelTurn();
  assert.equal(visibleAssistantTranscript("call:start_commerce_request(query='buds')"), "");
  assert.equal(turns.snapshot()[0]?.text, "Let me look.");
});

test("overlapping Live deltas do not duplicate assistant words", () => {
  const turns = transcript();
  turns.ingest("AMANA", { text: "I found Auralink" });
  turns.ingest("AMANA", { text: "Auralink Buds from Amazing" });
  turns.finishModelTurn();
  assert.equal(turns.snapshot()[0]?.text, "I found Auralink Buds from Amazing");
});

test("separate transcript chunks retain readable word boundaries without inventing punctuation", () => {
  const turns = transcript();
  turns.ingest("AMANA", { text: "I found" });
  turns.ingest("AMANA", { text: "Auralink Buds" });
  turns.ingest("AMANA", { text: "." });
  turns.finishModelTurn();
  assert.equal(turns.snapshot()[0]?.text, "I found Auralink Buds.");
});
