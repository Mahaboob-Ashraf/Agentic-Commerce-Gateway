/* global AudioWorkletProcessor, registerProcessor, sampleRate */

class GeminiLivePcmPlaybackProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.sourceSampleRate = options.processorOptions?.sourceSampleRate ?? 24000;
    this.prebufferSamples = Math.round(
      (this.sourceSampleRate * (options.processorOptions?.prebufferMs ?? 60)) / 1000,
    );
    this.queue = [];
    this.headOffset = 0;
    this.bufferedSamples = 0;
    this.phase = 0;
    this.expected = false;
    this.started = false;
    this.finishing = false;
    this.starved = false;
    this.framesSinceDepth = 0;
    this.depthIntervalFrames = Math.max(1, Math.round(sampleRate * 0.25));
    this.totalEnqueuedSamples = 0;
    this.totalConsumedSamples = 0;
    this.watermarks = [];
    this.port.onmessage = (event) => this.handleMessage(event.data);
  }

  handleMessage(message) {
    if (message?.type === "enqueue" && message.samples instanceof ArrayBuffer) {
      const samples = new Int16Array(message.samples);
      if (samples.length > 0) {
        this.queue.push(samples);
        this.bufferedSamples += samples.length;
        this.totalEnqueuedSamples += samples.length;
        this.expected = true;
        this.starved = false;
      }
    } else if (message?.type === "finish") {
      this.finishing = true;
      this.maybeEnd();
    } else if (message?.type === "flush") {
      this.failWatermarks("PLAYBACK_FLUSHED");
      this.totalConsumedSamples = this.totalEnqueuedSamples;
      this.reset();
      this.reportDepth();
    } else if (
      message?.type === "watermark" &&
      typeof message.id === "string" &&
      Number.isSafeInteger(message.targetSamples) &&
      typeof message.audibleAudioReceived === "boolean"
    ) {
      this.watermarks.push({
        id: message.id,
        targetSamples: message.targetSamples,
        audibleAudioReceived: message.audibleAudioReceived,
      });
      this.resolveWatermarks();
    } else if (message?.type === "cancelWatermark" && typeof message.id === "string") {
      this.watermarks = this.watermarks.filter((watermark) => watermark.id !== message.id);
    }
  }

  reset() {
    this.queue = [];
    this.headOffset = 0;
    this.bufferedSamples = 0;
    this.phase = 0;
    this.expected = false;
    this.started = false;
    this.finishing = false;
    this.starved = false;
  }

  sampleAt(relativeIndex) {
    let index = this.headOffset + relativeIndex;
    for (const samples of this.queue) {
      if (index < samples.length) {
        return samples[index] / 0x8000;
      }
      index -= samples.length;
    }
    return 0;
  }

  consume(count) {
    let remaining = Math.min(count, this.bufferedSamples);
    const consumed = remaining;
    this.bufferedSamples -= remaining;
    while (remaining > 0 && this.queue.length > 0) {
      const available = this.queue[0].length - this.headOffset;
      if (remaining < available) {
        this.headOffset += remaining;
        remaining = 0;
      } else {
        remaining -= available;
        this.queue.shift();
        this.headOffset = 0;
      }
    }
    this.totalConsumedSamples += consumed;
  }

  resolveWatermarks() {
    const pending = [];
    for (const watermark of this.watermarks) {
      if (
        watermark.audibleAudioReceived &&
        this.totalConsumedSamples >= watermark.targetSamples
      ) {
        this.port.postMessage({
          type: "watermarkDrained",
          id: watermark.id,
          targetSamples: watermark.targetSamples,
        });
      } else {
        pending.push(watermark);
      }
    }
    this.watermarks = pending;
  }

  failWatermarks(code) {
    for (const watermark of this.watermarks) {
      this.port.postMessage({ type: "watermarkFailed", id: watermark.id, code });
    }
    this.watermarks = [];
  }

  reportDepth() {
    this.port.postMessage({
      type: "depth",
      depthMs: Math.max(0, Math.round((this.bufferedSamples * 1000) / this.sourceSampleRate)),
    });
  }

  maybeEnd() {
    if (this.finishing && this.started && this.bufferedSamples <= 1) {
      this.consume(this.bufferedSamples);
      this.resolveWatermarks();
      this.reset();
      this.port.postMessage({ type: "ended" });
      this.reportDepth();
      return true;
    }
    return false;
  }

  process(_inputs, outputs) {
    const output = outputs[0]?.[0];
    if (!output) {
      return true;
    }
    output.fill(0);

    if (this.expected && !this.started) {
      if (this.bufferedSamples >= this.prebufferSamples || this.finishing) {
        if (this.bufferedSamples > 0) {
          this.started = true;
          this.port.postMessage({ type: "started" });
        }
      }
    }

    if (this.expected && this.started) {
      const sourceStep = this.sourceSampleRate / sampleRate;
      for (let index = 0; index < output.length; index += 1) {
        if (this.bufferedSamples <= 1) {
          if (this.bufferedSamples === 1) {
            output[index] = this.sampleAt(0);
            this.consume(1);
          }
          if (!this.maybeEnd() && !this.finishing && !this.starved) {
            this.starved = true;
            this.port.postMessage({ type: "underrun" });
          }
          break;
        }
        output[index] =
          this.sampleAt(0) * (1 - this.phase) + this.sampleAt(1) * this.phase;
        this.phase += sourceStep;
        const consumed = Math.floor(this.phase);
        if (consumed > 0) {
          this.consume(consumed);
          this.phase -= consumed;
        }
      }
    }

    this.resolveWatermarks();

    this.framesSinceDepth += output.length;
    if (this.framesSinceDepth >= this.depthIntervalFrames) {
      this.framesSinceDepth = 0;
      this.reportDepth();
    }
    return true;
  }
}

registerProcessor("gemini-live-pcm-playback", GeminiLivePcmPlaybackProcessor);
