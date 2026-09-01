import { GEMINI_LIVE_RAW_SETUP } from "./config";

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
}

interface RealtimeInput {
  audio?: { data: string; mimeType: string };
  audioStreamEnd?: boolean;
}

interface FunctionResponse {
  id: string;
  name: string;
  response: Record<string, unknown>;
  scheduling: "WHEN_IDLE";
}

interface LiveSocketCallbacks {
  onmessage: (message: LiveServerMessage, socket: GeminiLiveSocket) => void;
  onerror: () => void;
  onclose: (event: CloseEvent) => void;
}

function parseServerMessage(value: string): LiveServerMessage {
  const parsed = JSON.parse(value) as LiveServerMessage & {
    setup_complete?: Record<string, unknown>;
    server_content?: LiveServerMessage["serverContent"];
    tool_call?: LiveServerMessage["toolCall"];
    tool_call_cancellation?: LiveServerMessage["toolCallCancellation"];
  };
  return {
    ...parsed,
    setupComplete: parsed.setupComplete ?? parsed.setup_complete,
    serverContent: parsed.serverContent ?? parsed.server_content,
    toolCall: parsed.toolCall ?? parsed.tool_call,
    toolCallCancellation: parsed.toolCallCancellation ?? parsed.tool_call_cancellation,
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

  private constructor(
    private readonly connection: WebSocket,
  ) {}

  static connect(token: string, callbacks: LiveSocketCallbacks): Promise<GeminiLiveSocket> {
    return new Promise((resolveConnection, rejectConnection) => {
      const endpoint = `${LIVE_ENDPOINT}?access_token=${encodeURIComponent(token)}`;
      const webSocket = new WebSocket(endpoint);
      const liveSocket = new GeminiLiveSocket(webSocket);
      let messageChain = Promise.resolve();
      const timeout = setTimeout(() => {
        if (!liveSocket.established) {
          webSocket.close();
          rejectConnection(new Error("LIVE_SETUP_TIMEOUT"));
        }
      }, SETUP_TIMEOUT_MS);

      webSocket.onopen = () => {
        webSocket.send(JSON.stringify({ setup: GEMINI_LIVE_RAW_SETUP }));
      };
      webSocket.onmessage = (event) => {
        messageChain = messageChain
          .then(async () => {
            const message = parseServerMessage(await messageText(event.data));
            if (message.setupComplete && !liveSocket.established) {
              liveSocket.established = true;
              clearTimeout(timeout);
              resolveConnection(liveSocket);
            }
            callbacks.onmessage(message, liveSocket);
          })
          .catch(() => callbacks.onerror());
      };
      webSocket.onerror = () => {
        if (!liveSocket.established) {
          clearTimeout(timeout);
          rejectConnection(new Error("LIVE_SOCKET_ERROR"));
        } else {
          callbacks.onerror();
        }
      };
      webSocket.onclose = (event) => {
        clearTimeout(timeout);
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

  close(): void {
    if (
      this.connection.readyState === WebSocket.OPEN ||
      this.connection.readyState === WebSocket.CONNECTING
    ) {
      this.connection.close(1000, "developer ended session");
    }
  }

  private send(message: Record<string, unknown>): void {
    if (this.connection.readyState !== WebSocket.OPEN) {
      throw new Error("LIVE_SOCKET_NOT_OPEN");
    }
    this.connection.send(JSON.stringify(message));
  }
}
