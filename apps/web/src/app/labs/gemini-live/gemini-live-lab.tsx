"use client";

import { useEffect, useRef, useState } from "react";
import { ContinuousPcmRecorder, PcmAudioPlayer } from "@/lib/gemini-live/audio";
import {
  COMMERCE_FUNCTION_NAME,
  GEMINI_LIVE_MODEL,
} from "@/lib/gemini-live/config";
import {
  GeminiLiveSocket,
  type LiveFunctionCall,
  type LiveServerMessage,
} from "@/lib/gemini-live/socket";

type ConnectionState =
  | "DISCONNECTED"
  | "CONNECTING"
  | "LISTENING"
  | "SPEAKING"
  | "TOOL_PENDING"
  | "ERROR";

type TimelineEventName =
  | "SESSION_CONNECTED"
  | "SESSION_CLOSED"
  | "USER_SPEECH_STARTED"
  | "USER_SPEECH_ENDED"
  | "MODEL_AUDIO_STARTED"
  | "MODEL_AUDIO_ENDED"
  | "FUNCTION_CALL_RECEIVED"
  | "FAKE_COMMERCE_STARTED"
  | "FAKE_COMMERCE_COMPLETED"
  | "FUNCTION_RESPONSE_SENT"
  | "FUNCTION_FAILED"
  | "SESSION_INTERRUPTED"
  | "ERROR";

interface TimelineEvent {
  id: string;
  event: TimelineEventName;
  at: string;
  elapsedMs: number;
  detail?: string;
}

interface TranscriptEvent {
  id: string;
  role: "USER" | "GEMINI";
  text: string;
  languageCode?: string;
  final: boolean;
  at: string;
}

interface FakeCommerceResult {
  request_id: string;
  status: "COMPLETED";
  products: Array<{
    name: "Demo Protein Snack A" | "Demo Protein Snack B";
    price_paise: 19900 | 24900;
    vegetarian: true;
    peanut_constraint: "PASS";
  }>;
}

interface TokenResponse {
  token: string;
  model: string;
}

interface AcknowledgementWaiter {
  resolve: () => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

const FAKE_COMMERCE_DELAY_MS = 8_000;
const FAKE_COMMERCE_TIMEOUT_MS = 12_000;
const ACKNOWLEDGEMENT_TIMEOUT_MS = 15_000;

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}

async function fakeCommerceRequest(shouldFail: boolean): Promise<FakeCommerceResult> {
  await delay(FAKE_COMMERCE_DELAY_MS);
  if (shouldFail) {
    throw new Error("SIMULATED_FAKE_COMMERCE_FAILURE");
  }
  return {
    request_id: `fake-${crypto.randomUUID()}`,
    status: "COMPLETED",
    products: [
      {
        name: "Demo Protein Snack A",
        price_paise: 19900,
        vegetarian: true,
        peanut_constraint: "PASS",
      },
      {
        name: "Demo Protein Snack B",
        price_paise: 24900,
        vegetarian: true,
        peanut_constraint: "PASS",
      },
    ],
  };
}

function isValidCommerceResult(result: FakeCommerceResult): boolean {
  return (
    result.request_id.startsWith("fake-") &&
    result.status === "COMPLETED" &&
    result.products.length === 2 &&
    result.products[0]?.name === "Demo Protein Snack A" &&
    result.products[0]?.price_paise === 19900 &&
    result.products[0]?.vegetarian === true &&
    result.products[0]?.peanut_constraint === "PASS" &&
    result.products[1]?.name === "Demo Protein Snack B" &&
    result.products[1]?.price_paise === 24900 &&
    result.products[1]?.vegetarian === true &&
    result.products[1]?.peanut_constraint === "PASS"
  );
}

function classifyTokenFailure(code: string): string {
  switch (code) {
    case "GEMINI_API_KEY_MISSING":
      return "GEMINI_API_KEY is missing from the workspace .env file.";
    case "GEMINI_QUOTA_OR_RATE_LIMIT":
      return "Gemini rejected token creation because of quota or rate limits.";
    case "GEMINI_AUTHENTICATION_FAILED":
      return "Gemini rejected the configured API credential.";
    case "GEMINI_LIVE_POC_DISABLED":
      return "This developer-only route is disabled outside next dev.";
    default:
      return "Could not create a secure Gemini Live session token.";
  }
}

function connectionCloseMessage(reason: string): string {
  const normalized = reason.toLowerCase();
  if (normalized.includes("quota") || normalized.includes("rate")) {
    return "Gemini closed the session because of quota or rate limits.";
  }
  return "The Gemini Live session closed unexpectedly.";
}

export function GeminiLiveLab() {
  const [connectionState, setConnectionState] = useState<ConnectionState>("DISCONNECTED");
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [transcript, setTranscript] = useState<TranscriptEvent[]>([]);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [pendingToolCount, setPendingToolCount] = useState(0);
  const [failNextTool, setFailNextTool] = useState(false);

  const sessionRef = useRef<GeminiLiveSocket | null>(null);
  const recorderRef = useRef<ContinuousPcmRecorder | null>(null);
  const playerRef = useRef<PcmAudioPlayer | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const intentionalCloseRef = useRef(false);
  const connectedLoggedRef = useRef(false);
  const sessionStartedAtRef = useRef(0);
  const speechActiveRef = useRef(false);
  const latestSpeechTurnRef = useRef(0);
  const modelAudioTurnRef = useRef(0);
  const audioCompletedTurnRef = useRef(0);
  const generationCompletedTurnRef = useRef(0);
  const acknowledgementWaitersRef = useRef(new Map<number, AcknowledgementWaiter[]>());
  const pendingToolsRef = useRef(new Set<string>());
  const failNextToolRef = useRef(false);

  function addTimeline(event: TimelineEventName, detail?: string): void {
    const now = performance.now();
    setTimeline((current) => [
      ...current.slice(-199),
      {
        id: crypto.randomUUID(),
        event,
        at: new Date().toISOString(),
        elapsedMs: Math.max(0, Math.round(now - sessionStartedAtRef.current)),
        detail,
      },
    ]);
  }

  function addTranscript(
    role: TranscriptEvent["role"],
    text: string,
    final: boolean,
    languageCode?: string,
  ): void {
    const normalized = text.trim();
    if (!normalized) {
      return;
    }
    setTranscript((current) => [
      ...current.slice(-99),
      {
        id: crypto.randomUUID(),
        role,
        text: normalized,
        languageCode,
        final,
        at: new Date().toISOString(),
      },
    ]);
  }

  function refreshPendingState(): void {
    const count = pendingToolsRef.current.size;
    setPendingToolCount(count);
    if (count > 0) {
      setConnectionState("TOOL_PENDING");
    } else if (sessionRef.current) {
      setConnectionState("LISTENING");
    }
  }

  function rejectAcknowledgements(error: Error): void {
    for (const waiters of acknowledgementWaitersRef.current.values()) {
      for (const waiter of waiters) {
        clearTimeout(waiter.timer);
        waiter.reject(error);
      }
    }
    acknowledgementWaitersRef.current.clear();
  }

  function resolveAcknowledgements(): void {
    const completedTurn = Math.min(
      audioCompletedTurnRef.current,
      generationCompletedTurnRef.current,
    );
    for (const [turn, waiters] of acknowledgementWaitersRef.current) {
      if (turn <= completedTurn) {
        acknowledgementWaitersRef.current.delete(turn);
        for (const waiter of waiters) {
          clearTimeout(waiter.timer);
          waiter.resolve();
        }
      }
    }
  }

  function waitForAcknowledgement(turn: number): Promise<void> {
    const completedTurn = Math.min(
      audioCompletedTurnRef.current,
      generationCompletedTurnRef.current,
    );
    if (completedTurn >= turn) {
      return Promise.resolve();
    }
    return new Promise((resolveWaiter, rejectWaiter) => {
      const timer = setTimeout(() => {
        const waiters = acknowledgementWaitersRef.current.get(turn) ?? [];
        acknowledgementWaitersRef.current.set(
          turn,
          waiters.filter((waiter) => waiter.timer !== timer),
        );
        rejectWaiter(new Error("ACKNOWLEDGEMENT_AUDIO_NOT_COMPLETED"));
      }, ACKNOWLEDGEMENT_TIMEOUT_MS);
      const waiters = acknowledgementWaitersRef.current.get(turn) ?? [];
      waiters.push({ resolve: resolveWaiter, reject: rejectWaiter, timer });
      acknowledgementWaitersRef.current.set(turn, waiters);
    });
  }

  function recordSpeechActivity(active: boolean, source: "client-rms" | "server-vad"): void {
    if (speechActiveRef.current === active) {
      return;
    }
    speechActiveRef.current = active;
    if (active) {
      latestSpeechTurnRef.current += 1;
      addTimeline("USER_SPEECH_STARTED", source);
    } else {
      addTimeline("USER_SPEECH_ENDED", source);
    }
  }

  function sendFunctionFailure(
    session: GeminiLiveSocket,
    call: LiveFunctionCall,
    code: string,
  ): void {
    if (!call.id || !call.name || sessionRef.current !== session) {
      return;
    }
    session.sendToolResponse([
      {
        id: call.id,
        name: call.name,
        response: { error: { code, status: "FAILED" } },
        scheduling: "WHEN_IDLE",
      },
    ]);
    addTimeline("FUNCTION_RESPONSE_SENT", `${call.id} · FAILED · WHEN_IDLE`);
  }

  async function runCommerceFunction(
    call: LiveFunctionCall,
    session: GeminiLiveSocket,
  ): Promise<void> {
    const callId = call.id;
    const query = call.args?.query;
    if (
      call.name !== COMMERCE_FUNCTION_NAME ||
      !callId ||
      typeof query !== "string" ||
      query.trim().length === 0 ||
      query.length > 2_000
    ) {
      addTimeline("FUNCTION_FAILED", "INVALID_FUNCTION_REQUEST");
      setErrorMessage("Gemini supplied an invalid commerce function request.");
      sendFunctionFailure(session, call, "INVALID_FUNCTION_REQUEST");
      return;
    }

    pendingToolsRef.current.add(callId);
    refreshPendingState();
    const acknowledgementTurn = Math.max(1, latestSpeechTurnRef.current);

    try {
      await waitForAcknowledgement(acknowledgementTurn);
      if (sessionRef.current !== session) {
        return;
      }

      const requestStartedAt = performance.now();
      addTimeline("FAKE_COMMERCE_STARTED", `${callId} · query length ${query.length}`);
      const shouldFail = failNextToolRef.current;
      if (shouldFail) {
        failNextToolRef.current = false;
        setFailNextTool(false);
      }

      const result = await Promise.race([
        fakeCommerceRequest(shouldFail),
        delay(FAKE_COMMERCE_TIMEOUT_MS).then(() => {
          throw new Error("FAKE_COMMERCE_TIMEOUT");
        }),
      ]);
      if (!isValidCommerceResult(result)) {
        throw new Error("INVALID_FAKE_COMMERCE_RESPONSE");
      }
      if (sessionRef.current !== session) {
        return;
      }

      addTimeline(
        "FAKE_COMMERCE_COMPLETED",
        `${callId} · ${Math.round(performance.now() - requestStartedAt)} ms`,
      );
      session.sendToolResponse([
        {
          id: callId,
          name: COMMERCE_FUNCTION_NAME,
          response: { output: result },
          scheduling: "WHEN_IDLE",
        },
      ]);
      addTimeline("FUNCTION_RESPONSE_SENT", `${callId} · COMPLETED · WHEN_IDLE`);
    } catch (error) {
      const code = error instanceof Error ? error.message : "FAKE_COMMERCE_FAILED";
      addTimeline("FUNCTION_FAILED", `${callId} · ${code}`);
      setErrorMessage(
        code === "ACKNOWLEDGEMENT_AUDIO_NOT_COMPLETED"
          ? "Commerce did not start because the spoken acknowledgement did not complete."
          : "The synthetic commerce function failed; no success result was sent.",
      );
      sendFunctionFailure(session, call, code);
    } finally {
      pendingToolsRef.current.delete(callId);
      refreshPendingState();
    }
  }

  function handleLiveMessage(
    message: LiveServerMessage,
    session: GeminiLiveSocket | null,
  ): void {
    if (message.setupComplete && !connectedLoggedRef.current) {
      connectedLoggedRef.current = true;
      addTimeline("SESSION_CONNECTED", GEMINI_LIVE_MODEL);
    }

    const voiceActivity = message.voiceActivity?.voiceActivityType;
    if (voiceActivity === "ACTIVITY_START") {
      recordSpeechActivity(true, "server-vad");
    } else if (voiceActivity === "ACTIVITY_END") {
      recordSpeechActivity(false, "server-vad");
    }

    const content = message.serverContent;
    if (content?.interimInputTranscription?.text) {
      addTranscript(
        "USER",
        content.interimInputTranscription.text,
        false,
        content.interimInputTranscription.languageCode,
      );
    }
    if (content?.inputTranscription?.text) {
      addTranscript(
        "USER",
        content.inputTranscription.text,
        content.inputTranscription.finished ?? true,
        content.inputTranscription.languageCode,
      );
    }
    if (content?.outputTranscription?.text) {
      addTranscript(
        "GEMINI",
        content.outputTranscription.text,
        content.outputTranscription.finished ?? false,
        content.outputTranscription.languageCode,
      );
    }

    for (const part of content?.modelTurn?.parts ?? []) {
      if (part.inlineData?.data && part.inlineData.mimeType?.startsWith("audio/pcm")) {
        try {
          playerRef.current?.enqueue(part.inlineData.data);
        } catch {
          setErrorMessage("Gemini returned invalid audio data.");
          setConnectionState("ERROR");
          addTimeline("ERROR", "INVALID_MODEL_AUDIO");
        }
      }
    }

    if (content?.generationComplete || content?.turnComplete) {
      generationCompletedTurnRef.current = Math.max(
        generationCompletedTurnRef.current,
        modelAudioTurnRef.current,
        Math.max(1, latestSpeechTurnRef.current),
      );
      resolveAcknowledgements();
    }
    if (content?.interrupted) {
      playerRef.current?.interrupt();
      addTimeline("SESSION_INTERRUPTED");
      rejectAcknowledgements(new Error("ACKNOWLEDGEMENT_INTERRUPTED"));
      refreshPendingState();
    }

    if (message.toolCall?.functionCalls && session) {
      for (const call of message.toolCall.functionCalls) {
        addTimeline(
          "FUNCTION_CALL_RECEIVED",
          `${call.id ?? "missing-id"} · ${call.name ?? "missing-name"}`,
        );
        void runCommerceFunction(call, session);
      }
    }
  }

  function releaseMedia(): void {
    recorderRef.current?.stop();
    recorderRef.current = null;
    playerRef.current?.stop();
    playerRef.current = null;
    if (audioContextRef.current) {
      void audioContextRef.current.close();
      audioContextRef.current = null;
    }
  }

  function failConnection(message: string, detail: string): void {
    setErrorMessage(message);
    setConnectionState("ERROR");
    addTimeline("ERROR", detail);
    releaseMedia();
  }

  async function startConversation(): Promise<void> {
    if (connectionState !== "DISCONNECTED" && connectionState !== "ERROR") {
      return;
    }

    intentionalCloseRef.current = false;
    connectedLoggedRef.current = false;
    speechActiveRef.current = false;
    latestSpeechTurnRef.current = 0;
    modelAudioTurnRef.current = 0;
    audioCompletedTurnRef.current = 0;
    generationCompletedTurnRef.current = 0;
    pendingToolsRef.current.clear();
    rejectAcknowledgements(new Error("SESSION_RESTARTED"));
    sessionStartedAtRef.current = performance.now();
    setTimeline([]);
    setTranscript([]);
    setErrorMessage(null);
    setPendingToolCount(0);
    setConnectionState("CONNECTING");

    let stream: MediaStream | null = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      });

      const audioContext = new AudioContext({ latencyHint: "interactive" });
      await audioContext.resume();
      audioContextRef.current = audioContext;
      playerRef.current = new PcmAudioPlayer(
        audioContext,
        () => {
          modelAudioTurnRef.current = Math.max(1, latestSpeechTurnRef.current);
          setConnectionState("SPEAKING");
          addTimeline("MODEL_AUDIO_STARTED");
        },
        () => {
          audioCompletedTurnRef.current = Math.max(
            audioCompletedTurnRef.current,
            modelAudioTurnRef.current,
          );
          addTimeline("MODEL_AUDIO_ENDED");
          resolveAcknowledgements();
          refreshPendingState();
        },
      );

      const tokenResponse = await fetch("/api/labs/gemini-live/token", {
        method: "POST",
        headers: { Accept: "application/json" },
        cache: "no-store",
      });
      const tokenPayload = (await tokenResponse.json()) as Partial<TokenResponse> & {
        error?: string;
      };
      if (!tokenResponse.ok || !tokenPayload.token) {
        throw new Error(`TOKEN:${tokenPayload.error ?? "UNKNOWN"}`);
      }

      const liveSession = await GeminiLiveSocket.connect(tokenPayload.token, {
        onmessage: (message, socket) => handleLiveMessage(message, socket),
        onerror: () => {
          if (!intentionalCloseRef.current) {
            failConnection("Gemini Live reported a connection error.", "LIVE_SOCKET_ERROR");
          }
        },
        onclose: (event) => {
          releaseMedia();
          sessionRef.current = null;
          rejectAcknowledgements(new Error("SESSION_CLOSED"));
          if (intentionalCloseRef.current) {
            setConnectionState("DISCONNECTED");
          } else {
            setErrorMessage(connectionCloseMessage(event.reason));
            setConnectionState("ERROR");
            addTimeline("SESSION_CLOSED", `code ${event.code}`);
          }
        },
      });
      sessionRef.current = liveSession;
      if (!connectedLoggedRef.current) {
        connectedLoggedRef.current = true;
        addTimeline("SESSION_CONNECTED", GEMINI_LIVE_MODEL);
      }

      recorderRef.current = new ContinuousPcmRecorder(
        audioContext,
        stream,
        (base64Pcm) => {
          if (sessionRef.current === liveSession) {
            liveSession.sendRealtimeInput({
              audio: { data: base64Pcm, mimeType: "audio/pcm;rate=16000" },
            });
          }
        },
        recordSpeechActivity,
      );
      recorderRef.current.start();
      setConnectionState("LISTENING");
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      const code = error instanceof Error ? error.message : "UNKNOWN";
      if (error instanceof DOMException && error.name === "NotAllowedError") {
        failConnection("Microphone permission was denied.", "MICROPHONE_DENIED");
      } else if (code.startsWith("TOKEN:")) {
        failConnection(classifyTokenFailure(code.slice("TOKEN:".length)), code);
      } else {
        failConnection("Could not start the Gemini Live session.", "SESSION_START_FAILED");
      }
    }
  }

  function endConversation(): void {
    intentionalCloseRef.current = true;
    const session = sessionRef.current;
    if (session) {
      try {
        session.sendRealtimeInput({ audioStreamEnd: true });
      } catch {
        // The socket may already be closed.
      }
      session.close();
    }
    sessionRef.current = null;
    rejectAcknowledgements(new Error("SESSION_ENDED"));
    pendingToolsRef.current.clear();
    setPendingToolCount(0);
    releaseMedia();
    setConnectionState("DISCONNECTED");
    addTimeline("SESSION_CLOSED", "ended by developer");
  }

  useEffect(() => {
    return () => {
      intentionalCloseRef.current = true;
      sessionRef.current?.close();
      recorderRef.current?.stop();
      playerRef.current?.stop();
      if (audioContextRef.current) {
        void audioContextRef.current.close();
      }
    };
  }, []);

  const active = connectionState !== "DISCONNECTED" && connectionState !== "ERROR";

  return (
    <main className="mx-auto min-h-screen max-w-7xl px-5 py-8 sm:px-8">
      <header className="rounded-2xl border border-amber-300 bg-amber-50 p-6">
        <p className="text-xs font-bold uppercase tracking-[0.2em] text-amber-800">
          Developer lab · non-production
        </p>
        <h1 className="mt-2 text-3xl font-bold text-slate-950">Gemini 2.5 Live voice POC</h1>
        <p className="mt-3 max-w-4xl text-sm leading-6 text-slate-700">
          Direct browser-to-Gemini audio over a one-use ephemeral token. The long-lived API key
          stays server-side. Commerce results below are fixed synthetic data and never touch the
          Java commerce control plane.
        </p>
      </header>

      <section className="mt-6 grid gap-6 lg:grid-cols-[1fr_1.2fr]">
        <div className="space-y-6">
          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Connection state
                </p>
                <p className="mt-1 font-mono text-xl font-bold text-blue-700">
                  {connectionState}
                </p>
              </div>
              <div className="text-right text-sm text-slate-600">
                <p>{pendingToolCount} pending function(s)</p>
                <p className="font-mono text-xs">{GEMINI_LIVE_MODEL}</p>
              </div>
            </div>

            <div className="mt-6 flex flex-wrap gap-3">
              <button
                type="button"
                onClick={() => void startConversation()}
                disabled={active}
                className="rounded-lg bg-blue-600 px-5 py-3 font-semibold text-white disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                Start conversation
              </button>
              <button
                type="button"
                onClick={endConversation}
                disabled={!active}
                className="rounded-lg border border-slate-300 px-5 py-3 font-semibold text-slate-800 disabled:cursor-not-allowed disabled:text-slate-300"
              >
                End conversation
              </button>
            </div>

            <label className="mt-5 flex items-center gap-3 text-sm text-slate-700">
              <input
                type="checkbox"
                checked={failNextTool}
                onChange={(event) => {
                  failNextToolRef.current = event.target.checked;
                  setFailNextTool(event.target.checked);
                }}
                className="h-4 w-4"
              />
              Fail the next fake commerce request after its delay
            </label>

            {errorMessage ? (
              <div role="alert" className="mt-5 rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-800">
                {errorMessage}
              </div>
            ) : null}
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="font-semibold text-slate-950">Manual prompts</h2>
            <ol className="mt-3 list-decimal space-y-3 pl-5 text-sm leading-6 text-slate-700">
              <li>“500 ke andar high-protein vegetarian snacks chahiye, peanuts bilkul nahi.”</li>
              <li>While the function is pending: “Actually, make my budget ₹700.”</li>
              <li>Ask a Telugu follow-up, then say “Please switch to English.”</li>
              <li>Ask “What are their ratings?” and confirm no rating is invented.</li>
            </ol>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="font-semibold text-slate-950">Transcript events</h2>
            <div className="mt-4 max-h-[28rem] space-y-3 overflow-auto" aria-live="polite">
              {transcript.length === 0 ? (
                <p className="text-sm text-slate-500">Transcription will appear when exposed by Live API.</p>
              ) : (
                transcript.map((entry) => (
                  <article key={entry.id} className="rounded-lg bg-slate-50 p-3 text-sm">
                    <div className="flex justify-between gap-3 text-xs font-semibold text-slate-500">
                      <span>{entry.role}{entry.languageCode ? ` · ${entry.languageCode}` : ""}</span>
                      <span>{entry.final ? "FINAL" : "INTERIM"}</span>
                    </div>
                    <p className="mt-1 leading-6 text-slate-800">{entry.text}</p>
                  </article>
                ))
              )}
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-slate-200 bg-slate-950 p-6 text-slate-100 shadow-sm">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="font-semibold">Developer timeline</h2>
              <p className="mt-1 text-xs text-slate-400">Elapsed time is relative to the latest Start.</p>
            </div>
            <span className="rounded bg-slate-800 px-2 py-1 font-mono text-xs">{timeline.length} events</span>
          </div>
          <div className="mt-5 max-h-[66rem] overflow-auto font-mono text-xs" aria-live="polite">
            {timeline.length === 0 ? (
              <p className="text-slate-500">No session events yet.</p>
            ) : (
              <ol className="space-y-2">
                {timeline.map((entry) => (
                  <li key={entry.id} className="grid grid-cols-[5.5rem_1fr] gap-3 border-b border-slate-800 pb-2">
                    <span className="text-slate-500">+{entry.elapsedMs} ms</span>
                    <span>
                      <strong className="text-cyan-300">{entry.event}</strong>
                      {entry.detail ? <span className="mt-1 block break-all text-slate-400">{entry.detail}</span> : null}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </div>
      </section>
    </main>
  );
}
