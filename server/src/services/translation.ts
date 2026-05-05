import axios from 'axios';
import { config } from 'dotenv';
import { TranslationCache } from '../types';

config();

const LIBRETRANSLATE_URL = process.env.LIBRETRANSLATE_URL || 'http://localhost:5000';
const USE_MOCK_TRANSLATION = process.env.USE_MOCK_TRANSLATION !== 'false';
const CACHE_TTL = 5 * 60 * 1000;

export interface TranslationResult {
  translatedText: string;
  sourceLanguage: string;
  targetLanguage: string;
  fromCache: boolean;
}

export class TranslationService {
  private static cache: TranslationCache = {};

  private static mockTranslations: Record<string, Record<string, string>> = {
    '大家好，今天我们来讨论项目进度。': {
      'en': 'Hello everyone, let\'s discuss the project progress today.',
    },
    '关于这个功能，我们需要更多的测试。': {
      'en': 'Regarding this feature, we need more testing.',
    },
    '下周三我们将进行代码审查。': {
      'en': 'We will conduct a code review next Wednesday.',
    },
    '请各位准备好相关的文档。': {
      'en': 'Please prepare the relevant documents.',
    },
    '这个模块的性能需要优化。': {
      'en': 'The performance of this module needs optimization.',
    },
    'Hello everyone, let\'s discuss the project progress today.': {
      'zh': '大家好，今天我们来讨论项目进度。',
    },
    'Regarding this feature, we need more testing.': {
      'zh': '关于这个功能，我们需要更多的测试。',
    },
    'We will conduct a code review next Wednesday.': {
      'zh': '下周三我们将进行代码审查。',
    },
    'Please prepare the relevant documents.': {
      'zh': '请各位准备好相关的文档。',
    },
    'The performance of this module needs optimization.': {
      'zh': '这个模块的性能需要优化。',
    },
  };

  static async translate(
    text: string,
    targetLanguage: string,
    sourceLanguage: string = 'auto'
  ): Promise<TranslationResult> {
    const normalizedSource = this.normalizeLanguageCode(sourceLanguage);
    const normalizedTarget = this.normalizeLanguageCode(targetLanguage);

    if (normalizedSource === normalizedTarget) {
      return {
        translatedText: text,
        sourceLanguage: normalizedSource,
        targetLanguage: normalizedTarget,
        fromCache: false,
      };
    }

    const cacheKey = this.generateCacheKey(text, normalizedSource, normalizedTarget);
    const cached = this.cache[cacheKey];

    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return {
        translatedText: cached.text,
        sourceLanguage: normalizedSource,
        targetLanguage: normalizedTarget,
        fromCache: true,
      };
    }

    let result: TranslationResult;

    if (USE_MOCK_TRANSLATION) {
      result = this.mockTranslate(text, normalizedSource, normalizedTarget);
    } else {
      result = await this.translateWithLibreTranslate(text, normalizedSource, normalizedTarget);
    }

    this.cache[cacheKey] = {
      text: result.translatedText,
      timestamp: Date.now(),
    };

    this.cleanupCache();

    return result;
  }

  private static async translateWithLibreTranslate(
    text: string,
    sourceLanguage: string,
    targetLanguage: string
  ): Promise<TranslationResult> {
    try {
      const response = await axios.post(`${LIBRETRANSLATE_URL}/translate`, {
        q: text,
        source: sourceLanguage,
        target: targetLanguage,
        format: 'text',
      });

      return {
        translatedText: response.data.translatedText || text,
        sourceLanguage: response.data.detectedLanguage?.language || sourceLanguage,
        targetLanguage,
        fromCache: false,
      };
    } catch (error) {
      console.error('LibreTranslate API error:', error);
      return this.mockTranslate(text, sourceLanguage, targetLanguage);
    }
  }

  private static mockTranslate(
    text: string,
    sourceLanguage: string,
    targetLanguage: string
  ): TranslationResult {
    const targetLang = targetLanguage.split('-')[0];
    const mockTranslation = this.mockTranslations[text]?.[targetLang];

    if (mockTranslation) {
      return {
        translatedText: mockTranslation,
        sourceLanguage,
        targetLanguage,
        fromCache: false,
      };
    }

    const isChinese = /[\u4e00-\u9fa5]/.test(text);
    const isTargetChinese = targetLang === 'zh';

    let translatedText: string;

    if (isChinese && isTargetChinese) {
      translatedText = text;
    } else if (isChinese) {
      translatedText = `[Translated] ${this.pinyinize(text)}`;
    } else if (isTargetChinese) {
      translatedText = `[翻译] ${text}`;
    } else {
      translatedText = text;
    }

    return {
      translatedText,
      sourceLanguage,
      targetLanguage,
      fromCache: false,
    };
  }

  private static pinyinize(text: string): string {
    return text.split('').map(char => {
      if (/[\u4e00-\u9fa5]/.test(char)) {
        return '*';
      }
      return char;
    }).join('');
  }

  private static normalizeLanguageCode(lang: string): string {
    const mapping: Record<string, string> = {
      'zh-CN': 'zh',
      'zh-TW': 'zh',
      'en-US': 'en',
      'en-GB': 'en',
      'ja-JP': 'ja',
      'auto': 'auto',
    };
    return mapping[lang] || lang.split('-')[0];
  }

  private static generateCacheKey(
    text: string,
    sourceLang: string,
    targetLang: string
  ): string {
    return `${sourceLang}:${targetLang}:${text}`;
  }

  private static cleanupCache(): void {
    const now = Date.now();
    const keys = Object.keys(this.cache);

    for (const key of keys) {
      if (now - this.cache[key].timestamp > CACHE_TTL) {
        delete this.cache[key];
      }
    }
  }

  static clearCache(): void {
    this.cache = {};
  }
}
