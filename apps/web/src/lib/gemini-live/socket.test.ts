import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { GeminiLiveSocket, sanitizeLiveDiagnosticText } from "./socket.ts";

test("the shared Live transport reports open, setup send, and setup completion", async () => {
  const original = globalThis.WebSocket;
  const diagnostics: string[] = [];

  class FakeWebSocket {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    readyState = FakeWebSocket.CONNECTING;
    onopen: (() => void) | null = null;
    onmessage: ((event: { data: string }) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: ((event: { code: number; reason: string }) => void) | null = null;
    readonly url: string;

    constructor(url: string) {
      this.url = url;
      queueMicrotask(() => {
        this.readyState = FakeWebSocket.OPEN;
        this.onopen?.();
      });
    }

    send(value: string) {
      assert.deepEqual(JSON.parse(value), { setup: { model: "models/test" } });
      queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ setupComplete: {} }) }));
    }

    close() {
      this.readyState = 3;
      this.onclose?.({ code: 1000, reason: "test complete" });
    }
  }

  globalThis.WebSocket = FakeWebSocket as unknown as typeof WebSocket;
  try {
    const socket = await GeminiLiveSocket.connect(
      "auth_tokens/secret-value",
      { model: "models/test" },
      {
        onmessage: () => undefined,
        onerror: () => undefined,
        onclose: () => undefined,
        ondiagnostic: (diagnostic) => diagnostics.push(diagnostic.stage),
      },
    );
    assert.deepEqual(diagnostics, ["websocket-open", "setup-sent", "setup-complete"]);
    socket.close("test complete");
  } finally {
    globalThis.WebSocket = original;
  }
});

test("Live diagnostics redact credentials", () => {
  const value = sanitizeLiveDiagnosticText(
    "authorization=Token auth_tokens/secret-value api_key=AIzaExampleSecret",
  );
  assert.equal(value?.includes("secret-value"), false);
  assert.equal(value?.includes("AIzaExampleSecret"), false);
  assert.match(value ?? "", /redacted/);
});
