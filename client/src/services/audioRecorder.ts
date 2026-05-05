export interface AudioRecorderOptions {
  sampleRate?: number;
  channels?: number;
  noiseReduction?: boolean;
  autoGain?: boolean;
}

export interface AudioChunk {
  data: Float32Array;
  sampleRate: number;
  timestamp: number;
}

type AudioCallback = (chunk: AudioChunk) => void;
type ErrorCallback = (error: Error) => void;

export class AudioRecorderService {
  private audioContext: AudioContext | null = null;
  private mediaStream: MediaStream | null = null;
  private sourceNode: MediaStreamAudioSourceNode | null = null;
  private processorNode: ScriptProcessorNode | null = null;
  private gainNode: GainNode | null = null;
  private noiseReductionNode: AudioWorkletNode | null = null;
  private analyserNode: AnalyserNode | null = null;

  private sampleRate = 16000;
  private channels = 1;
  private noiseReduction = true;
  private autoGain = true;
  private isRecording = false;
  private bufferSize = 4096;

  private audioCallbacks: Set<AudioCallback> = new Set();
  private errorCallbacks: Set<ErrorCallback> = new Set();

  constructor(options: AudioRecorderOptions = {}) {
    this.sampleRate = options.sampleRate ?? 16000;
    this.channels = options.channels ?? 1;
    this.noiseReduction = options.noiseReduction ?? true;
    this.autoGain = options.autoGain ?? true;
  }

  public async initialize(): Promise<void> {
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('您的浏览器不支持音频采集功能');
      }

      const constraints: MediaStreamConstraints = {
        audio: {
          echoCancellation: true,
          noiseSuppression: this.noiseReduction,
          autoGainControl: this.autoGain,
        },
      };

      this.mediaStream = await navigator.mediaDevices.getUserMedia(constraints);

      const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      
      this.audioContext = new AudioContextClass({
        sampleRate: this.sampleRate,
      });

      this.sourceNode = this.audioContext.createMediaStreamSource(this.mediaStream);
      this.analyserNode = this.audioContext.createAnalyser();
      this.analyserNode.fftSize = 2048;

      this.gainNode = this.audioContext.createGain();
      this.gainNode.gain.value = 1.0;

      this.processorNode = this.audioContext.createScriptProcessor(
        this.bufferSize,
        this.channels,
        this.channels
      );

      this.sourceNode
        .connect(this.analyserNode)
        .connect(this.gainNode)
        .connect(this.processorNode)
        .connect(this.audioContext.destination);
    } catch (error) {
      console.error('Failed to initialize audio recorder:', error);
      const err = error instanceof Error ? error : new Error('无法访问麦克风');
      this.errorCallbacks.forEach(callback => callback(err));
      throw err;
    }
  }

  public start(): void {
    if (this.isRecording) return;

    if (!this.audioContext || !this.processorNode) {
      throw new Error('Audio recorder not initialized');
    }

    if (this.audioContext.state === 'suspended') {
      this.audioContext.resume();
    }

    this.processorNode.onaudioprocess = (event) => {
      const inputBuffer = event.inputBuffer;
      const outputBuffer = event.outputBuffer;

      for (let channel = 0; channel < inputBuffer.numberOfChannels; channel++) {
        const inputData = inputBuffer.getChannelData(channel);
        const outputData = outputBuffer.getChannelData(channel);

        for (let i = 0; i < inputBuffer.length; i++) {
          outputData[i] = inputData[i];
        }

        const chunk: AudioChunk = {
          data: new Float32Array(inputData),
          sampleRate: this.sampleRate,
          timestamp: Date.now(),
        };

        this.audioCallbacks.forEach(callback => callback(chunk));
      }
    };

    this.isRecording = true;
  }

  public stop(): void {
    if (!this.isRecording) return;

    this.isRecording = false;

    if (this.processorNode) {
      this.processorNode.onaudioprocess = null;
    }
  }

  public dispose(): void {
    this.stop();

    if (this.sourceNode) {
      this.sourceNode.disconnect();
      this.sourceNode = null;
    }

    if (this.analyserNode) {
      this.analyserNode.disconnect();
      this.analyserNode = null;
    }

    if (this.gainNode) {
      this.gainNode.disconnect();
      this.gainNode = null;
    }

    if (this.processorNode) {
      this.processorNode.disconnect();
      this.processorNode = null;
    }

    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop());
      this.mediaStream = null;
    }

    if (this.audioContext) {
      this.audioContext.close();
      this.audioContext = null;
    }

    this.audioCallbacks.clear();
    this.errorCallbacks.clear();
  }

  public onAudioData(callback: AudioCallback): () => void {
    this.audioCallbacks.add(callback);
    return () => this.audioCallbacks.delete(callback);
  }

  public onError(callback: ErrorCallback): () => void {
    this.errorCallbacks.add(callback);
    return () => this.errorCallbacks.delete(callback);
  }

  public getIsRecording(): boolean {
    return this.isRecording;
  }

  public getVolumeLevel(): number {
    if (!this.analyserNode) return 0;

    const dataArray = new Uint8Array(this.analyserNode.frequencyBinCount);
    this.analyserNode.getByteTimeDomainData(dataArray);

    let sum = 0;
    for (let i = 0; i < dataArray.length; i++) {
      const value = (dataArray[i] - 128) / 128;
      sum += value * value;
    }

    return Math.sqrt(sum / dataArray.length);
  }

  public getSampleRate(): number {
    return this.sampleRate;
  }

  public getChannels(): number {
    return this.channels;
  }
}

export const audioRecorderService = new AudioRecorderService();
