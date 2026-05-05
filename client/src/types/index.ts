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

export interface SessionState {
  sessionId: string;
  isActive: boolean;
  startTime: number;
  segments: Segment[];
  config: {
    audioLanguage: string;
    targetLanguage?: string;
    enableTranslation: boolean;
  };
}

export interface HistoryRecord {
  transcribe_id: string;
  session_id: string;
  duration: number;
  segment_count: number;
  audio_language: string;
  target_language?: string;
  created_at: string;
}

export type AudioLanguage = 'auto' | 'zh-CN' | 'en-US' | 'ja-JP';
export type TargetLanguage = 'zh-CN' | 'en-US' | 'ja-JP';

export interface AppSettings {
  audioLanguage: AudioLanguage;
  targetLanguage: TargetLanguage;
  enableTranslation: boolean;
  noiseReduction: boolean;
  autoGain: boolean;
}

export const DEFAULT_SETTINGS: AppSettings = {
  audioLanguage: 'auto',
  targetLanguage: 'en-US',
  enableTranslation: false,
  noiseReduction: true,
  autoGain: true,
};

export const AUDIO_LANGUAGES: { value: AudioLanguage; label: string }[] = [
  { value: 'auto', label: '自动检测' },
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: '英语' },
  { value: 'ja-JP', label: '日语' },
];

export const TARGET_LANGUAGES: { value: TargetLanguage; label: string }[] = [
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: '英语' },
  { value: 'ja-JP', label: '日语' },
];
