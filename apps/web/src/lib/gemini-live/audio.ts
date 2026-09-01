const INPUT_SAMPLE_RATE = 16_000;
const OUTPUT_SAMPLE_RATE = 24_000;
const CAPTURE_WORKLET_URL = "/labs/gemini-live/mic-capture-worklet.js";
const PLAYBACK_WORKLET_URL = "/labs/gemini-live/pcm-playback-worklet.js";

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

function base64ToPcm16(value: string): ArrayBuffer {
  const binary = atob(value);
  if (binary.length === 0 || binary.length % 2 !== 0) {
    throw new Error("INVALID_MODEL_AUDIO");
  }
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
}

export interface MicrophoneSettingsReport {
  echoCancellation: boolean | null;
  noiseSuppression: boolean | null;
  autoGainControl: boolean | null;
  channelCount: number | null;
  sampleRate: number | null;
}

export function inspectMicrophoneSettings(stream: MediaStream): MicrophoneSettingsReport {
  const settings = stream.getAudioTracks()[0]?.getSettings();
  return {
    echoCancellation:
      typeof settings?.echoCancellation === "boolean" ? settings.echoCancellation : null,
    noiseSuppression:
      typeof settings?.noiseSuppression === "boolean" ? settings.noiseSuppression : null,
    autoGainControl:
      typeof settings?.autoGainControl === "boolean" ? settings.autoGainControl : null,
    channelCount: typeof settings?.channelCount === "number" ? settings.channelCount : null,
    sampleRate: typeof settings?.sampleRate === "number" ? settings.sampleRate : null,
  };
}

interface CaptureMessage {
  type: "pcm" | "meter" | "error";
  samples?: ArrayBuffer;
  rms?: number;
  active?: boolean;
  changed?: boolean;
  code?: string;
}

export class ContinuousPcmRecorder {
  private source: MediaStreamAudioSourceNode | null = null;
  private worklet: AudioWorkletNode | null = null;

  private constructor(
    private readonly context: AudioContext,
    private readonly stream: MediaStream,
    private readonly onAudio: (base64Pcm: string) => void,
    private readonly onLevel: (rms: number) => void,
    private readonly onLocalActivity: (active: boolean) => void,
    private readonly onError: (code: string) => void,
  ) {}

  static async create(
    context: AudioContext,
    stream: MediaStream,
    callbacks: {
      onAudio: (base64Pcm: string) => void;
      onLevel: (rms: number) => void;
      onLocalActivity: (active: boolean) => void;
      onError: (code: string) => void;
    },
  ): Promise<ContinuousPcmRecorder> {
    await context.audioWorklet.addModule(CAPTURE_WORKLET_URL);
    return new ContinuousPcmRecorder(
      context,
      stream,
      callbacks.onAudio,
      callbacks.onLevel,
      callbacks.onLocalActivity,
      callbacks.onError,
    );
  }

  start(): void {
    if (this.worklet) {
      return;
    }
    this.source = this.context.createMediaStreamSource(this.stream);
    this.worklet = new AudioWorkletNode(this.context, "gemini-live-mic-capture", {
      numberOfInputs: 1,
      numberOfOutputs: 0,
      channelCount: 1,
      processorOptions: { targetSampleRate: INPUT_SAMPLE_RATE },
    });
    this.worklet.port.onmessage = (event: MessageEvent<CaptureMessage>) => {
      const message = event.data;
      if (message.type === "pcm" && message.samples) {
        this.onAudio(bytesToBase64(new Uint8Array(message.samples)));
      } else if (message.type === "meter" && typeof message.rms === "number") {
        this.onLevel(message.rms);
        if (message.changed && typeof message.active === "boolean") {
          this.onLocalActivity(message.active);
        }
      } else if (message.type === "error") {
        this.onError(message.code ?? "MICROPHONE_WORKLET_ERROR");
      }
    };
    this.source.connect(this.worklet);
  }

  stop(options: { stopTracks?: boolean } = {}): void {
    if (this.worklet) {
      this.worklet.port.onmessage = null;
      this.worklet.port.postMessage({ type: "stop" });
      this.worklet.disconnect();
    }
    this.source?.disconnect();
    if (options.stopTracks !== false) {
      this.stream.getTracks().forEach((track) => track.stop());
    }
    this.worklet = null;
    this.source = null;
  }
}

interface PlaybackMessage {
  type:
    | "started"
    | "ended"
    | "underrun"
    | "depth"
    | "error"
    | "watermarkDrained"
    | "watermarkFailed";
  depthMs?: number;
  code?: string;
  id?: string;
  targetSamples?: number;
}

interface PlaybackWatermarkWaiter {
  resolve: () => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

export interface PlaybackWatermark {
  id: string;
  targetSamples: number;
  drained: Promise<void>;
}

export class PcmAudioPlayer {
  private worklet: AudioWorkletNode | null = null;
  private stopped = false;
  private enqueuedSamples = 0;
  private watermarkSequence = 0;
  private readonly watermarkWaiters = new Map<string, PlaybackWatermarkWaiter>();

  private constructor(
    private readonly context: AudioContext,
    private readonly callbacks: {
      onStarted: () => void;
      onCompleted: () => void;
      onUnderrun: () => void;
      onDepth: (depthMs: number) => void;
      onError: (code: string) => void;
    },
  ) {}

  static async create(
    context: AudioContext,
    callbacks: {
      onStarted: () => void;
      onCompleted: () => void;
      onUnderrun: () => void;
      onDepth: (depthMs: number) => void;
      onError: (code: string) => void;
    },
  ): Promise<PcmAudioPlayer> {
    await context.audioWorklet.addModule(PLAYBACK_WORKLET_URL);
    const player = new PcmAudioPlayer(context, callbacks);
    player.initialize();
    return player;
  }

  private initialize(): void {
    this.worklet = new AudioWorkletNode(this.context, "gemini-live-pcm-playback", {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [1],
      processorOptions: { sourceSampleRate: OUTPUT_SAMPLE_RATE, prebufferMs: 60 },
    });
    this.worklet.port.onmessage = (event: MessageEvent<PlaybackMessage>) => {
      const message = event.data;
      if (message.type === "started") {
        this.callbacks.onStarted();
      } else if (message.type === "ended") {
        this.callbacks.onCompleted();
      } else if (message.type === "underrun") {
        this.callbacks.onUnderrun();
      } else if (message.type === "depth" && typeof message.depthMs === "number") {
        this.callbacks.onDepth(message.depthMs);
      } else if (message.type === "error") {
        this.callbacks.onError(message.code ?? "PLAYBACK_WORKLET_ERROR");
      } else if (message.type === "watermarkDrained" && message.id) {
        this.settleWatermark(message.id);
      } else if (message.type === "watermarkFailed" && message.id) {
        this.settleWatermark(
          message.id,
          new Error(message.code ?? "PLAYBACK_WATERMARK_FAILED"),
        );
      }
    };
    this.worklet.connect(this.context.destination);
  }

  enqueue(base64Pcm: string): void {
    if (this.stopped || !this.worklet) {
      throw new Error("PLAYBACK_NOT_AVAILABLE");
    }
    const samples = base64ToPcm16(base64Pcm);
    this.enqueuedSamples += samples.byteLength / Int16Array.BYTES_PER_ELEMENT;
    this.worklet.port.postMessage({ type: "enqueue", samples }, [samples]);
  }

  capturePlaybackWatermark(
    timeoutMs: number,
    audibleAudioReceived: boolean,
  ): PlaybackWatermark {
    if (this.stopped || !this.worklet) {
      throw new Error("PLAYBACK_NOT_AVAILABLE");
    }
    const id = `playback-watermark-${++this.watermarkSequence}`;
    const targetSamples = this.enqueuedSamples;
    let resolveWatermark!: () => void;
    let rejectWatermark!: (error: Error) => void;
    const drained = new Promise<void>((resolve, reject) => {
      resolveWatermark = resolve;
      rejectWatermark = reject;
    });
    const timer = setTimeout(() => {
      this.worklet?.port.postMessage({ type: "cancelWatermark", id });
      this.settleWatermark(id, new Error("ACK_BARRIER_TIMEOUT"));
    }, timeoutMs);
    this.watermarkWaiters.set(id, {
      resolve: resolveWatermark,
      reject: rejectWatermark,
      timer,
    });
    this.worklet.port.postMessage({
      type: "watermark",
      id,
      targetSamples,
      audibleAudioReceived,
    });
    return { id, targetSamples, drained };
  }

  finishTurn(): void {
    this.worklet?.port.postMessage({ type: "finish" });
  }

  interrupt(): void {
    this.rejectWatermarks("ACKNOWLEDGEMENT_INTERRUPTED");
    this.worklet?.port.postMessage({ type: "flush" });
  }

  stop(): void {
    if (this.stopped) {
      return;
    }
    this.stopped = true;
    this.rejectWatermarks("PLAYBACK_STOPPED");
    if (this.worklet) {
      this.worklet.port.onmessage = null;
      this.worklet.port.postMessage({ type: "flush" });
      this.worklet.disconnect();
      this.worklet = null;
    }
  }

  private settleWatermark(id: string, error?: Error): void {
    const waiter = this.watermarkWaiters.get(id);
    if (!waiter) return;
    this.watermarkWaiters.delete(id);
    clearTimeout(waiter.timer);
    if (error) waiter.reject(error);
    else waiter.resolve();
  }

  private rejectWatermarks(code: string): void {
    for (const id of [...this.watermarkWaiters.keys()]) {
      this.settleWatermark(id, new Error(code));
    }
  }
}
