/* global AudioWorkletProcessor, registerProcessor, sampleRate */

class GeminiLiveMicCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.targetSampleRate = options.processorOptions?.targetSampleRate ?? 16000;
    this.sourcePosition = 0;
    this.pcm = new Int16Array(640);
    this.pcmLength = 0;
    this.meterSum = 0;
    this.meterCount = 0;
    this.meterTarget = Math.max(1, Math.round(sampleRate * 0.1));
    this.activityThreshold = 0.018;
    this.activityActive = false;
    this.lastAboveThresholdFrame = 0;
    this.frameNumber = 0;
    this.stopped = false;
    this.port.onmessage = (event) => {
      if (event.data?.type === "stop") {
        this.stopped = true;
      }
    };
  }

  emitPcmSample(sample) {
    const clamped = Math.max(-1, Math.min(1, sample));
    this.pcm[this.pcmLength] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff;
    this.pcmLength += 1;
    if (this.pcmLength === this.pcm.length) {
      const packet = this.pcm.buffer;
      this.port.postMessage({ type: "pcm", samples: packet }, [packet]);
      this.pcm = new Int16Array(640);
      this.pcmLength = 0;
    }
  }

  updateMeter(channel) {
    for (let index = 0; index < channel.length; index += 1) {
      this.meterSum += channel[index] * channel[index];
    }
    this.meterCount += channel.length;
    this.frameNumber += channel.length;
    if (this.meterCount < this.meterTarget) {
      return;
    }

    const rms = Math.sqrt(this.meterSum / this.meterCount);
    const wasActive = this.activityActive;
    if (rms >= this.activityThreshold) {
      this.activityActive = true;
      this.lastAboveThresholdFrame = this.frameNumber;
    } else if (
      this.activityActive &&
      this.frameNumber - this.lastAboveThresholdFrame >= sampleRate * 0.65
    ) {
      this.activityActive = false;
    }
    this.port.postMessage({
      type: "meter",
      rms,
      active: this.activityActive,
      changed: wasActive !== this.activityActive,
    });
    this.meterSum = 0;
    this.meterCount = 0;
  }

  process(inputs) {
    if (this.stopped) {
      return false;
    }
    const channel = inputs[0]?.[0];
    if (!channel || channel.length === 0) {
      return true;
    }

    this.updateMeter(channel);
    const step = sampleRate / this.targetSampleRate;
    while (this.sourcePosition < channel.length) {
      const lower = Math.floor(this.sourcePosition);
      const upper = Math.min(lower + 1, channel.length - 1);
      const weight = this.sourcePosition - lower;
      this.emitPcmSample(channel[lower] * (1 - weight) + channel[upper] * weight);
      this.sourcePosition += step;
    }
    this.sourcePosition -= channel.length;
    return true;
  }
}

registerProcessor("gemini-live-mic-capture", GeminiLiveMicCaptureProcessor);
