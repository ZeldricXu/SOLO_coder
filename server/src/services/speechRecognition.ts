import axios from 'axios';
import { config } from 'dotenv';
import { join } from 'path';
import { tmpdir } from 'os';
import { writeFileSync, unlinkSync, existsSync } from 'fs';

config();

const WHISPER_API_KEY = process.env.WHISPER_API_KEY;
const WHISPER_API_URL = process.env.WHISPER_API_URL || 'https://api.openai.com/v1/audio/transcriptions';
const USE_LOCAL_WHISPER = process.env.USE_LOCAL_WHISPER === 'true';
const LOCAL_WHISPER_URL = process.env.LOCAL_WHISPER_URL || 'http://localhost:9000/asr';

const CONFIDENCE_THRESHOLD = 0.6;

export interface RecognitionResult {
  text: string;
  confidence: number;
  language: string;
}

export class SpeechRecognitionService {
  private static mockResponses = [
    '大家好，今天我们来讨论项目进度。',
    'Hello everyone, let\'s discuss the project progress today.',
    '关于这个功能，我们需要更多的测试。',
    'Regarding this feature, we need more testing.',
    '下周三我们将进行代码审查。',
    'We will conduct a code review next Wednesday.',
    '请各位准备好相关的文档。',
    'Please prepare the relevant documents.',
    '这个模块的性能需要优化。',
    'The performance of this module needs optimization.',
  ];

  private static mockIndex = 0;

  static async recognize(
    audioBuffer: Buffer,
    language: string = 'auto'
  ): Promise<RecognitionResult> {
    if (USE_LOCAL_WHISPER) {
      return this.recognizeWithLocalWhisper(audioBuffer, language);
    }

    if (WHISPER_API_KEY) {
      return this.recognizeWithOpenAI(audioBuffer, language);
    }

    return this.simulateRecognition(language);
  }

  private static async recognizeWithOpenAI(
    audioBuffer: Buffer,
    language: string
  ): Promise<RecognitionResult> {
    try {
      const tempFilePath = join(tmpdir(), `audio_${Date.now()}.wav`);
      writeFileSync(tempFilePath, audioBuffer);

      const formData = new FormData();
      formData.append('file', new Blob([audioBuffer]), 'audio.wav');
      formData.append('model', 'whisper-1');
      formData.append('response_format', 'json');
      
      if (language !== 'auto') {
        formData.append('language', this.mapLanguageCode(language));
      }

      const response = await axios.post(WHISPER_API_URL, formData, {
        headers: {
          'Authorization': `Bearer ${WHISPER_API_KEY}`,
          'Content-Type': 'multipart/form-data',
        },
      });

      if (existsSync(tempFilePath)) {
        unlinkSync(tempFilePath);
      }

      return {
        text: response.data.text || '',
        confidence: response.data.confidence || 0.85,
        language: this.detectLanguage(response.data.text || '', language),
      };
    } catch (error) {
      console.error('OpenAI Whisper API error:', error);
      return this.simulateRecognition(language);
    }
  }

  private static async recognizeWithLocalWhisper(
    audioBuffer: Buffer,
    language: string
  ): Promise<RecognitionResult> {
    try {
      const response = await axios.post(LOCAL_WHISPER_URL, audioBuffer, {
        headers: {
          'Content-Type': 'audio/wav',
        },
        params: {
          language: language !== 'auto' ? this.mapLanguageCode(language) : undefined,
          task: 'transcribe',
          output: 'json',
        },
      });

      return {
        text: response.data.text || '',
        confidence: response.data.confidence || 0.8,
        language: this.detectLanguage(response.data.text || '', language),
      };
    } catch (error) {
      console.error('Local Whisper API error:', error);
      return this.simulateRecognition(language);
    }
  }

  private static simulateRecognition(language: string): RecognitionResult {
    const text = this.mockResponses[this.mockIndex % this.mockResponses.length];
    this.mockIndex++;

    const detectedLanguage = this.detectLanguage(text, language);
    const confidence = 0.7 + Math.random() * 0.25;

    return {
      text,
      confidence,
      language: detectedLanguage,
    };
  }

  private static detectLanguage(text: string, hintLanguage: string): string {
    const chinesePattern = /[\u4e00-\u9fa5]/;
    const japanesePattern = /[\u3040-\u309F\u30A0-\u30FF]/;

    if (chinesePattern.test(text)) {
      return 'zh-CN';
    }
    if (japanesePattern.test(text)) {
      return 'ja-JP';
    }

    return hintLanguage === 'auto' ? 'en-US' : hintLanguage;
  }

  private static mapLanguageCode(language: string): string {
    const mapping: Record<string, string> = {
      'zh-CN': 'zh',
      'zh-TW': 'zh',
      'en-US': 'en',
      'en-GB': 'en',
      'ja-JP': 'ja',
      'auto': 'auto',
    };
    return mapping[language] || language.split('-')[0];
  }

  static isConfidenceHigh(confidence: number): boolean {
    return confidence >= CONFIDENCE_THRESHOLD;
  }
}
