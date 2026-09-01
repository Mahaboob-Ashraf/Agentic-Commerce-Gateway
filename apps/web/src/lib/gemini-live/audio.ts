const INPUT_SAMPLE_RATE = 16_000;
const OUTPUT_SAMPLE_RATE = 24_000;
const SPEECH_START_RMS = 0.018;
const SPEECH_END_DELAY_MS = 650;

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function resample(input: Float32Array, sourceRate: number, targetRate: number): Float32Array {
  if (sourceRate === targetRate) {
    return input;
  }

  const ratio = sourceRate / targetRate;
  const output = new Float32Array(Math.max(1, Math.round(input.length / ratio)));
  for (let index = 0; index < output.length; index += 1) {
    const sourcePosition = index * ratio;
    const lower = Math.floor(sourcePosition);
    const upper = Math.min(lower + 1, input.length - 1);
    const weight = sourcePosition - lower;
    output[index] = input[lower] * (1 - weight) + input[upper] * weight;
  }
  return output;
}

function pcm16Base64(input: Float32Array, sampleRate: number): string {
  const samples = resample(input, sampleRate, INPUT_SAMPLE_RATE);
  const buffer = new ArrayBuffer(samples.length * Int16Array.BYTES_PER_ELEMENT);
  const view = new DataView(buffer);
  for (let index = 0; index < samples.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, samples[index]));
    view.setInt16(index * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }
  return bytesToBase64(new Uint8Array(buffer));
}

function rootMeanSquare(samples: Float32Array): number {
  let sum = 0;
  for (const sample of samples) {
    sum += sample * sample;
  }
  return Math.sqrt(sum / Math.max(1, samples.length));
}

export class ContinuousPcmRecorder {
  private source: MediaStreamAudioSourceNode | null = null;
  private processor: ScriptProcessorNode | null = null;
  private silentGain: GainNode | null = null;
  private speechActive = false;
  private lastSpeechAt = 0;

  constructor(
    private readonly context: AudioContext,
    private readonly stream: MediaStream,
    private readonly onAudio: (base64Pcm: string) => void,
    private readonly onActivity: (active: boolean, source: "client-rms") => void,
  ) {}

  start(): void {
    this.source = this.context.createMediaStreamSource(this.stream);
    this.processor = this.context.createScriptProcessor(4096, 1, 1);
    this.silentGain = this.context.createGain();
    this.silentGain.gain.value = 0;

    this.processor.onaudioprocess = (event) => {
      const samples = event.inputBuffer.getChannelData(0);
      this.onAudio(pcm16Base64(samples, this.context.sampleRate));

      const now = performance.now();
      if (rootMeanSquare(samples) >= SPEECH_START_RMS) {
        this.lastSpeechAt = now;
        if (!this.speechActive) {
          this.speechActive = true;
          this.onActivity(true, "client-rms");
        }
      } else if (this.speechActive && now - this.lastSpeechAt >= SPEECH_END_DELAY_MS) {
        this.speechActive = false;
        this.onActivity(false, "client-rms");
      }
    };

    this.source.connect(this.processor);
    this.processor.connect(this.silentGain);
    this.silentGain.connect(this.context.destination);
  }

  stop(): void {
    if (this.speechActive) {
      this.speechActive = false;
      this.onActivity(false, "client-rms");
    }
    if (this.processor) {
      this.processor.onaudioprocess = null;
      this.processor.disconnect();
    }
    this.source?.disconnect();
    this.silentGain?.disconnect();
    this.stream.getTracks().forEach((track) => track.stop());
    this.processor = null;
    this.source = null;
    this.silentGain = null;
  }
}

export class PcmAudioPlayer {
  private readonly sources = new Set<AudioBufferSourceNode>();
  private nextStartAt = 0;
  private speaking = false;
  private completionTimer: ReturnType<typeof setTimeout> | null = null;
  private generation = 0;

  constructor(
    private readonly context: AudioContext,
    private readonly onStarted: () => void,
    private readonly onCompleted: () => void,
  ) {}

  enqueue(base64Pcm: string): void {
    const bytes = base64ToBytes(base64Pcm);
    if (bytes.byteLength === 0 || bytes.byteLength % 2 !== 0) {
      throw new Error("INVALID_MODEL_AUDIO");
    }

    if (this.completionTimer) {
      clearTimeout(this.completionTimer);
      this.completionTimer = null;
    }

    const samples = bytes.byteLength / 2;
    const buffer = this.context.createBuffer(1, samples, OUTPUT_SAMPLE_RATE);
    const channel = buffer.getChannelData(0);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let index = 0; index < samples; index += 1) {
      channel[index] = view.getInt16(index * 2, true) / 0x8000;
    }

    const source = this.context.createBufferSource();
    const generation = this.generation;
    source.buffer = buffer;
    source.connect(this.context.destination);
    this.sources.add(source);

    const startAt = Math.max(this.context.currentTime + 0.015, this.nextStartAt);
    this.nextStartAt = startAt + buffer.duration;
    if (!this.speaking) {
      this.speaking = true;
      this.onStarted();
    }

    source.onended = () => {
      source.disconnect();
      this.sources.delete(source);
      if (generation !== this.generation || this.sources.size > 0) {
        return;
      }
      this.completionTimer = setTimeout(() => {
        this.completionTimer = null;
        if (this.sources.size === 0 && this.speaking && generation === this.generation) {
          this.speaking = false;
          this.onCompleted();
        }
      }, 250);
    };
    source.start(startAt);
  }

  interrupt(): void {
    this.generation += 1;
    if (this.completionTimer) {
      clearTimeout(this.completionTimer);
      this.completionTimer = null;
    }
    for (const source of this.sources) {
      try {
        source.stop();
      } catch {
        // A source that already ended requires no further cleanup.
      }
      source.disconnect();
    }
    this.sources.clear();
    this.nextStartAt = this.context.currentTime;
    this.speaking = false;
  }

  stop(): void {
    this.interrupt();
  }
}
