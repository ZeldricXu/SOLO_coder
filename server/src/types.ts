export interface AudioConfig {
  sampleRate: number;
  channels: number;
  bitDepth: number;
  chunkSize: number;
  noiseReduction: boolean;
  languageMode: string;
}

export interface Segment {
  segmentId: number;
  startTime: number;
  endTime: number;
  originalText: string;
  translatedText?: string;
  confidence: number;
  status: 'confirmed' | 'pending';
}

export interface TranscribeRecord {
  transcribeId: string;
  sessionId: string;
  audioLanguage: string;
  targetLanguage?: string;
  segments: Segment[];
  totalDuration: number;
  createdAt: string;
}

export interface WebSocketMessage {
  action: 'audio_chunk' | 'start_session' | 'end_session' | 'config_update';
  data: unknown;
}

export interface AudioChunkMessage {
  action: 'audio_chunk';
  data: {
    audioData: string;
    sequence: number;
    timestamp: number;
    metadata: {
      isFirstChunk: boolean;
      overlapSamples: number;
      totalSamples: number;
    };
  };
}

export interface TranslationUpdate {
  event: 'translation_update';
  data: {
    segmentId: number;
    translatedText: string;
  };
}

export interface ChunkAck {
  event: 'chunk_ack';
  data: {
    sequence: number;
    received: boolean;
  };
}

export interface PendingTranslation {
  segmentId: number;
  originalText: string;
  targetLanguage: string;
  sourceLanguage?: string;
}

export interface ChunkMetadata {
  sequence: number;
  isFirstChunk: boolean;
  overlapSamples: number;
  totalSamples: number;
  receivedTime: number;
}

export interface StartSessionMessage {
  action: 'start_session';
  data: {
    sessionId: string;
    audioLanguage: string;
    targetLanguage?: string;
    enableTranslation: boolean;
  };
}

export interface EndSessionMessage {
  action: 'end_session';
  data: {
    sessionId: string;
  };
}

export interface TranscribeResult {
  event: 'transcribe_result';
  data: {
    segmentId: number;
    text: string;
    translatedText?: string;
    confidence: number;
    status: 'confirmed' | 'pending';
    startTime: number;
    endTime: number;
  };
}

export interface TranslationCache {
  [key: string]: {
    text: string;
    timestamp: number;
  };
}

export interface SessionState {
  sessionId: string;
  audioLanguage: string;
  targetLanguage?: string;
  enableTranslation: boolean;
  startTime: number;
  segments: Segment[];
  isActive: boolean;
}

export interface DatabaseRecord {
  transcribeId: string;
  sessionId: string;
  audioLanguage: string;
  targetLanguage: string | null;
  segments: string;
  totalDuration: number;
  createdAt: string;
}
