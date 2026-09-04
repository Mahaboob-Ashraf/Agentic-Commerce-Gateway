"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { buyerApi, BuyerApiError } from "@/lib/buyer/api";
import type { CommerceRequestResult } from "@/lib/buyer/types";
import {
  executeProductionVoiceCommerce,
  initialVoiceSessionState,
  reduceVoiceSession,
  toAuthoritativeVoiceResult,
  VoiceToolCallGuard,
  type VoiceOrbState,
} from "@/lib/buyer/voice-state";
import { ContinuousPcmRecorder, PcmAudioPlayer } from "@/lib/gemini-live/audio";
import {
  BUYER_GEMINI_LIVE_MODEL,
  buildBuyerGeminiLiveRawSetup,
} from "@/lib/gemini-live/buyer-config";
import { COMMERCE_FUNCTION_NAME } from "@/lib/gemini-live/config";
import {
  GeminiLiveSocket,
  type LiveFunctionCall,
  type LiveServerMessage,
  type LiveTranscription,
} from "@/lib/gemini-live/socket";
import { useBuyerSession } from "./buyer-session";
import { Icon } from "./icons";
import styles from "./buyer-voice.module.css";

type VoiceTurn = {
  id: string;
  role: "USER" | "AMANA";
  text: string;
  final: boolean;
  languageCode?: string;
};

type Props = {
  onClose: () => void;
  onCommerceRequest: (query: string) => Promise<CommerceRequestResult>;
};

const ACKNOWLEDGEMENT_TIMEOUT_MS = 15_000;
const HEALTHY_SESSION_AGE_MS = 8 * 60_000;
const MAX_TRANSCRIPT_TURNS = 80;

const stateCopy: Record<VoiceOrbState, string> = {
  IDLE: "Ready when you are",
  CONNECTING: "Connecting securely…",
  LISTENING: "Listening",
  USER_SPEAKING: "I’m listening…",
  THINKING: "Thinking…",
  AGENT_SPEAKING: "Amana is speaking",
  COMMERCE_RUNNING: "Searching connected stores…",
  AWAITING_AUTHORIZATION: "Review the proposal on screen",
  ERROR: "Voice is unavailable",
  RECONNECTING: "Starting a fresh voice session…",
};

function tokenFailure(code?: string): string {
  if (code === "GEMINI_QUOTA_OR_RATE_LIMIT") return "Voice is busy right now. Continue by typing below.";
  if (code === "GEMINI_AUTHENTICATION_FAILED") return "Voice could not authenticate. Continue by typing below.";
  if (code === "BUYER_AUTHENTICATION_REQUIRED" || code === "BUYER_AUTHORITY_REQUIRED") {
    return "Your Buyer session could not be verified. Continue by typing below.";
  }
  return "Voice could not connect. Your text conversation is still available.";
}

function mergeStreamingText(current: string, incoming: string): string {
  if (!current) return incoming;
  if (incoming.startsWith(current)) return incoming;
  if (current.endsWith(incoming)) return current;
  return current + incoming;
}

export function BuyerVoice({ onClose, onCommerceRequest }: Props) {
  const { actor } = useBuyerSession();
  const [session, dispatch] = useReducer(reduceVoiceSession, initialVoiceSessionState);
  const [transcript, setTranscript] = useState<VoiceTurn[]>([]);
  const [paused, setPaused] = useState(false);
  const [starting, setStarting] = useState(false);
  const [rotationPending, setRotationPending] = useState(false);
  const [health, setHealth] = useState<"HEALTHY" | "SLOW" | "DEGRADED">("HEALTHY");
  const socketRef = useRef<GeminiLiveSocket | null>(null);
  const recorderRef = useRef<ContinuousPcmRecorder | null>(null);
  const playerRef = useRef<PcmAudioPlayer | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const orbRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLElement>(null);
  const startingRef = useRef(false);
  const wantsSessionRef = useRef(false);
  const sessionGenerationRef = useRef(0);
  const rotationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const transcriptRef = useRef<VoiceTurn[]>([]);
  const draftIdsRef = useRef<Record<VoiceTurn["role"], string | null>>({ USER: null, AMANA: null });
  const transcriptFlushRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toolGuardRef = useRef(new VoiceToolCallGuard());
  const modelAudioSeenRef = useRef(false);
  const speechEndedAtRef = useRef<number | null>(null);
  const consecutiveSlowTurnsRef = useRef(0);
  const acknowledgementBarrierRef = useRef(false);

  const preferredLanguage = (() => {
    if (typeof window === "undefined" || !actor?.actorId) return null;
    try {
      const stored = localStorage.getItem(`amana:voice:${actor.actorId}`);
      const parsed = stored ? (JSON.parse(stored) as { language?: unknown }) : null;
      return typeof parsed?.language === "string" ? parsed.language : null;
    } catch {
      return null;
    }
  })();

  const publishTranscript = useCallback(() => {
    if (transcriptFlushRef.current) return;
    transcriptFlushRef.current = setTimeout(() => {
      transcriptFlushRef.current = null;
      transcriptRef.current = transcriptRef.current.slice(-MAX_TRANSCRIPT_TURNS);
      setTranscript([...transcriptRef.current]);
    }, 120);
  }, []);

  const ingestTranscript = useCallback((role: VoiceTurn["role"], item: LiveTranscription, interim = false) => {
    if (!item.text) return;
    const existingId = draftIdsRef.current[role];
    const existing = transcriptRef.current.find((turn) => turn.id === existingId);
    if (existing) {
      existing.text = interim ? item.text : mergeStreamingText(existing.text, item.text);
      existing.languageCode = item.languageCode ?? existing.languageCode;
      existing.final = Boolean(item.finished);
    } else {
      const turn: VoiceTurn = {
        id: crypto.randomUUID(),
        role,
        text: item.text,
        final: Boolean(item.finished),
        languageCode: item.languageCode,
      };
      transcriptRef.current.push(turn);
      draftIdsRef.current[role] = turn.id;
    }
    if (item.finished) draftIdsRef.current[role] = null;
    publishTranscript();
  }, [publishTranscript]);

  const finalizeTranscript = useCallback((role: VoiceTurn["role"]) => {
    const id = draftIdsRef.current[role];
    const turn = transcriptRef.current.find((candidate) => candidate.id === id);
    if (turn) turn.final = true;
    draftIdsRef.current[role] = null;
    publishTranscript();
  }, [publishTranscript]);

  const releaseMedia = useCallback(() => {
    recorderRef.current?.stop();
    recorderRef.current = null;
    playerRef.current?.stop();
    playerRef.current = null;
    if (audioContextRef.current) void audioContextRef.current.close();
    audioContextRef.current = null;
    orbRef.current?.style.setProperty("--voice-level", "0");
  }, []);

  const closeSocket = useCallback((reason: string) => {
    const current = socketRef.current;
    socketRef.current = null;
    try {
      current?.sendRealtimeInput({ audioStreamEnd: true });
    } catch {
      // A dropped socket cannot receive the stream-end marker.
    }
    current?.close(reason);
    releaseMedia();
    if (rotationTimerRef.current) clearTimeout(rotationTimerRef.current);
    rotationTimerRef.current = null;
  }, [releaseMedia]);

  const sendToolResponse = useCallback((socket: GeminiLiveSocket, call: LiveFunctionCall, response: Record<string, unknown>) => {
    if (!call.id) return;
    socket.sendToolResponse([{ id: call.id, name: COMMERCE_FUNCTION_NAME, response }]);
  }, []);

  const runCommerceTool = useCallback(async (call: LiveFunctionCall, socket: GeminiLiveSocket) => {
    if (call.name !== COMMERCE_FUNCTION_NAME || !call.id) return;
    if (!toolGuardRef.current.begin(call.id)) {
      sendToolResponse(socket, call, { status: "DUPLICATE_IGNORED" });
      return;
    }
    try {
      const query = typeof call.args?.query === "string" ? call.args.query : null;
      if (!query) throw new Error("INVALID_COMMERCE_REQUEST");
      const player = playerRef.current;
      if (!player) throw new Error("PLAYBACK_NOT_AVAILABLE");
      acknowledgementBarrierRef.current = true;
      await player.capturePlaybackWatermark(ACKNOWLEDGEMENT_TIMEOUT_MS, modelAudioSeenRef.current).drained;
      if (socketRef.current !== socket) return;
      dispatch({ type: "COMMERCE_STARTED" });
      sendToolResponse(socket, call, { status: "STARTED" });
      // The guard has already admitted this call; use a single-call guard for the shared executor helper.
      const admitted = new VoiceToolCallGuard();
      const result = await executeProductionVoiceCommerce(call.id, query, admitted, onCommerceRequest);
      if (!result) return;
      dispatch({
        type: "COMMERCE_FINISHED",
        awaitingAuthorization: Boolean(result.transactionProposalId && result.paymentReady),
      });
      if (socketRef.current === socket) {
        socket.sendClientContent({
          turns: [{
            role: "user",
            parts: [{ text: `APP_COMMERCE_RESULT\n${JSON.stringify(toAuthoritativeVoiceResult(result))}` }],
          }],
          turnComplete: true,
        });
      }
    } catch (error) {
      const code = error instanceof Error ? error.message : "COMMERCE_REQUEST_FAILED";
      dispatch({ type: "COMMERCE_FINISHED", awaitingAuthorization: false });
      if (socketRef.current === socket) {
        try {
          socket.sendClientContent({
            turns: [{
              role: "user",
              parts: [{ text: `APP_COMMERCE_RESULT\n${JSON.stringify({ type: "APP_COMMERCE_RESULT", status: "FAILED", code })}` }],
            }],
            turnComplete: true,
          });
        } catch {
          // Text mode remains available if the result cannot be narrated.
        }
      }
    } finally {
      acknowledgementBarrierRef.current = false;
    }
  }, [onCommerceRequest, sendToolResponse]);

  const handleMessage = useCallback((message: LiveServerMessage, socket: GeminiLiveSocket) => {
    if (message.goAway) setRotationPending(true);
    if (message.voiceActivity?.voiceActivityType === "ACTIVITY_START") {
      dispatch({ type: "USER_ACTIVITY", active: true });
    } else if (message.voiceActivity?.voiceActivityType === "ACTIVITY_END") {
      finalizeTranscript("USER");
      speechEndedAtRef.current = performance.now();
      dispatch({ type: "USER_ACTIVITY", active: false });
    }
    const content = message.serverContent;
    if (content?.interimInputTranscription) ingestTranscript("USER", content.interimInputTranscription, true);
    if (content?.inputTranscription) ingestTranscript("USER", content.inputTranscription);
    if (content?.outputTranscription) ingestTranscript("AMANA", content.outputTranscription);
    for (const part of content?.modelTurn?.parts ?? []) {
      if (part.inlineData?.data && part.inlineData.mimeType?.startsWith("audio/pcm")) {
        modelAudioSeenRef.current = true;
        playerRef.current?.enqueue(part.inlineData.data);
      }
    }
    if (content?.generationComplete || content?.turnComplete) {
      finalizeTranscript("AMANA");
      playerRef.current?.finishTurn();
    }
    if (content?.interrupted) {
      playerRef.current?.interrupt();
      dispatch({ type: "INTERRUPTED" });
    }
    for (const call of message.toolCall?.functionCalls ?? []) void runCommerceTool(call, socket);
  }, [finalizeTranscript, ingestTranscript, runCommerceTool]);

  const startSession = useCallback(async (reconnecting = false) => {
    if (startingRef.current || socketRef.current) return;
    startingRef.current = true;
    setStarting(true);
    wantsSessionRef.current = true;
    dispatch({ type: reconnecting ? "RECONNECT" : "CONNECT" });
    setHealth("HEALTHY");
    consecutiveSlowTurnsRef.current = 0;
    speechEndedAtRef.current = null;
    const generation = ++sessionGenerationRef.current;
    let stream: MediaStream | null = null;
    let ownedSocket: GeminiLiveSocket | null = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        video: false,
      });
      if (generation !== sessionGenerationRef.current) throw new Error("SESSION_SUPERSEDED");
      const context = new AudioContext({ latencyHint: "interactive" });
      await context.resume();
      audioContextRef.current = context;
      playerRef.current = await PcmAudioPlayer.create(context, {
        onStarted: () => {
          const speechEndedAt = speechEndedAtRef.current;
          speechEndedAtRef.current = null;
          if (speechEndedAt !== null) {
            const latency = performance.now() - speechEndedAt;
            if (latency > 10_000) {
              setHealth("DEGRADED");
              setRotationPending(true);
            } else if (latency > 5_000) {
              consecutiveSlowTurnsRef.current += 1;
              if (consecutiveSlowTurnsRef.current >= 2) {
                setHealth("DEGRADED");
                setRotationPending(true);
              } else {
                setHealth("SLOW");
              }
            } else {
              consecutiveSlowTurnsRef.current = 0;
              setHealth("HEALTHY");
            }
          }
          dispatch({ type: "MODEL_AUDIO_STARTED" });
        },
        onCompleted: () => {
          modelAudioSeenRef.current = false;
          dispatch({ type: "MODEL_AUDIO_ENDED" });
        },
        onUnderrun: () => undefined,
        onDepth: () => undefined,
        onError: () => dispatch({ type: "FAIL", message: "Audio playback stopped. Continue by typing below." }),
      });
      const token = await buyerApi.createVoiceToken(preferredLanguage);
      if (generation !== sessionGenerationRef.current) throw new Error("SESSION_SUPERSEDED");
      if (token.model !== BUYER_GEMINI_LIVE_MODEL) throw new Error("LIVE_MODEL_CONSTRAINT_MISMATCH");
      ownedSocket = await GeminiLiveSocket.connect(
        token.token,
        buildBuyerGeminiLiveRawSetup(preferredLanguage),
        {
          onmessage: handleMessage,
          onerror: () => {
            if (socketRef.current === ownedSocket) {
              closeSocket("socket error");
              dispatch({ type: "FAIL", message: "The voice session was interrupted. Continue by typing below." });
            }
          },
          onclose: () => {
            if (socketRef.current !== ownedSocket) return;
            socketRef.current = null;
            releaseMedia();
            dispatch({ type: "FAIL", message: "The voice session ended. Continue by typing or reconnect." });
          },
        },
      );
      if (generation !== sessionGenerationRef.current) throw new Error("SESSION_SUPERSEDED");
      socketRef.current = ownedSocket;
      recorderRef.current = await ContinuousPcmRecorder.create(context, stream, {
        onAudio: (audio) => {
          if (socketRef.current === ownedSocket) {
            try {
              ownedSocket?.sendRealtimeInput({ audio: { data: audio, mimeType: "audio/pcm;rate=16000" } });
            } catch {
              dispatch({ type: "FAIL", message: "Microphone audio could not be sent. Continue by typing below." });
            }
          }
        },
        onLevel: (level) => orbRef.current?.style.setProperty("--voice-level", String(Math.min(1, level * 7))),
        // Local RMS drives the orb level only; Gemini server VAD owns speech boundaries.
        onLocalActivity: () => undefined,
        onError: () => dispatch({ type: "FAIL", message: "The microphone disconnected. Continue by typing below." }),
      });
      recorderRef.current.start();
      dispatch({ type: "CONNECTED" });
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      if (socketRef.current === ownedSocket) socketRef.current = null;
      ownedSocket?.close("session start failed");
      releaseMedia();
      const code = error instanceof BuyerApiError ? error.code : error instanceof Error ? error.message : undefined;
      const message = error instanceof DOMException && error.name === "NotAllowedError"
        ? "Microphone permission was denied. You can continue by typing below."
        : tokenFailure(code);
      if (code === "SESSION_SUPERSEDED" && wantsSessionRef.current) dispatch({ type: "RECONNECT" });
      else dispatch({ type: "FAIL", message });
    } finally {
      startingRef.current = false;
      setStarting(false);
    }
  }, [closeSocket, handleMessage, preferredLanguage, releaseMedia]);

  useEffect(() => {
    if (!session.connected) return;
    rotationTimerRef.current = setTimeout(() => setRotationPending(true), HEALTHY_SESSION_AGE_MS);
    return () => {
      if (rotationTimerRef.current) clearTimeout(rotationTimerRef.current);
      rotationTimerRef.current = null;
    };
  }, [closeSocket, session.connected, startSession]);

  useEffect(() => {
    if (!rotationPending || !session.connected || session.commerceRunning || acknowledgementBarrierRef.current) return;
    if (["USER_SPEAKING", "THINKING", "AGENT_SPEAKING"].includes(session.orb)) return;
    const timer = setTimeout(() => {
      setRotationPending(false);
      closeSocket("fresh session rotation");
      toolGuardRef.current.clear();
      transcriptRef.current = [];
      draftIdsRef.current = { USER: null, AMANA: null };
      setTranscript([]);
      void startSession(true);
    }, 0);
    return () => clearTimeout(timer);
  }, [closeSocket, rotationPending, session.commerceRunning, session.connected, session.orb, startSession]);

  const stopSession = useCallback(() => {
    wantsSessionRef.current = false;
    sessionGenerationRef.current += 1;
    if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
    reconnectTimerRef.current = null;
    closeSocket("buyer ended voice");
    dispatch({ type: "STOP" });
  }, [closeSocket]);

  useEffect(() => {
    const visibility = () => {
      const hidden = document.visibilityState !== "visible";
      setPaused(hidden);
      if (hidden && wantsSessionRef.current) {
        sessionGenerationRef.current += 1;
        closeSocket("tab hidden");
        dispatch({ type: "RECONNECT" });
      } else if (!hidden && wantsSessionRef.current && !socketRef.current) {
        const resumeWhenReleased = () => {
          if (!wantsSessionRef.current || document.visibilityState !== "visible") return;
          if (startingRef.current) {
            reconnectTimerRef.current = setTimeout(resumeWhenReleased, 250);
          } else {
            void startSession(true);
          }
        };
        reconnectTimerRef.current = setTimeout(resumeWhenReleased, 250);
      }
    };
    document.addEventListener("visibilitychange", visibility);
    return () => document.removeEventListener("visibilitychange", visibility);
  }, [closeSocket, startSession]);

  useEffect(() => {
    const target = containerRef.current;
    if (!target || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => {
      setPaused(document.visibilityState !== "visible" || !entry.isIntersecting);
    }, { threshold: 0.05 });
    observer.observe(target);
    return () => observer.disconnect();
  }, []);

  useEffect(() => () => {
    wantsSessionRef.current = false;
    sessionGenerationRef.current += 1;
    if (transcriptFlushRef.current) clearTimeout(transcriptFlushRef.current);
    if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
    closeSocket("voice panel unmounted");
  }, [closeSocket]);

  function exitVoice() {
    stopSession();
    onClose();
  }

  return (
    <section ref={containerRef} className={styles.voiceMode} aria-labelledby="amana-voice-title" data-paused={paused}>
      <header className={styles.voiceHeader}>
        <div><p>Gemini Live · Buyer voice</p><h2 id="amana-voice-title">Talk with Amana</h2></div>
        <button type="button" onClick={exitVoice} aria-label="Close voice mode"><Icon name="close" /></button>
      </header>
      <div className={styles.orbStage}>
        <div ref={orbRef} className={styles.voiceOrb} data-state={session.orb} aria-hidden="true">
          <i /><i /><i /><span />
        </div>
        <strong className={styles.stateLabel} role="status" aria-live="polite">{stateCopy[session.orb]}</strong>
        <small>{session.connected ? `${BUYER_GEMINI_LIVE_MODEL} · microphone on · session ${health.toLowerCase()}` : "Microphone starts only when you choose Start voice"}</small>
      </div>
      <div className={styles.transcript} aria-label="Live voice transcript" aria-live="polite">
        {transcript.length === 0 ? <p className={styles.transcriptEmpty}>Your live transcript will appear here. Speak naturally and switch languages whenever you like.</p> : transcript.map((turn) => (
          <p key={turn.id} data-role={turn.role} data-final={turn.final}>{turn.text}</p>
        ))}
      </div>
      {session.error && <div className={styles.voiceError} role="alert">{session.error}</div>}
      <footer className={styles.voiceControls}>
        {!session.connected ? <button className={styles.primary} type="button" onClick={() => void startSession(session.orb === "ERROR")} disabled={starting}><Icon name="mic" />{session.orb === "ERROR" ? "Try voice again" : "Start voice"}</button> : <button type="button" onClick={stopSession}>End voice session</button>}
        <button type="button" onClick={exitVoice}>Continue by typing</button>
      </footer>
      <p className={styles.boundary}><Icon name="shield" />Voice can search and prepare a proposal. Only the on-screen <strong>Authorize purchase</strong> control can approve spending.</p>
    </section>
  );
}
