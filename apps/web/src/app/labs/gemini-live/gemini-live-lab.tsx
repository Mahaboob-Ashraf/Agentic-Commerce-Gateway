"use client";

import { useEffect, useRef, useState } from "react";
import {
  ContinuousPcmRecorder,
  PcmAudioPlayer,
  inspectMicrophoneSettings,
  type MicrophoneSettingsReport,
  type PlaybackWatermark,
} from "@/lib/gemini-live/audio";
import {
  COMMERCE_FUNCTION_NAME,
  DEFAULT_ASYNC_MODE,
  DEFAULT_GEMINI_LIVE_MODEL,
  DEFAULT_PROACTIVE_AUDIO,
  GEMINI_LIVE_MODELS,
  buildGeminiLiveRawSetup,
  normalizeSessionOptions,
  type GeminiLiveAsyncMode,
  type GeminiLiveModel,
  type GeminiLiveSessionOptions,
} from "@/lib/gemini-live/config";
import {
  GeminiLiveSocket,
  type LiveFunctionCall,
  type LiveServerMessage,
  type LiveTranscription,
} from "@/lib/gemini-live/socket";

type ConnectionState =
  | "DISCONNECTED"
  | "CONNECTING"
  | "LISTENING"
  | "SPEAKING"
  | "TOOL_PENDING"
  | "ERROR";

type SessionHealth = "HEALTHY" | "SLOW" | "DEGRADED";
type FirstAudioKind = "ORDINARY" | "ACKNOWLEDGEMENT";

interface TimelineEvent {
  id: string;
  event: string;
  at: string;
  elapsedMs: number;
  detail?: string;
}

interface TranscriptTurn {
  id: string;
  role: "USER" | "GEMINI";
  text: string;
  languageCode?: string;
  final: boolean;
  at: string;
  sessionNumber: number;
}

interface FakeCommerceResult {
  requestId: string;
  status: "COMPLETED";
  products: Array<{
    name: "Demo Protein Snack A" | "Demo Protein Snack B";
    price_paise: 19900 | 24900;
    vegetarian: true;
    peanut_constraint: "PASS";
  }>;
}

interface CommerceJob {
  requestId: string;
  callId: string;
  query: string;
  mode: GeminiLiveAsyncMode;
  session: GeminiLiveSocket;
  startedAt: number;
  controller: AbortController;
  superseded: boolean;
  responseSent: boolean;
}

interface CompactSessionState {
  preferredLanguage: string | null;
  latestCommerceQuery: string | null;
  latestCompletedResult: FakeCommerceResult | null;
  latestRequestStatus: string | null;
}

interface TurnMetric {
  id: string;
  sessionNumber: number;
  turn: number;
  sessionAgeMs: number;
  firstPlaybackLatencyMs: number | null;
  firstChunkToPlaybackMs: number | null;
  playbackUnderruns: number;
  localActivityCount: number;
  model: GeminiLiveModel;
  asyncMode: GeminiLiveAsyncMode;
  involvedCommerce: boolean;
  firstAudioKind: FirstAudioKind;
}

interface RecentLatency {
  sessionNumber: number;
  turn: number;
  latencyMs: number;
  sessionAgeMs: number;
  model: GeminiLiveModel;
  involvedCommerce: boolean;
  firstAudioKind: FirstAudioKind;
}

interface TokenResponse {
  token: string;
  model: GeminiLiveModel;
  asyncMode: GeminiLiveAsyncMode;
  proactiveAudio: boolean;
}

const FAKE_COMMERCE_DELAY_MS = 8_000;
const FAKE_COMMERCE_TIMEOUT_MS = 12_000;
const ACKNOWLEDGEMENT_TIMEOUT_MS = 15_000;
const MAX_TIMELINE_EVENTS = 300;
const MAX_TRANSCRIPT_TURNS = 100;
const MAX_METRIC_ROWS = 120;
const RECENT_LATENCY_WINDOW_SIZE = 6;
const LATENCY_WARNING_THRESHOLD_MS = 5_000;
const LATENCY_SEVERE_THRESHOLD_MS = 10_000;
const HEALTHY_TURNS_TO_REARM_RECOVERY = 2;

const EMPTY_MICROPHONE_SETTINGS: MicrophoneSettingsReport = {
  echoCancellation: null,
  noiseSuppression: null,
  autoGainControl: null,
  channelCount: null,
  sampleRate: null,
};

function fixedCommerceResult(requestId: string): FakeCommerceResult {
  return {
    requestId,
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
    result.requestId.startsWith("commerce-") &&
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

function runFakeCommerce(
  requestId: string,
  shouldFail: boolean,
  signal: AbortSignal,
): Promise<FakeCommerceResult> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (action: () => void) => {
      if (settled) return;
      settled = true;
      clearTimeout(completionTimer);
      clearTimeout(timeoutTimer);
      signal.removeEventListener("abort", abort);
      action();
    };
    const abort = () =>
      finish(() => reject(new DOMException("Commerce job aborted", "AbortError")));
    const completionTimer = setTimeout(() => {
      finish(() => {
        if (shouldFail) reject(new Error("SIMULATED_FAKE_COMMERCE_FAILURE"));
        else resolve(fixedCommerceResult(requestId));
      });
    }, FAKE_COMMERCE_DELAY_MS);
    const timeoutTimer = setTimeout(
      () => finish(() => reject(new Error("FAKE_COMMERCE_TIMEOUT"))),
      FAKE_COMMERCE_TIMEOUT_MS,
    );
    signal.addEventListener("abort", abort, { once: true });
  });
}

function mergeStreamingText(current: string, incoming: string): string {
  if (!current) return incoming;
  if (incoming.startsWith(current)) return incoming;
  if (current.endsWith(incoming)) return current;
  return current + incoming;
}

function classifyTokenFailure(code: string): string {
  switch (code) {
    case "GEMINI_API_KEY_MISSING":
      return "GEMINI_API_KEY is missing from the workspace .env file.";
    case "GEMINI_QUOTA_OR_RATE_LIMIT":
      return "Gemini rejected token creation because of quota or rate limits.";
    case "GEMINI_AUTHENTICATION_FAILED":
      return "Gemini rejected the configured API credential.";
    case "UNSUPPORTED_MODEL_CONFIGURATION":
    case "UNSUPPORTED_LIVE_SESSION_OPTIONS":
      return "The selected model configuration is not supported by this lab.";
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

function formatDuration(milliseconds: number | null): string {
  if (milliseconds === null) return "—";
  if (milliseconds < 1_000) return `${milliseconds} ms`;
  return `${(milliseconds / 1_000).toFixed(1)} s`;
}

function reported(value: boolean | null): string {
  if (value === null) return "not reported";
  return value ? "enabled" : "disabled";
}

export function GeminiLiveLab() {
  const [connectionState, setConnectionState] = useState<ConnectionState>("DISCONNECTED");
  const [selectedModel, setSelectedModel] = useState<GeminiLiveModel>(
    DEFAULT_GEMINI_LIVE_MODEL,
  );
  const [asyncMode, setAsyncMode] = useState<GeminiLiveAsyncMode>(DEFAULT_ASYNC_MODE);
  const [proactiveAudio, setProactiveAudio] = useState(DEFAULT_PROACTIVE_AUDIO);
  const [activeOptions, setActiveOptions] = useState<GeminiLiveSessionOptions | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [transcript, setTranscript] = useState<TranscriptTurn[]>([]);
  const [metrics, setMetrics] = useState<TurnMetric[]>([]);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [pendingJobCount, setPendingJobCount] = useState(0);
  const [failNextTool, setFailNextTool] = useState(false);
  const [sessionAgeMs, setSessionAgeMs] = useState(0);
  const [completedTurns, setCompletedTurns] = useState(0);
  const [microphoneSettings, setMicrophoneSettings] = useState(EMPTY_MICROPHONE_SETTINGS);
  const [microphoneLevel, setMicrophoneLevel] = useState(0);
  const [playbackDepthMs, setPlaybackDepthMs] = useState(0);
  const [playbackUnderruns, setPlaybackUnderruns] = useState(0);
  const [localActivityCount, setLocalActivityCount] = useState(0);
  const [localRmsActive, setLocalRmsActive] = useState(false);
  const [sessionHealth, setSessionHealth] = useState<SessionHealth>("HEALTHY");
  const [recentLatencies, setRecentLatencies] = useState<RecentLatency[]>([]);
  const [autoRecoverDegraded, setAutoRecoverDegraded] = useState(true);
  const [latestResult, setLatestResult] = useState<FakeCommerceResult | null>(null);
  const [latestRequestStatus, setLatestRequestStatus] = useState<string | null>(null);
  const [groundingViolations, setGroundingViolations] = useState(0);

  const sessionRef = useRef<GeminiLiveSocket | null>(null);
  const recorderRef = useRef<ContinuousPcmRecorder | null>(null);
  const playerRef = useRef<PcmAudioPlayer | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const sessionStartedAtRef = useRef(0);
  const sessionNumberRef = useRef(0);
  const sessionAgeTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const activeOptionsRef = useRef<GeminiLiveSessionOptions | null>(null);
  const connectedLoggedRef = useRef(false);
  const serverSpeechActiveRef = useRef(false);
  const serverTurnRef = useRef(0);
  const speechEndTimesRef = useRef(new Map<number, number>());
  const currentModelTurnRef = useRef(0);
  const modelOutputActiveRef = useRef(false);
  const firstAudioChunkAtRef = useRef<number | null>(null);
  const currentAudioIsCommerceResultRef = useRef(false);
  const audioSeenTurnsRef = useRef(new Set<number>());
  const latencyRecordedTurnsRef = useRef(new Set<number>());
  const commerceTurnsRef = useRef(new Set<number>());
  const activeAcknowledgementBarriersRef = useRef(0);
  const activeJobsRef = useRef(new Map<string, CommerceJob>());
  const latestJobRef = useRef<CommerceJob | null>(null);
  const failNextToolRef = useRef(false);
  const localActivityCountRef = useRef(0);
  const playbackUnderrunsRef = useRef(0);
  const lastLevelUiAtRef = useRef(0);
  const lastResultInjectedAtRef = useRef<number | null>(null);
  const resultDeliveryPendingRef = useRef(false);
  const sessionHealthRef = useRef<SessionHealth>("HEALTHY");
  const recentLatenciesRef = useRef<RecentLatency[]>([]);
  const autoRecoverDegradedRef = useRef(true);
  const autoRecoveryPendingRef = useRef(false);
  const autoRecoveryLockedRef = useRef(false);
  const healthyTurnsSinceRecoveryRef = useRef(0);
  const autoRecoveryCheckTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const compactStateRef = useRef<CompactSessionState>({
    preferredLanguage: null,
    latestCommerceQuery: null,
    latestCompletedResult: null,
    latestRequestStatus: null,
  });
  const transcriptRef = useRef<TranscriptTurn[]>([]);
  const transcriptDraftIdsRef = useRef<Record<TranscriptTurn["role"], string | null>>({
    USER: null,
    GEMINI: null,
  });
  const transcriptSourcesRef = useRef<Record<TranscriptTurn["role"], "INTERIM" | "FINAL">>({
    USER: "FINAL",
    GEMINI: "FINAL",
  });
  const transcriptFlushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const metricsRef = useRef<TurnMetric[]>([]);

  function addTimeline(event: string, detail?: string): void {
    const now = performance.now();
    setTimeline((current) => [
      ...current.slice(-(MAX_TIMELINE_EVENTS - 1)),
      {
        id: crypto.randomUUID(),
        event,
        at: new Date().toISOString(),
        elapsedMs: Math.max(0, Math.round(now - sessionStartedAtRef.current)),
        detail,
      },
    ]);
  }

  function flushTranscriptSoon(): void {
    if (transcriptFlushTimerRef.current) return;
    transcriptFlushTimerRef.current = setTimeout(() => {
      transcriptFlushTimerRef.current = null;
      const bounded = transcriptRef.current.slice(-MAX_TRANSCRIPT_TURNS);
      transcriptRef.current = bounded;
      setTranscript([...bounded]);
    }, 150);
  }

  function ingestTranscription(
    role: TranscriptTurn["role"],
    transcription: LiveTranscription,
    interim = false,
  ): void {
    const incoming = transcription.text;
    if (!incoming) return;
    if (transcription.languageCode) {
      compactStateRef.current.preferredLanguage = transcription.languageCode;
    }

    let draftId = transcriptDraftIdsRef.current[role];
    let entry = draftId
      ? transcriptRef.current.find((candidate) => candidate.id === draftId)
      : undefined;
    if (!entry) {
      entry = {
        id: crypto.randomUUID(),
        role,
        text: incoming,
        languageCode: transcription.languageCode,
        final: false,
        at: new Date().toISOString(),
        sessionNumber: sessionNumberRef.current,
      };
      transcriptRef.current.push(entry);
      transcriptDraftIdsRef.current[role] = entry.id;
      transcriptSourcesRef.current[role] = interim ? "INTERIM" : "FINAL";
      draftId = entry.id;
    } else {
      if (interim) {
        entry.text = incoming;
        transcriptSourcesRef.current[role] = "INTERIM";
      } else if (transcriptSourcesRef.current[role] === "INTERIM") {
        entry.text = incoming;
        transcriptSourcesRef.current[role] = "FINAL";
      } else {
        entry.text = mergeStreamingText(entry.text, incoming);
      }
      entry.languageCode = transcription.languageCode ?? entry.languageCode;
    }

    if (transcription.finished) {
      entry.final = true;
      transcriptDraftIdsRef.current[role] = null;
    }
    flushTranscriptSoon();
  }

  function finalizeTranscript(role: TranscriptTurn["role"]): void {
    const draftId = transcriptDraftIdsRef.current[role];
    if (!draftId) return;
    const entry = transcriptRef.current.find((candidate) => candidate.id === draftId);
    if (entry) entry.final = true;
    transcriptDraftIdsRef.current[role] = null;
    flushTranscriptSoon();
  }

  function publishMetrics(): void {
    const bounded = metricsRef.current.slice(-MAX_METRIC_ROWS);
    metricsRef.current = bounded;
    setMetrics([...bounded]);
  }

  function updateTurnMetric(turn: number, update: Partial<TurnMetric>): void {
    const sessionNumber = sessionNumberRef.current;
    const index = metricsRef.current.findIndex(
      (metric) => metric.sessionNumber === sessionNumber && metric.turn === turn,
    );
    if (index >= 0) {
      metricsRef.current[index] = { ...metricsRef.current[index], ...update };
    } else if (activeOptionsRef.current) {
      metricsRef.current.push({
        id: crypto.randomUUID(),
        sessionNumber,
        turn,
        sessionAgeMs: Math.max(0, Math.round(performance.now() - sessionStartedAtRef.current)),
        firstPlaybackLatencyMs: null,
        firstChunkToPlaybackMs: null,
        playbackUnderruns: playbackUnderrunsRef.current,
        localActivityCount: localActivityCountRef.current,
        model: activeOptionsRef.current.model,
        asyncMode: activeOptionsRef.current.asyncMode,
        involvedCommerce: false,
        firstAudioKind: "ORDINARY",
        ...update,
      });
    }
    publishMetrics();
  }

  function refreshPendingState(): void {
    const count = activeJobsRef.current.size;
    setPendingJobCount(count);
    if (!sessionRef.current) return;
    if (modelOutputActiveRef.current) setConnectionState("SPEAKING");
    else if (count > 0) setConnectionState("TOOL_PENDING");
    else setConnectionState("LISTENING");
  }

  function updateCommerceTurnClassification(turn: number): void {
    commerceTurnsRef.current.add(turn);
    updateTurnMetric(turn, {
      involvedCommerce: true,
      firstAudioKind: "ACKNOWLEDGEMENT",
    });
    const updated = recentLatenciesRef.current.map((latency) =>
      latency.sessionNumber === sessionNumberRef.current && latency.turn === turn
        ? { ...latency, involvedCommerce: true, firstAudioKind: "ACKNOWLEDGEMENT" as const }
        : latency,
    );
    recentLatenciesRef.current = updated;
    setRecentLatencies([...updated]);
  }

  function setHealth(next: SessionHealth, detail: string): void {
    const previous = sessionHealthRef.current;
    if (previous === next) return;
    sessionHealthRef.current = next;
    setSessionHealth(next);
    if (next === "DEGRADED") {
      addTimeline("LATENCY_DEGRADATION_DETECTED", detail);
      requestAutoRecovery();
    } else if (next === "SLOW") {
      addTimeline("LATENCY_HEALTH_WARNING", detail);
    } else {
      addTimeline("LATENCY_HEALTH_RECOVERED", detail);
    }
  }

  function recordConversationalLatency(turn: number, latencyMs: number): void {
    const options = activeOptionsRef.current;
    if (!options) return;
    const involvedCommerce = commerceTurnsRef.current.has(turn);
    const latency: RecentLatency = {
      sessionNumber: sessionNumberRef.current,
      turn,
      latencyMs,
      sessionAgeMs: Math.max(0, Math.round(performance.now() - sessionStartedAtRef.current)),
      model: options.model,
      involvedCommerce,
      firstAudioKind: involvedCommerce ? "ACKNOWLEDGEMENT" : "ORDINARY",
    };
    recentLatenciesRef.current = [
      ...recentLatenciesRef.current.filter(
        (candidate) =>
          candidate.sessionNumber !== latency.sessionNumber || candidate.turn !== latency.turn,
      ),
      latency,
    ].slice(-RECENT_LATENCY_WINDOW_SIZE);
    setRecentLatencies([...recentLatenciesRef.current]);
    updateTurnMetric(turn, {
      sessionAgeMs: latency.sessionAgeMs,
      firstPlaybackLatencyMs: latencyMs,
      involvedCommerce,
      firstAudioKind: latency.firstAudioKind,
    });

    if (autoRecoveryLockedRef.current) {
      if (latencyMs <= LATENCY_WARNING_THRESHOLD_MS) {
        healthyTurnsSinceRecoveryRef.current += 1;
        if (healthyTurnsSinceRecoveryRef.current >= HEALTHY_TURNS_TO_REARM_RECOVERY) {
          autoRecoveryLockedRef.current = false;
          healthyTurnsSinceRecoveryRef.current = 0;
          addTimeline(
            "LATENCY_RECOVERY_REARMED",
            `${HEALTHY_TURNS_TO_REARM_RECOVERY} fresh healthy turns`,
          );
        }
      } else {
        healthyTurnsSinceRecoveryRef.current = 0;
      }
    }

    const current = recentLatenciesRef.current.at(-1);
    const previous = recentLatenciesRef.current.at(-2);
    const detail = `${latencyMs} ms · turn ${turn}`;
    if (
      current &&
      (current.latencyMs > LATENCY_SEVERE_THRESHOLD_MS ||
        (current.latencyMs > LATENCY_WARNING_THRESHOLD_MS &&
          previous !== undefined &&
          previous.latencyMs > LATENCY_WARNING_THRESHOLD_MS))
    ) {
      setHealth("DEGRADED", detail);
    } else if (latencyMs > LATENCY_WARNING_THRESHOLD_MS) {
      setHealth("SLOW", detail);
    } else {
      setHealth("HEALTHY", detail);
    }
  }

  function recoveryIsSafe(): boolean {
    return (
      sessionRef.current !== null &&
      !serverSpeechActiveRef.current &&
      activeAcknowledgementBarriersRef.current === 0 &&
      !modelOutputActiveRef.current &&
      activeJobsRef.current.size === 0 &&
      !resultDeliveryPendingRef.current
    );
  }

  function queueAutoRecoveryCheck(): void {
    if (autoRecoveryCheckTimerRef.current) return;
    autoRecoveryCheckTimerRef.current = setTimeout(() => {
      autoRecoveryCheckTimerRef.current = null;
      attemptAutoRecoveryIfSafe();
    }, 0);
  }

  function requestAutoRecovery(): void {
    if (!autoRecoverDegradedRef.current || autoRecoveryPendingRef.current) return;
    if (autoRecoveryLockedRef.current) {
      addTimeline(
        "LATENCY_AUTO_RECOVERY_SUPPRESSED",
        "waiting for fresh healthy turns before another attempt",
      );
      return;
    }
    autoRecoveryPendingRef.current = true;
    addTimeline("LATENCY_AUTO_RECOVERY_QUEUED", "waiting for a safe rotation boundary");
    queueAutoRecoveryCheck();
  }

  function attemptAutoRecoveryIfSafe(): void {
    if (!autoRecoveryPendingRef.current || !autoRecoverDegradedRef.current) return;
    if (!recoveryIsSafe() || !activeOptionsRef.current) return;
    const options = activeOptionsRef.current;
    autoRecoveryPendingRef.current = false;
    autoRecoveryLockedRef.current = true;
    healthyTurnsSinceRecoveryRef.current = 0;
    addTimeline("LATENCY_RECOVERY_ROTATION_STARTED", "fresh Live session requested");
    void rotateSession(options, true, "AUTO_LATENCY_RECOVERY").then(() => {
      if (sessionRef.current) {
        addTimeline("LATENCY_RECOVERY_COMPLETED", `session ${sessionNumberRef.current}`);
      }
    });
  }

  function captureAcknowledgementBarrier(turn: number): PlaybackWatermark {
    const player = playerRef.current;
    if (!player) throw new Error("PLAYBACK_NOT_AVAILABLE");
    const watermark = player.capturePlaybackWatermark(
      ACKNOWLEDGEMENT_TIMEOUT_MS,
      audioSeenTurnsRef.current.has(turn),
    );
    activeAcknowledgementBarriersRef.current += 1;
    addTimeline("ACK_BARRIER_STARTED", `turn ${turn}`);
    addTimeline(
      "ACK_PLAYBACK_WATERMARK",
      `${watermark.id} · ${watermark.targetSamples} PCM samples`,
    );
    return watermark;
  }

  function sendFunctionResponse(
    session: GeminiLiveSocket,
    callId: string,
    response: Record<string, unknown>,
    scheduling?: "SILENT" | "WHEN_IDLE" | "INTERRUPT",
  ): void {
    session.sendToolResponse([
      {
        id: callId,
        name: COMMERCE_FUNCTION_NAME,
        response,
        ...(scheduling ? { scheduling } : {}),
      },
    ]);
  }

  function sendFunctionFailure(
    session: GeminiLiveSocket,
    call: LiveFunctionCall,
    code: string,
  ): void {
    if (!call.id || !call.name || sessionRef.current !== session) return;
    sendFunctionResponse(
      session,
      call.id,
      { error: { code, status: "FAILED" } },
      activeOptionsRef.current?.asyncMode === "GEMINI_NON_BLOCKING" ? "WHEN_IDLE" : undefined,
    );
    addTimeline("FUNCTION_RESPONSE_SENT", `${call.id} · FAILED`);
  }

  function supersedeJob(job: CommerceJob, reason: string): void {
    if (job.superseded) return;
    job.superseded = true;
    job.controller.abort();
    activeJobsRef.current.delete(job.requestId);
    addTimeline("COMMERCE_REQUEST_SUPERSEDED", `${job.requestId} · ${reason}`);
    compactStateRef.current.latestRequestStatus = "SUPERSEDED";
    setLatestRequestStatus("SUPERSEDED");
    if (
      job.mode === "GEMINI_NON_BLOCKING" &&
      !job.responseSent &&
      sessionRef.current === job.session
    ) {
      try {
        sendFunctionResponse(
          job.session,
          job.callId,
          { requestId: job.requestId, status: "SUPERSEDED" },
          "SILENT",
        );
        job.responseSent = true;
        addTimeline("FUNCTION_RESPONSE_SENT", `${job.callId} · SUPERSEDED · SILENT`);
      } catch {
        // A closing session cannot receive a supersession response.
      }
    }
    refreshPendingState();
    queueAutoRecoveryCheck();
  }

  function injectApplicationEvent(event: Record<string, unknown>): void {
    const session = sessionRef.current;
    if (!session) throw new Error("NO_ACTIVE_SESSION_FOR_APPLICATION_EVENT");
    session.sendClientContent({
      turns: [
        {
          role: "user",
          parts: [
            {
              text: `APPLICATION_EVENT_JSON\n${JSON.stringify(event)}`,
            },
          ],
        },
      ],
      turnComplete: true,
    });
  }

  async function completeCommerceJob(
    job: CommerceJob,
    shouldFail: boolean,
  ): Promise<void> {
    try {
      const result = await runFakeCommerce(job.requestId, shouldFail, job.controller.signal);
      if (job.superseded || latestJobRef.current !== job) return;
      if (!isValidCommerceResult(result)) throw new Error("INVALID_FAKE_COMMERCE_RESPONSE");

      const durationMs = Math.round(performance.now() - job.startedAt);
      addTimeline("COMMERCE_REQUEST_COMPLETED", `${job.requestId} · ${durationMs} ms`);
      addTimeline("FAKE_COMMERCE_COMPLETED", `${job.requestId} · ${durationMs} ms`);
      compactStateRef.current.latestCompletedResult = result;
      compactStateRef.current.latestRequestStatus = "COMPLETED";
      setLatestResult(result);
      setLatestRequestStatus("COMPLETED");
      resultDeliveryPendingRef.current = true;

      if (job.mode === "APP_MANAGED") {
        const event = {
          type: "APP_COMMERCE_RESULT",
          requestId: result.requestId,
          status: result.status,
          products: result.products,
        };
        injectApplicationEvent(event);
        lastResultInjectedAtRef.current = performance.now();
        addTimeline("COMMERCE_RESULT_INJECTED", job.requestId);
      } else if (sessionRef.current === job.session) {
        sendFunctionResponse(job.session, job.callId, { output: result }, "WHEN_IDLE");
        job.responseSent = true;
        addTimeline("FUNCTION_RESPONSE_SENT", `${job.callId} · COMPLETED · WHEN_IDLE`);
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") return;
      if (job.superseded) return;
      const code = error instanceof Error ? error.message : "FAKE_COMMERCE_FAILED";
      compactStateRef.current.latestRequestStatus = "FAILED";
      setLatestRequestStatus("FAILED");
      resultDeliveryPendingRef.current = true;
      setErrorMessage(
        code === "FAKE_COMMERCE_TIMEOUT"
          ? "The synthetic commerce request timed out; no success was displayed."
          : "The synthetic commerce request failed; no success was displayed.",
      );
      addTimeline("FUNCTION_FAILED", `${job.callId} · ${code}`);
      try {
        if (job.mode === "APP_MANAGED") {
          injectApplicationEvent({
            type: "APP_COMMERCE_RESULT",
            requestId: job.requestId,
            status: "FAILED",
            error: code,
          });
          addTimeline("COMMERCE_RESULT_INJECTED", `${job.requestId} · FAILED`);
        } else if (sessionRef.current === job.session) {
          sendFunctionResponse(
            job.session,
            job.callId,
            { error: { code, status: "FAILED" } },
            "WHEN_IDLE",
          );
          job.responseSent = true;
          addTimeline("FUNCTION_RESPONSE_SENT", `${job.callId} · FAILED · WHEN_IDLE`);
        }
      } catch {
        resultDeliveryPendingRef.current = false;
        addTimeline("ERROR", "FAILED_RESULT_DELIVERY_FAILED");
      }
    } finally {
      activeJobsRef.current.delete(job.requestId);
      refreshPendingState();
      queueAutoRecoveryCheck();
    }
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

    const acknowledgementTurn = Math.max(1, serverTurnRef.current);
    updateCommerceTurnClassification(acknowledgementTurn);
    let acknowledgementWatermark: PlaybackWatermark | null = null;
    let acknowledgementPassedAt: number | null = null;
    try {
      acknowledgementWatermark = captureAcknowledgementBarrier(acknowledgementTurn);
      await acknowledgementWatermark.drained;
      acknowledgementPassedAt = performance.now();
      addTimeline(
        "ACK_BARRIER_PASSED",
        `${acknowledgementWatermark.id} · pre-function audio drained`,
      );
      if (sessionRef.current !== session || !activeOptionsRef.current) return;

      if (latestJobRef.current && activeJobsRef.current.has(latestJobRef.current.requestId)) {
        supersedeJob(latestJobRef.current, "newer commerce request");
      }

      const requestId = `commerce-${crypto.randomUUID()}`;
      const job: CommerceJob = {
        requestId,
        callId,
        query: query.trim(),
        mode: activeOptionsRef.current.asyncMode,
        session,
        startedAt: performance.now(),
        controller: new AbortController(),
        superseded: false,
        responseSent: false,
      };
      latestJobRef.current = job;
      activeJobsRef.current.set(requestId, job);
      compactStateRef.current.latestCommerceQuery = job.query;
      compactStateRef.current.latestRequestStatus = "STARTED";
      setLatestResult(null);
      setLatestRequestStatus("STARTED");
      if (acknowledgementPassedAt !== null) {
        addTimeline(
          "ACK_BARRIER_TO_COMMERCE_START_MS",
          `${Math.max(0, Math.round(job.startedAt - acknowledgementPassedAt))}`,
        );
      }
      addTimeline("APP_COMMERCE_STARTED", `${requestId} · after audio barrier`);
      addTimeline("COMMERCE_REQUEST_STARTED", `${requestId} · query length ${job.query.length}`);

      if (job.mode === "APP_MANAGED") {
        sendFunctionResponse(session, callId, { requestId, status: "STARTED" });
        job.responseSent = true;
        addTimeline("FUNCTION_RESPONSE_SENT", `${callId} · STARTED · immediate`);
      }

      const shouldFail = failNextToolRef.current;
      if (shouldFail) {
        failNextToolRef.current = false;
        setFailNextTool(false);
      }
      refreshPendingState();
      void completeCommerceJob(job, shouldFail);
    } catch (error) {
      const code = error instanceof Error ? error.message : "FAKE_COMMERCE_FAILED";
      if (code === "ACK_BARRIER_TIMEOUT") {
        addTimeline("ACK_BARRIER_TIMEOUT", `turn ${acknowledgementTurn}`);
      } else {
        addTimeline("ACK_BARRIER_FAILED", `${acknowledgementTurn} · ${code}`);
      }
      addTimeline("FUNCTION_FAILED", `${callId} · ${code}`);
      setErrorMessage(
        code === "ACK_BARRIER_TIMEOUT"
          ? "Commerce did not start because audible acknowledgement playback did not complete."
          : "The synthetic commerce function could not start.",
      );
      sendFunctionFailure(session, call, code);
    } finally {
      if (acknowledgementWatermark) {
        activeAcknowledgementBarriersRef.current = Math.max(
          0,
          activeAcknowledgementBarriersRef.current - 1,
        );
        queueAutoRecoveryCheck();
      }
    }
  }

  function handleFirstModelAudioChunk(): void {
    if (modelOutputActiveRef.current) return;
    modelOutputActiveRef.current = true;
    const turn = Math.max(1, serverTurnRef.current);
    currentModelTurnRef.current = turn;
    currentAudioIsCommerceResultRef.current = resultDeliveryPendingRef.current;
    if (!currentAudioIsCommerceResultRef.current) {
      audioSeenTurnsRef.current.add(turn);
    }
    const now = performance.now();
    firstAudioChunkAtRef.current = now;
    if (lastResultInjectedAtRef.current !== null) {
      const latency = Math.max(0, Math.round(now - lastResultInjectedAtRef.current));
      addTimeline("RESULT_INJECTED_TO_MODEL_AUDIO_MS", `${latency}`);
      addTimeline("FIRST_COMMERCE_RESULT_AUDIO", `turn ${turn}`);
      lastResultInjectedAtRef.current = null;
    }
  }

  function handleLiveMessage(message: LiveServerMessage, session: GeminiLiveSocket | null): void {
    if (message.setupComplete && !connectedLoggedRef.current) {
      connectedLoggedRef.current = true;
      addTimeline("SESSION_CONNECTED", activeOptionsRef.current?.model);
    }
    if (message.goAway?.timeLeft) {
      addTimeline("SESSION_GO_AWAY", message.goAway.timeLeft);
    }

    const voiceActivity = message.voiceActivity?.voiceActivityType;
    if (voiceActivity === "ACTIVITY_START" && !serverSpeechActiveRef.current) {
      serverSpeechActiveRef.current = true;
      addTimeline("SERVER_SPEECH_STARTED", "Gemini server VAD");
    } else if (voiceActivity === "ACTIVITY_END" && serverSpeechActiveRef.current) {
      serverSpeechActiveRef.current = false;
      serverTurnRef.current += 1;
      const turn = serverTurnRef.current;
      const now = performance.now();
      speechEndTimesRef.current.set(turn, now);
      setCompletedTurns(turn);
      updateTurnMetric(turn, {
        sessionAgeMs: Math.max(0, Math.round(now - sessionStartedAtRef.current)),
        playbackUnderruns: playbackUnderrunsRef.current,
        localActivityCount: localActivityCountRef.current,
      });
      addTimeline("SERVER_SPEECH_ENDED", `Gemini server VAD · turn ${turn}`);
      queueAutoRecoveryCheck();
    }

    const content = message.serverContent;
    if (content?.interimInputTranscription) {
      ingestTranscription("USER", content.interimInputTranscription, true);
    }
    if (content?.inputTranscription) {
      ingestTranscription("USER", content.inputTranscription);
    }
    if (content?.outputTranscription) {
      ingestTranscription("GEMINI", content.outputTranscription);
    }

    for (const part of content?.modelTurn?.parts ?? []) {
      if (part.inlineData?.data && part.inlineData.mimeType?.startsWith("audio/pcm")) {
        try {
          handleFirstModelAudioChunk();
          playerRef.current?.enqueue(part.inlineData.data);
        } catch {
          setErrorMessage("Gemini returned invalid audio data or playback failed.");
          setConnectionState("ERROR");
          addTimeline("ERROR", "INVALID_MODEL_AUDIO");
        }
      }
    }

    if (content?.generationComplete || content?.turnComplete) {
      finalizeTranscript("GEMINI");
      playerRef.current?.finishTurn();
    }
    if (content?.interrupted) {
      playerRef.current?.interrupt();
      modelOutputActiveRef.current = false;
      firstAudioChunkAtRef.current = null;
      if (currentAudioIsCommerceResultRef.current) {
        resultDeliveryPendingRef.current = false;
      }
      currentAudioIsCommerceResultRef.current = false;
      addTimeline("SESSION_INTERRUPTED", "playback buffer flushed");
      refreshPendingState();
      queueAutoRecoveryCheck();
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
    for (const cancelledId of message.toolCallCancellation?.ids ?? []) {
      const job = [...activeJobsRef.current.values()].find(
        (candidate) => candidate.callId === cancelledId,
      );
      if (job) supersedeJob(job, "Gemini tool-call cancellation");
      addTimeline("FUNCTION_CALL_CANCELLED", cancelledId);
    }
  }

  function stopSessionClock(): void {
    if (sessionAgeTimerRef.current) {
      clearInterval(sessionAgeTimerRef.current);
      sessionAgeTimerRef.current = null;
    }
  }

  function releaseMedia(): void {
    stopSessionClock();
    recorderRef.current?.stop();
    recorderRef.current = null;
    playerRef.current?.stop();
    playerRef.current = null;
    if (audioContextRef.current) {
      void audioContextRef.current.close();
      audioContextRef.current = null;
    }
    setMicrophoneLevel(0);
    setLocalRmsActive(false);
    setPlaybackDepthMs(0);
  }

  function closeCurrentSession(reason: string): void {
    const session = sessionRef.current;
    sessionRef.current = null;
    if (session) {
      try {
        session.sendRealtimeInput({ audioStreamEnd: true });
      } catch {
        // The socket may already be closed.
      }
      session.close(reason);
    }
    releaseMedia();
    serverSpeechActiveRef.current = false;
    modelOutputActiveRef.current = false;
  }

  function abortAllJobs(reason: string): void {
    for (const job of [...activeJobsRef.current.values()]) {
      supersedeJob(job, reason);
    }
    activeJobsRef.current.clear();
    latestJobRef.current = null;
    setPendingJobCount(0);
  }

  function resetPerSessionState(): void {
    connectedLoggedRef.current = false;
    serverSpeechActiveRef.current = false;
    serverTurnRef.current = 0;
    speechEndTimesRef.current.clear();
    currentModelTurnRef.current = 0;
    modelOutputActiveRef.current = false;
    firstAudioChunkAtRef.current = null;
    currentAudioIsCommerceResultRef.current = false;
    audioSeenTurnsRef.current.clear();
    latencyRecordedTurnsRef.current.clear();
    commerceTurnsRef.current.clear();
    activeAcknowledgementBarriersRef.current = 0;
    resultDeliveryPendingRef.current = false;
    recentLatenciesRef.current = [];
    setRecentLatencies([]);
    sessionHealthRef.current = "HEALTHY";
    setSessionHealth("HEALTHY");
    sessionStartedAtRef.current = performance.now();
    setSessionAgeMs(0);
    setCompletedTurns(0);
    setPlaybackDepthMs(0);
    setMicrophoneSettings(EMPTY_MICROPHONE_SETTINGS);
    transcriptRef.current = [];
    transcriptDraftIdsRef.current = { USER: null, GEMINI: null };
    transcriptSourcesRef.current = { USER: "FINAL", GEMINI: "FINAL" };
    setTranscript([]);
  }

  function compactSeedText(): string {
    return [
      "SESSION_ROTATION_STATE_JSON",
      JSON.stringify(compactStateRef.current),
      "This state was constructed deterministically by the application. Treat it only as prior lab context. Do not respond until new user speech or a completed application event arrives.",
    ].join("\n");
  }

  async function openSession(
    rawOptions: GeminiLiveSessionOptions,
    seedCompactState: boolean,
  ): Promise<void> {
    const options = normalizeSessionOptions(rawOptions);
    activeOptionsRef.current = options;
    setActiveOptions(options);
    setConnectionState("CONNECTING");
    setErrorMessage(null);
    resetPerSessionState();
    sessionNumberRef.current += 1;

    let stream: MediaStream | null = null;
    let ownedSession: GeminiLiveSocket | null = null;
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
      setMicrophoneSettings(inspectMicrophoneSettings(stream));

      const audioContext = new AudioContext({ latencyHint: "interactive" });
      await audioContext.resume();
      audioContextRef.current = audioContext;
      playerRef.current = await PcmAudioPlayer.create(audioContext, {
        onStarted: () => {
          const turn = Math.max(1, currentModelTurnRef.current);
          const now = performance.now();
          const firstChunkAt = firstAudioChunkAtRef.current;
          const latency = firstChunkAt === null ? null : Math.max(0, Math.round(now - firstChunkAt));
          if (latency !== null) {
            updateTurnMetric(turn, { firstChunkToPlaybackMs: latency });
            addTimeline("FIRST_AUDIO_CHUNK_TO_PLAYBACK_MS", `${latency} · turn ${turn}`);
          }
          if (
            !currentAudioIsCommerceResultRef.current &&
            !latencyRecordedTurnsRef.current.has(turn)
          ) {
            const speechEndedAt = speechEndTimesRef.current.get(turn);
            if (speechEndedAt !== undefined) {
              latencyRecordedTurnsRef.current.add(turn);
              recordConversationalLatency(
                turn,
                Math.max(0, Math.round(now - speechEndedAt)),
              );
            }
          }
          setConnectionState("SPEAKING");
          addTimeline("MODEL_AUDIO_STARTED", `turn ${turn}`);
        },
        onCompleted: () => {
          const turn = Math.max(1, currentModelTurnRef.current);
          modelOutputActiveRef.current = false;
          firstAudioChunkAtRef.current = null;
          if (currentAudioIsCommerceResultRef.current) {
            resultDeliveryPendingRef.current = false;
          }
          currentAudioIsCommerceResultRef.current = false;
          addTimeline("MODEL_AUDIO_ENDED", `turn ${turn}`);
          refreshPendingState();
          queueAutoRecoveryCheck();
        },
        onUnderrun: () => {
          playbackUnderrunsRef.current += 1;
          setPlaybackUnderruns(playbackUnderrunsRef.current);
          updateTurnMetric(Math.max(1, currentModelTurnRef.current), {
            playbackUnderruns: playbackUnderrunsRef.current,
          });
          addTimeline("PLAYBACK_BUFFER_UNDERRUN", `count ${playbackUnderrunsRef.current}`);
        },
        onDepth: (depthMs) => {
          setPlaybackDepthMs(depthMs);
        },
        onError: (code) => {
          setErrorMessage("The playback worklet reported a failure.");
          setConnectionState("ERROR");
          addTimeline("ERROR", code);
        },
      });

      const tokenResponse = await fetch("/api/labs/gemini-live/token", {
        method: "POST",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        cache: "no-store",
        body: JSON.stringify(options),
      });
      const tokenPayload = (await tokenResponse.json()) as Partial<TokenResponse> & {
        error?: string;
      };
      if (!tokenResponse.ok || !tokenPayload.token) {
        throw new Error(`TOKEN:${tokenPayload.error ?? "UNKNOWN"}`);
      }
      if (
        tokenPayload.model !== options.model ||
        tokenPayload.asyncMode !== options.asyncMode ||
        tokenPayload.proactiveAudio !== options.proactiveAudio
      ) {
        throw new Error("TOKEN:SESSION_CONSTRAINT_MISMATCH");
      }

      ownedSession = await GeminiLiveSocket.connect(
        tokenPayload.token,
        buildGeminiLiveRawSetup(options),
        {
          onmessage: (message, socket) => handleLiveMessage(message, socket),
          onerror: () => {
            if (sessionRef.current === ownedSession) {
              setErrorMessage("Gemini Live reported a WebSocket failure.");
              setConnectionState("ERROR");
              addTimeline("ERROR", "LIVE_SOCKET_ERROR");
              closeCurrentSession("socket error");
            }
          },
          onclose: (event) => {
            if (sessionRef.current !== ownedSession) return;
            sessionRef.current = null;
            releaseMedia();
            setErrorMessage(connectionCloseMessage(event.reason));
            setConnectionState("ERROR");
            addTimeline("SESSION_CLOSED", `unexpected · code ${event.code}`);
          },
        },
      );
      sessionRef.current = ownedSession;
      if (!connectedLoggedRef.current) {
        connectedLoggedRef.current = true;
        addTimeline("SESSION_CONNECTED", `${options.model} · session ${sessionNumberRef.current}`);
      }

      if (seedCompactState) {
        ownedSession.sendClientContent({
          turns: [{ role: "user", parts: [{ text: compactSeedText() }] }],
          turnComplete: false,
        });
        addTimeline("SESSION_CONTEXT_SEEDED", "compact deterministic state only");
      }

      recorderRef.current = await ContinuousPcmRecorder.create(audioContext, stream, {
        onAudio: (base64Pcm) => {
          if (sessionRef.current === ownedSession) {
            try {
              ownedSession?.sendRealtimeInput({
                audio: { data: base64Pcm, mimeType: "audio/pcm;rate=16000" },
              });
            } catch {
              setErrorMessage("Microphone audio could not be sent to Gemini Live.");
              addTimeline("ERROR", "MICROPHONE_SEND_FAILED");
            }
          }
        },
        onLevel: (rms) => {
          const now = performance.now();
          if (now - lastLevelUiAtRef.current >= 200) {
            lastLevelUiAtRef.current = now;
            setMicrophoneLevel(rms);
          }
        },
        onLocalActivity: (active) => {
          setLocalRmsActive(active);
          if (active) {
            localActivityCountRef.current += 1;
            setLocalActivityCount(localActivityCountRef.current);
          }
        },
        onError: (code) => {
          setErrorMessage("The microphone capture worklet reported a failure.");
          setConnectionState("ERROR");
          addTimeline("ERROR", code);
        },
      });
      recorderRef.current.start();
      sessionAgeTimerRef.current = setInterval(() => {
        setSessionAgeMs(Math.max(0, Math.round(performance.now() - sessionStartedAtRef.current)));
      }, 1_000);
      setConnectionState("LISTENING");
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      if (sessionRef.current === ownedSession) sessionRef.current = null;
      ownedSession?.close("session start failed");
      releaseMedia();
      const code = error instanceof Error ? error.message : "UNKNOWN";
      if (error instanceof DOMException && error.name === "NotAllowedError") {
        setErrorMessage("Microphone permission was denied.");
        addTimeline("ERROR", "MICROPHONE_DENIED");
      } else if (code.startsWith("TOKEN:")) {
        setErrorMessage(classifyTokenFailure(code.slice("TOKEN:".length)));
        addTimeline("ERROR", code);
      } else if (code.includes("AudioWorklet") || code.includes("worklet")) {
        setErrorMessage("An AudioWorklet could not be loaded or initialized.");
        addTimeline("ERROR", "WORKLET_LOADING_FAILED");
      } else {
        setErrorMessage("Could not start the Gemini Live session.");
        addTimeline("ERROR", `SESSION_START_FAILED · ${code}`);
      }
      setConnectionState("ERROR");
      throw error;
    }
  }

  async function startConversation(): Promise<void> {
    if (connectionState !== "DISCONNECTED" && connectionState !== "ERROR") return;
    closeCurrentSession("fresh start");
    abortAllJobs("fresh session");
    sessionNumberRef.current = 0;
    metricsRef.current = [];
    setMetrics([]);
    setTimeline([]);
    setLatestResult(null);
    setLatestRequestStatus(null);
    setGroundingViolations(0);
    compactStateRef.current = {
      preferredLanguage: null,
      latestCommerceQuery: null,
      latestCompletedResult: null,
      latestRequestStatus: null,
    };
    localActivityCountRef.current = 0;
    playbackUnderrunsRef.current = 0;
    autoRecoveryPendingRef.current = false;
    autoRecoveryLockedRef.current = false;
    healthyTurnsSinceRecoveryRef.current = 0;
    setLocalActivityCount(0);
    setLocalRmsActive(false);
    setPlaybackUnderruns(0);
    try {
      await openSession({ model: selectedModel, asyncMode, proactiveAudio }, false);
    } catch {
      // openSession has classified and displayed the failure.
    }
  }

  async function rotateSession(
    options: GeminiLiveSessionOptions,
    preserveCompactState: boolean,
    reason: string,
  ): Promise<void> {
    if (reason !== "AUTO_LATENCY_RECOVERY") {
      autoRecoveryPendingRef.current = false;
      autoRecoveryLockedRef.current = false;
      healthyTurnsSinceRecoveryRef.current = 0;
    }
    const wasActive = sessionRef.current !== null;
    if (!wasActive) {
      const normalized = normalizeSessionOptions(options);
      setSelectedModel(normalized.model);
      setAsyncMode(normalized.asyncMode);
      setProactiveAudio(normalized.proactiveAudio);
      return;
    }
    addTimeline("SESSION_ROTATION_STARTED", reason);
    closeCurrentSession("developer session rotation");
    abortAllJobs("session rotation");
    const normalized = normalizeSessionOptions(options);
    setSelectedModel(normalized.model);
    setAsyncMode(normalized.asyncMode);
    setProactiveAudio(normalized.proactiveAudio);
    try {
      await openSession(normalized, preserveCompactState);
      addTimeline("SESSION_ROTATION_COMPLETED", `${reason} · ${normalized.model}`);
    } catch {
      setErrorMessage("Session rotation failed. Start a new session to retry.");
      addTimeline("ERROR", "SESSION_ROTATION_FAILED");
    }
  }

  function endConversation(): void {
    closeCurrentSession("developer ended session");
    abortAllJobs("session ended");
    activeOptionsRef.current = null;
    autoRecoveryPendingRef.current = false;
    setActiveOptions(null);
    setConnectionState("DISCONNECTED");
    addTimeline("SESSION_CLOSED", "ended by developer");
  }

  useEffect(() => {
    const activeJobs = activeJobsRef.current;
    return () => {
      if (transcriptFlushTimerRef.current) clearTimeout(transcriptFlushTimerRef.current);
      if (autoRecoveryCheckTimerRef.current) clearTimeout(autoRecoveryCheckTimerRef.current);
      stopSessionClock();
      for (const job of activeJobs.values()) job.controller.abort();
      sessionRef.current?.close("component unmounted");
      recorderRef.current?.stop();
      playerRef.current?.stop();
      if (audioContextRef.current) void audioContextRef.current.close();
    };
  }, []);

  const active = connectionState !== "DISCONNECTED" && connectionState !== "ERROR";
  const displayedOptions = activeOptions ?? {
    model: selectedModel,
    asyncMode,
    proactiveAudio,
  };

  return (
    <main className="mx-auto min-h-screen max-w-7xl px-5 py-8 sm:px-8">
      <header className="rounded-2xl border border-amber-300 bg-amber-50 p-6">
        <p className="text-xs font-bold uppercase tracking-[0.2em] text-amber-800">
          Developer lab · non-production
        </p>
        <h1 className="mt-2 text-3xl font-bold text-slate-950">
          Gemini Live voice hardening · 2.5 vs 3.1
        </h1>
        <p className="mt-3 max-w-4xl text-sm leading-6 text-slate-700">
          Direct browser-to-Gemini audio uses a one-use, server-minted token constrained to the
          allowlisted model and setup. The long-lived API key remains server-side. Commerce data is
          fixed synthetic lab data and never touches the Java commerce control plane.
        </p>
      </header>

      <section className="mt-6 grid gap-6 lg:grid-cols-[1fr_1.15fr]">
        <div className="space-y-6">
          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="text-sm font-semibold text-slate-700">
                Model
                <select
                  value={selectedModel}
                  disabled={connectionState === "CONNECTING"}
                  onChange={(event) => {
                    const model = event.target.value as GeminiLiveModel;
                    void rotateSession(
                      {
                        model,
                        asyncMode: model === GEMINI_LIVE_MODELS[1] ? "APP_MANAGED" : asyncMode,
                        proactiveAudio: model === GEMINI_LIVE_MODELS[0] && proactiveAudio,
                      },
                      false,
                      "MODEL_SWITCH",
                    );
                  }}
                  className="mt-2 w-full rounded-lg border border-slate-300 bg-white p-3 font-mono text-xs"
                >
                  {GEMINI_LIVE_MODELS.map((model) => (
                    <option key={model} value={model}>{model}</option>
                  ))}
                </select>
              </label>
              <label className="text-sm font-semibold text-slate-700">
                Async mode
                <select
                  value={asyncMode}
                  onChange={(event) => {
                    const mode = event.target.value as GeminiLiveAsyncMode;
                    void rotateSession(
                      { model: selectedModel, asyncMode: mode, proactiveAudio },
                      true,
                      "ASYNC_MODE_CHANGE",
                    );
                  }}
                  disabled={
                    selectedModel === GEMINI_LIVE_MODELS[1] ||
                    connectionState === "CONNECTING"
                  }
                  className="mt-2 w-full rounded-lg border border-slate-300 bg-white p-3 text-sm disabled:bg-slate-100"
                >
                  <option value="APP_MANAGED">Application-managed async (default)</option>
                  {selectedModel === GEMINI_LIVE_MODELS[0] ? (
                    <option value="GEMINI_NON_BLOCKING">2.5 NON_BLOCKING + WHEN_IDLE</option>
                  ) : null}
                </select>
              </label>
            </div>

            <label className="mt-4 flex items-start gap-3 text-sm text-slate-700">
              <input
                type="checkbox"
                checked={proactiveAudio}
                disabled
                readOnly
                className="mt-1 h-4 w-4"
              />
              <span>
                Proactive audio unavailable: the current constrained v1beta Live endpoint rejects
                this setup field. The lab does not emulate it.
              </span>
            </label>

            <label className="mt-4 flex items-start gap-3 text-sm text-slate-700">
              <input
                type="checkbox"
                checked={autoRecoverDegraded}
                onChange={(event) => {
                  const enabled = event.target.checked;
                  autoRecoverDegradedRef.current = enabled;
                  setAutoRecoverDegraded(enabled);
                  if (!enabled) {
                    autoRecoveryPendingRef.current = false;
                  } else if (sessionHealthRef.current === "DEGRADED") {
                    requestAutoRecovery();
                  }
                }}
                className="mt-1 h-4 w-4"
              />
              <span>
                Auto-recover degraded Live session. Rotation waits for a safe boundary and uses a
                fresh constrained session with compact deterministic state only.
              </span>
            </label>

            <div className="mt-6 grid grid-cols-2 gap-3 text-sm sm:grid-cols-5">
              <div><p className="text-slate-500">State</p><p className="font-mono font-bold text-blue-700">{connectionState}</p></div>
              <div><p className="text-slate-500">Session health</p><p className="font-mono font-bold">{sessionHealth}</p></div>
              <div><p className="text-slate-500">Session age</p><p className="font-mono">{formatDuration(sessionAgeMs)}</p></div>
              <div><p className="text-slate-500">Completed turns</p><p className="font-mono">{completedTurns}</p></div>
              <div><p className="text-slate-500">Pending jobs</p><p className="font-mono">{pendingJobCount}</p></div>
            </div>
            <div className="mt-3 rounded-lg bg-slate-50 p-3 text-xs text-slate-600">
              <p><strong>Active model:</strong> <span className="font-mono">{displayedOptions.model}</span></p>
              <p className="mt-1"><strong>Async mode:</strong> {displayedOptions.asyncMode}</p>
              <p className="mt-1">
                <strong>Recent speech-end → first-playback:</strong>{" "}
                {recentLatencies.length === 0
                  ? "Not measured yet"
                  : recentLatencies.map((latency) => `${latency.latencyMs} ms`).join(" · ")}
              </p>
            </div>

            <div className="mt-5 flex flex-wrap gap-3">
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
                onClick={() => void rotateSession(displayedOptions, true, "MANUAL_ROTATION")}
                disabled={!active}
                className="rounded-lg border border-blue-300 px-5 py-3 font-semibold text-blue-800 disabled:cursor-not-allowed disabled:text-slate-300"
              >
                Rotate Live Session
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
              Fail the next fake commerce job after its delay
            </label>

            {errorMessage ? (
              <div role="alert" className="mt-5 rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-800">
                {errorMessage}
              </div>
            ) : null}
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="font-semibold text-slate-950">Media pipeline</h2>
            <div className="mt-4 grid gap-4 text-sm sm:grid-cols-2">
              <div className="rounded-lg bg-slate-50 p-4">
                <p className="font-semibold">Microphone constraints reported</p>
                <dl className="mt-2 space-y-1 text-slate-600">
                  <div className="flex justify-between"><dt>Echo cancellation</dt><dd>{reported(microphoneSettings.echoCancellation)}</dd></div>
                  <div className="flex justify-between"><dt>Noise suppression</dt><dd>{reported(microphoneSettings.noiseSuppression)}</dd></div>
                  <div className="flex justify-between"><dt>Auto gain</dt><dd>{reported(microphoneSettings.autoGainControl)}</dd></div>
                  <div className="flex justify-between"><dt>Track rate</dt><dd>{microphoneSettings.sampleRate ?? "not reported"}</dd></div>
                </dl>
                <div className="mt-3 h-2 overflow-hidden rounded bg-slate-200">
                  <div className="h-full bg-emerald-500" style={{ width: `${Math.min(100, microphoneLevel * 1800)}%` }} />
                </div>
                <p className="mt-1 text-xs text-slate-500">RMS {microphoneLevel.toFixed(4)} · telemetry only</p>
              </div>
              <div className="rounded-lg bg-slate-50 p-4">
                <p className="font-semibold">Continuous playback worklet</p>
                <p className="mt-2 text-slate-600">Buffer depth: <strong>{playbackDepthMs} ms</strong></p>
                <p className="text-slate-600">Underruns: <strong>{playbackUnderruns}</strong></p>
                <p className="text-slate-600">Local RMS starts: <strong>{localActivityCount}</strong></p>
                <p className="text-slate-600">RMS activity: <strong>{localRmsActive ? "ACTIVE" : "QUIET"}</strong></p>
                <p className="mt-2 text-xs text-slate-500">Server VAD alone owns conversational turns.</p>
              </div>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <h2 className="font-semibold text-slate-950">Authoritative synthetic result</h2>
              <span className="rounded bg-slate-100 px-2 py-1 font-mono text-xs">{latestRequestStatus ?? "NONE"}</span>
            </div>
            {latestResult ? (
              <div className="mt-4 space-y-3">
                {latestResult.products.map((product) => (
                  <article key={product.name} className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm">
                    <p className="font-semibold text-emerald-950">{product.name}</p>
                    <p className="mt-1 text-emerald-800">{product.price_paise} paise · vegetarian: true · peanut constraint: PASS</p>
                  </article>
                ))}
                <p className="text-xs text-slate-500">No ratings, reviews, ingredients, protein grams, brands, descriptions, merchant, or broader availability are supplied.</p>
              </div>
            ) : (
              <p className="mt-3 text-sm text-slate-500">No completed latest request.</p>
            )}
            <button
              type="button"
              onClick={() => {
                setGroundingViolations((count) => count + 1);
                addTimeline(
                  "GROUNDING_VIOLATION_RECORDED",
                  `${activeOptionsRef.current?.model ?? selectedModel} · manual count ${groundingViolations + 1}`,
                );
              }}
              className="mt-4 rounded-lg border border-red-300 px-3 py-2 text-xs font-semibold text-red-800"
            >
              Record manual grounding violation ({groundingViolations})
            </button>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="font-semibold text-slate-950">Coherent transcript</h2>
            <div className="mt-4 max-h-[30rem] space-y-3 overflow-auto" aria-live="polite">
              {transcript.length === 0 ? (
                <p className="text-sm text-slate-500">Turn-level transcription will appear when supplied by Live API.</p>
              ) : transcript.map((entry) => (
                <article key={entry.id} className="rounded-lg bg-slate-50 p-3 text-sm">
                  <div className="flex justify-between gap-3 text-xs font-semibold text-slate-500">
                    <span>{entry.role}{entry.languageCode ? ` · ${entry.languageCode}` : ""}</span>
                    <span>{entry.final ? "FINAL" : "STREAMING"}</span>
                  </div>
                  <p className="mt-1 whitespace-pre-wrap leading-6 text-slate-800">{entry.text.trim()}</p>
                </article>
              ))}
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="font-semibold text-slate-950">Manual A/B checklist</h2>
            <ol className="mt-3 list-decimal space-y-2 pl-5 text-sm leading-6 text-slate-700">
              <li>Fresh latency: say “500 ke andar high-protein vegetarian snacks chahiye, peanuts bilkul nahi.”</li>
              <li>Pending correction: during the job say “Actually, make my budget ₹700.”</li>
              <li>Switch naturally through Hinglish, Telugu, and English.</li>
              <li>Stay silent in normal room noise and inspect server VAD versus local RMS.</li>
              <li>Interrupt model audio on laptop speakers; repeat with headphones if useful.</li>
              <li>Ask “What are their ratings?” and record any grounding violation.</li>
              <li>Continue for 8–12 substantive turns or 5–10 minutes; inspect latency rows.</li>
              <li>If latency grows, rotate the session and compare fresh-session rows.</li>
            </ol>
          </div>
        </div>

        <div className="space-y-6">
          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="font-semibold text-slate-950">Long-session degradation view</h2>
            <div className="mt-4 overflow-auto">
              <table className="min-w-full text-left text-xs">
                <thead className="border-b border-slate-200 text-slate-500">
                  <tr>
                    <th className="p-2">Session / turn</th><th className="p-2">Age</th><th className="p-2">Speech end → playback</th><th className="p-2">Chunk → play</th><th className="p-2">Commerce</th><th className="p-2">First audio</th><th className="p-2">Underruns</th><th className="p-2">Local activity</th><th className="p-2">Model</th><th className="p-2">Mode</th>
                  </tr>
                </thead>
                <tbody>
                  {metrics.length === 0 ? (
                    <tr><td colSpan={10} className="p-3 text-slate-500">No completed server-VAD turns yet.</td></tr>
                  ) : metrics.map((metric) => (
                    <tr key={metric.id} className="border-b border-slate-100 align-top">
                      <td className="p-2 font-mono">{metric.sessionNumber} / {metric.turn}</td>
                      <td className="p-2">{formatDuration(metric.sessionAgeMs)}</td>
                      <td className="p-2">{formatDuration(metric.firstPlaybackLatencyMs)}</td>
                      <td className="p-2">{formatDuration(metric.firstChunkToPlaybackMs)}</td>
                      <td className="p-2">{metric.involvedCommerce ? "YES" : "NO"}</td>
                      <td className="p-2">{metric.firstAudioKind}</td>
                      <td className="p-2">{metric.playbackUnderruns}</td>
                      <td className="p-2">{metric.localActivityCount}</td>
                      <td className="max-w-48 break-all p-2 font-mono">{metric.model}</td>
                      <td className="p-2 font-mono">{metric.asyncMode}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-slate-950 p-6 text-slate-100 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <div>
                <h2 className="font-semibold">Bounded developer timeline</h2>
                <p className="mt-1 text-xs text-slate-400">At most {MAX_TIMELINE_EVENTS} events; elapsed time resets per Live session.</p>
              </div>
              <span className="rounded bg-slate-800 px-2 py-1 font-mono text-xs">{timeline.length}</span>
            </div>
            <div className="mt-5 max-h-[90rem] overflow-auto font-mono text-xs" aria-live="polite">
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
        </div>
      </section>
    </main>
  );
}
