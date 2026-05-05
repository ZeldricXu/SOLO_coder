import { AudioChunk } from './audioRecorder';

export interface AudioBufferOptions {
  chunkDuration?: number;
  overlapDuration?: number;
  sampleRate?: number;
}

type BufferCallback = (base64Data: string, sequence: number, metadata: {
  isFirstChunk: boolean;
  overlapSamples: number;
  totalSamples: number;
}) => void;

const DEFAULT_CHUNK_DURATION = 2.0;
const DEFAULT_OVERLAP_DURATION = 0.5;
const DEFAULT_SAMPLE_RATE = 16000;

export class AudioBufferService {
  private buffer: Float32Array[] = [];
  private totalSamples = 0;
  private chunkDuration: number;
  private overlapDuration: number;
  private sampleRate: number;
  private sequence = 0;
  private samplesPerChunk: number;
  private samplesPerOverlap: number;
  private samplesPerStep: number;
  private isFirstChunk = true;

  private bufferCallbacks: Set<BufferCallback> = new Set();

  constructor(options: AudioBufferOptions = {}) {
    this.chunkDuration = options.chunkDuration ?? DEFAULT_CHUNK_DURATION;
    this.overlapDuration = options.overlapDuration ?? DEFAULT_OVERLAP_DURATION;
    this.sampleRate = options.sampleRate ?? DEFAULT_SAMPLE_RATE;
    
    this.samplesPerChunk = Math.floor(this.chunkDuration * this.sampleRate);
    this.samplesPerOverlap = Math.floor(this.overlapDuration * this.sampleRate);
    this.samplesPerStep = this.samplesPerChunk - this.samplesPerOverlap;

    if (this.samplesPerStep <= 0) {
      throw new Error('Overlap duration cannot be greater than or equal to chunk duration');
    }
  }

  public addChunk(audioChunk: AudioChunk): void {
    this.buffer.push(audioChunk.data);
    this.totalSamples += audioChunk.data.length;

    while (this.totalSamples >= this.samplesPerChunk) {
      const combinedData = this.combineBuffer();
      
      const chunkData = combinedData.slice(0, this.samplesPerChunk);
      const remainingData = combinedData.slice(this.samplesPerStep);

      const base64Data = this.float32ToBase64(chunkData);
      this.sequence++;

      this.bufferCallbacks.forEach(callback => 
        callback(base64Data, this.sequence, {
          isFirstChunk: this.isFirstChunk,
          overlapSamples: this.isFirstChunk ? 0 : this.samplesPerOverlap,
          totalSamples: chunkData.length,
        })
      );

      this.isFirstChunk = false;

      if (remainingData.length > 0) {
        this.buffer = [remainingData];
        this.totalSamples = remainingData.length;
      } else {
        this.buffer = [];
        this.totalSamples = 0;
      }
    }
  }

  private combineBuffer(): Float32Array {
    if (this.buffer.length === 0) {
      return new Float32Array();
    }

    const result = new Float32Array(this.totalSamples);
    let offset = 0;

    for (const chunk of this.buffer) {
      result.set(chunk, offset);
      offset += chunk.length;
    }

    return result;
  }

  private float32ToBase64(data: Float32Array): string {
    const int16Data = this.float32ToInt16(data);
    const uint8Data = new Uint8Array(int16Data.buffer);
    return this.uint8ToBase64(uint8Data);
  }

  private float32ToInt16(data: Float32Array): Int16Array {
    const result = new Int16Array(data.length);
    for (let i = 0; i < data.length; i++) {
      const sample = data[i] * 32767;
      result[i] = Math.max(-32768, Math.min(32767, Math.round(sample)));
    }
    return result;
  }

  private uint8ToBase64(data: Uint8Array): string {
    let binary = '';
    const bytes = new Uint8Array(data);
    const len = bytes.byteLength;
    for (let i = 0; i < len; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
  }

  public flush(): void {
    if (this.totalSamples > 0) {
      const combinedData = this.combineBuffer();
      const base64Data = this.float32ToBase64(combinedData);
      this.sequence++;
      this.bufferCallbacks.forEach(callback => 
        callback(base64Data, this.sequence, {
          isFirstChunk: this.isFirstChunk,
          overlapSamples: this.isFirstChunk ? 0 : this.samplesPerOverlap,
          totalSamples: combinedData.length,
        })
      );
    }

    this.clear();
  }

  public clear(): void {
    this.buffer = [];
    this.totalSamples = 0;
  }

  public reset(): void {
    this.clear();
    this.sequence = 0;
    this.isFirstChunk = true;
  }

  public onBufferReady(callback: BufferCallback): () => void {
    this.bufferCallbacks.add(callback);
    return () => this.bufferCallbacks.delete(callback);
  }

  public getSequence(): number {
    return this.sequence;
  }

  public getBufferSize(): number {
    return this.totalSamples;
  }

  public getSampleRate(): number {
    return this.sampleRate;
  }

  public getChunkDuration(): number {
    return this.chunkDuration;
  }

  public getOverlapDuration(): number {
    return this.overlapDuration;
  }

  public getSamplesPerOverlap(): number {
    return this.samplesPerOverlap;
  }
}

export const audioBufferService = new AudioBufferService();
