import { ContinuousPcmRecorder, PcmAudioPlayer } from "./audio";
import {
  GeminiLiveSocket,
  type LiveServerMessage,
  type LiveTransportDiagnostic,
} from "./socket";

export type GeminiLiveRuntimeStage =
  | "microphone-acquisition"
  | "microphone-ready"
  | "audio-context"
  | "audio-context-ready"
  | "playback-worklet"
  | "playback-worklet-ready"
  | "token-request"
  | "token-ready"
  | "live-setup"
  | "live-ready"
  | "capture-worklet"
  | "capture-worklet-ready"
  | "first-audio-frame";

type PlaybackCallbacks = Parameters<typeof PcmAudioPlayer.create>[1];
type CaptureCallbacks = Omit<Parameters<typeof ContinuousPcmRecorder.create>[2], "onAudio">;

export type GeminiLiveRuntimeOptions = {
  acquireToken: () => Promise<{ token: string; setup: Record<string, unknown> }>;
  playback: PlaybackCallbacks;
  capture: CaptureCallbacks;
  onMessage: (message: LiveServerMessage, socket: GeminiLiveSocket) => void;
  onSocketError: () => void;
  onSocketClose: (event: CloseEvent) => void;
  onAudioSendError?: (error: unknown) => void;
  onDiagnostic?: (diagnostic: LiveTransportDiagnostic) => void;
  onStage?: (stage: GeminiLiveRuntimeStage, details?: Record<string, unknown>) => void;
  onMicrophoneReady?: (stream: MediaStream) => void;
  onAudioContextReady?: (context: AudioContext) => void;
  onPlaybackReady?: (player: PcmAudioPlayer) => void;
  onSocketReady?: (socket: GeminiLiveSocket) => void | Promise<void>;
  onRecorderReady?: (recorder: ContinuousPcmRecorder) => void;
};

/**
 * The single microphone/audio/token/socket startup path shared by the Live lab and Buyer UI.
 * A token is deliberately acquired only after microphone permission and both audio context and
 * playback worklet initialization have completed.
 */
export async function startGeminiLiveRuntime(
  options: GeminiLiveRuntimeOptions,
): Promise<GeminiLiveRuntime> {
  let stream: MediaStream | null = null;
  let context: AudioContext | null = null;
  let player: PcmAudioPlayer | null = null;
  let socket: GeminiLiveSocket | null = null;
  let recorder: ContinuousPcmRecorder | null = null;
  try {
    options.onStage?.("microphone-acquisition");
    stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
      video: false,
    });
    options.onStage?.("microphone-ready", {
      trackState: stream.getAudioTracks()[0]?.readyState ?? "missing",
    });
    options.onMicrophoneReady?.(stream);

    options.onStage?.("audio-context");
    context = new AudioContext({ latencyHint: "interactive" });
    await context.resume();
    options.onStage?.("audio-context-ready", { state: context.state });
    options.onAudioContextReady?.(context);

    options.onStage?.("playback-worklet");
    player = await PcmAudioPlayer.create(context, options.playback);
    options.onPlaybackReady?.(player);
    options.onStage?.("playback-worklet-ready");

    options.onStage?.("token-request");
    const token = await options.acquireToken();
    options.onStage?.("token-ready");

    options.onStage?.("live-setup");
    socket = await GeminiLiveSocket.connect(token.token, token.setup, {
      onmessage: options.onMessage,
      onerror: options.onSocketError,
      onclose: options.onSocketClose,
      ondiagnostic: options.onDiagnostic,
    });
    options.onStage?.("live-ready");
    await options.onSocketReady?.(socket);

    options.onStage?.("capture-worklet");
    let firstFrame = true;
    recorder = await ContinuousPcmRecorder.create(context, stream, {
      ...options.capture,
      onAudio: (audio) => {
        try {
          socket?.sendRealtimeInput({
            audio: { data: audio, mimeType: "audio/pcm;rate=16000" },
          });
          if (firstFrame) {
            firstFrame = false;
            options.onStage?.("first-audio-frame", { mimeType: "audio/pcm;rate=16000" });
          }
        } catch (error) {
          options.onAudioSendError?.(error);
        }
      },
    });
    options.onRecorderReady?.(recorder);
    recorder.start();
    options.onStage?.("capture-worklet-ready");
    return new GeminiLiveRuntime(socket, recorder, player, context);
  } catch (error) {
    recorder?.stop();
    player?.stop();
    if (context) void context.close();
    if (!recorder) stream?.getTracks().forEach((track) => track.stop());
    socket?.close("session start failed");
    throw error;
  }
}

export class GeminiLiveRuntime {
  constructor(
    readonly socket: GeminiLiveSocket,
    readonly recorder: ContinuousPcmRecorder,
    readonly player: PcmAudioPlayer,
    readonly audioContext: AudioContext,
  ) {}

  releaseMedia(): void {
    this.recorder.stop();
    this.player.stop();
    void this.audioContext.close();
  }

  close(reason: string): void {
    try {
      this.socket.sendRealtimeInput({ audioStreamEnd: true });
    } catch {
      // A dropped socket cannot receive the stream-end marker.
    }
    this.socket.close(reason);
    this.releaseMedia();
  }
}
