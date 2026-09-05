const LIVE_ENDPOINT =
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained";
const SETUP_TIMEOUT_MS = 12_000;

export interface LiveFunctionCall {
  args?: Record<string, unknown>;
  id?: string;
  name?: string;
}

export interface LiveTranscription {
  text?: string;
  finished?: boolean;
  languageCode?: string;
}

export interface LiveServerMessage {
  error?: {
    code?: number | string;
    message?: string;
    status?: string;
  };
  setupComplete?: Record<string, unknown>;
  serverContent?: {
    modelTurn?: {
      parts?: Array<{
        inlineData?: { data?: string; mimeType?: string };
      }>;
    };
    turnComplete?: boolean;
    generationComplete?: boolean;
    interrupted?: boolean;
    inputTranscription?: LiveTranscription;
    interimInputTranscription?: LiveTranscription;
    outputTranscription?: LiveTranscription;
  };
  toolCall?: { functionCalls?: LiveFunctionCall[] };
  toolCallCancellation?: { ids?: string[] };
  voiceActivity?: {
    voiceActivityType?: "ACTIVITY_START" | "ACTIVITY_END" | "TYPE_UNSPECIFIED";
  };
  goAway?: { timeLeft?: string };
}

interface RealtimeInput {
  audio?: { data: string; mimeType: string };
  audioStreamEnd?: boolean;
}

export interface FunctionResponse {
  id: string;
  name: string;
  response: Record<string, unknown>;
  scheduling?: "SILENT" | "WHEN_IDLE" | "INTERRUPT";
}

interface ClientContent {
  turns: Array<{ role: "user"; parts: Array<{ text: string }> }>;
  turnComplete: boolean;
}

interface LiveSocketCallbacks {
  onmessage: (message: LiveServerMessage, socket: GeminiLiveSocket) => void;
  onerror: () => void;
  onclose: (event: CloseEvent) => void;
  ondiagnostic?: (diagnostic: LiveTransportDiagnostic) => void;
}

export type LiveTransportDiagnostic = {
  stage: "websocket-open" | "setup-sent" | "setup-complete" | "provider-error" | "websocket-error" | "websocket-close" | "setup-timeout";
  closeCode?: number;
  closeReason?: string;
  providerCode?: number | string;
  providerStatus?: string;
  providerMessage?: string;
};

export function sanitizeLiveDiagnosticText(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return value
    .replace(/AIza[\w-]+/g, "[redacted]")
    .replace(/auth_tokens\/[\w.-]+/g, "auth_tokens/[redacted]")
    .replace(/(access_token|api[_-]?key|authorization|cookie)\s*[:=]\s*\S+/gi, "$1=[redacted]")
    .slice(0, 320);
}

function parseServerMessage(value: string): LiveServerMessage {
  const parsed = JSON.parse(value) as LiveServerMessage & {
    setup_complete?: Record<string, unknown>;
    server_content?: LiveServerMessage["serverContent"];
    tool_call?: LiveServerMessage["toolCall"];
    tool_call_cancellation?: LiveServerMessage["toolCallCancellation"];
    go_away?: LiveServerMessage["goAway"];
  };
  return {
    ...parsed,
    setupComplete: parsed.setupComplete ?? parsed.setup_complete,
    serverContent: parsed.serverContent ?? parsed.server_content,
    toolCall: parsed.toolCall ?? parsed.tool_call,
    toolCallCancellation: parsed.toolCallCancellation ?? parsed.tool_call_cancellation,
    goAway: parsed.goAway ?? parsed.go_away,
  };
}

async function messageText(data: unknown): Promise<string> {
  if (typeof data === "string") {
    return data;
  }
  if (data instanceof Blob) {
    return data.text();
  }
  if (data instanceof ArrayBuffer) {
    return new TextDecoder().decode(data);
  }
  throw new Error("UNSUPPORTED_LIVE_MESSAGE_TYPE");
}

export class GeminiLiveSocket {
  private established = false;
  private readonly connection: WebSocket;

  private constructor(connection: WebSocket) {
    this.connection = connection;
  }

  static connect(
    token: string,
    setup: Record<string, unknown>,
    callbacks: LiveSocketCallbacks,
  ): Promise<GeminiLiveSocket> {
    return new Promise((resolveConnection, rejectConnection) => {
      const endpoint = `${LIVE_ENDPOINT}?access_token=${encodeURIComponent(token)}`;
      const webSocket = new WebSocket(endpoint);
      const liveSocket = new GeminiLiveSocket(webSocket);
      let messageChain = Promise.resolve();
      const timeout = setTimeout(() => {
        if (!liveSocket.established) {
          callbacks.ondiagnostic?.({ stage: "setup-timeout" });
          webSocket.close();
          rejectConnection(new Error("LIVE_SETUP_TIMEOUT"));
        }
      }, SETUP_TIMEOUT_MS);

      webSocket.onopen = () => {
        callbacks.ondiagnostic?.({ stage: "websocket-open" });
        webSocket.send(JSON.stringify({ setup }));
        callbacks.ondiagnostic?.({ stage: "setup-sent" });
      };
      webSocket.onmessage = (event) => {
        messageChain = messageChain
          .then(async () => {
            const message = parseServerMessage(await messageText(event.data));
            if (message.error) {
              callbacks.ondiagnostic?.({
                stage: "provider-error",
                providerCode: message.error.code,
                providerStatus: sanitizeLiveDiagnosticText(message.error.status),
                providerMessage: sanitizeLiveDiagnosticText(message.error.message),
              });
            }
            if (message.setupComplete && !liveSocket.established) {
              liveSocket.established = true;
              clearTimeout(timeout);
              callbacks.ondiagnostic?.({ stage: "setup-complete" });
              resolveConnection(liveSocket);
            }
            callbacks.onmessage(message, liveSocket);
          })
          .catch(() => callbacks.onerror());
      };
      webSocket.onerror = () => {
        callbacks.ondiagnostic?.({ stage: "websocket-error" });
        if (!liveSocket.established) {
          clearTimeout(timeout);
          rejectConnection(new Error("LIVE_SOCKET_ERROR"));
        } else {
          callbacks.onerror();
        }
      };
      webSocket.onclose = (event) => {
        clearTimeout(timeout);
        callbacks.ondiagnostic?.({
          stage: "websocket-close",
          closeCode: event.code,
          closeReason: sanitizeLiveDiagnosticText(event.reason),
        });
        if (!liveSocket.established) {
          rejectConnection(new Error("LIVE_SOCKET_CLOSED_DURING_SETUP"));
        }
        callbacks.onclose(event);
      };
    });
  }

  sendRealtimeInput(input: RealtimeInput): void {
    this.send({ realtimeInput: input });
  }

  sendToolResponse(functionResponses: FunctionResponse[]): void {
    this.send({ toolResponse: { functionResponses } });
  }

  sendClientContent(content: ClientContent): void {
    this.send({ clientContent: content });
  }

  close(reason = "developer ended session"): void {
    if (
      this.connection.readyState === WebSocket.OPEN ||
      this.connection.readyState === WebSocket.CONNECTING
    ) {
      this.connection.close(1000, reason.slice(0, 123));
    }
  }

  private send(message: Record<string, unknown>): void {
    if (this.connection.readyState !== WebSocket.OPEN) {
      throw new Error("LIVE_SOCKET_NOT_OPEN");
    }
    this.connection.send(JSON.stringify(message));
  }
}
