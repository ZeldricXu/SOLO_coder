import axios, { AxiosInstance } from 'axios';
import { AIConfig, IPCResponse } from '../../shared/types';
import { SecureStorageService } from './secure-storage';

export interface SummaryResult {
  summary: string;
  keywords: string[];
}

export interface AIServiceConfig {
  api_url: string;
  api_key: string;
  model: string;
  max_tokens: number;
}

export class AIService {
  private static instance: AIService;
  private apiClient: AxiosInstance | null = null;
  private config: AIServiceConfig | null = null;
  private secureStorage: SecureStorageService | null = null;
  private summaryCache: Map<string, SummaryResult> = new Map();
  private lastCallTime: Map<string, number> = new Map();
  private rateLimitPerMinute: number = 5;
  private isInitialized: boolean = false;

  private constructor() {}

  public static getInstance(): AIService {
    if (!AIService.instance) {
      AIService.instance = new AIService();
    }
    return AIService.instance;
  }

  public async initialize(): Promise<void> {
    this.secureStorage = SecureStorageService.getInstance();
    this.summaryCache.clear();
    this.lastCallTime.clear();
    
    const savedConfig = await this.secureStorage.getAIConfig();
    if (savedConfig) {
      this.configure({
        api_url: savedConfig.api_url,
        api_key: savedConfig.api_key,
        model: savedConfig.model || 'gpt-3.5-turbo',
        max_tokens: savedConfig.max_tokens || 500,
      });
    }
    
    this.isInitialized = true;
    console.log('AIService initialized');
  }

  public async configure(config: AIServiceConfig): Promise<boolean> {
    if (!this.secureStorage) {
      this.secureStorage = SecureStorageService.getInstance();
    }

    this.config = config;
    
    this.apiClient = axios.create({
      baseURL: config.api_url,
      timeout: 60000,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.api_key}`,
      },
    });

    try {
      await this.secureStorage.storeAIConfig({
        api_url: config.api_url,
        api_key: config.api_key,
        model: config.model,
        max_tokens: config.max_tokens,
      });
      return true;
    } catch (error) {
      console.error('Failed to save AI config to secure storage:', error);
      return false;
    }
  }

  public getConfig(): AIConfig | null {
    if (!this.config) return null;
    return {
      api_url: this.config.api_url,
      api_key: '********',
      model: this.config.model,
      max_tokens: this.config.max_tokens,
    };
  }

  public getRawConfig(): AIServiceConfig | null {
    return this.config;
  }

  public async clearConfig(): Promise<boolean> {
    if (!this.secureStorage) {
      this.secureStorage = SecureStorageService.getInstance();
    }

    this.config = null;
    this.apiClient = null;
    
    try {
      await this.secureStorage.deleteAIConfig();
      return true;
    } catch (error) {
      console.error('Failed to clear AI config:', error);
      return false;
    }
  }

  public async generateSummary(content: string): Promise<IPCResponse<SummaryResult>> {
    if (!this.isInitialized) {
      return { success: false, error: 'AI service not initialized' };
    }

    if (!this.config || !this.apiClient) {
      return { success: false, error: 'AI service not configured. Please set your API key in settings.' };
    }

    if (!content || content.trim().length === 0) {
      return { success: false, error: 'Content is empty' };
    }

    const contentHash = this.hashContent(content);

    if (this.summaryCache.has(contentHash)) {
      return { success: true, data: this.summaryCache.get(contentHash)! };
    }

    if (!this.checkRateLimit()) {
      return { success: false, error: 'Rate limit exceeded. Please try again later.' };
    }

    const truncatedContent = this.truncateContent(content);

    try {
      const result = await this.callAIAPI(truncatedContent);

      this.summaryCache.set(contentHash, result);

      return { success: true, data: result };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      if (errorMessage.includes('401') || errorMessage.includes('Unauthorized')) {
        return { success: false, error: 'API key invalid or expired. Please check your AI settings.' };
      }
      if (errorMessage.includes('429')) {
        return { success: false, error: 'Rate limit exceeded by API provider. Please try again later.' };
      }
      return { success: false, error: errorMessage };
    }
  }

  private async callAIAPI(content: string): Promise<SummaryResult> {
    if (!this.apiClient || !this.config) {
      throw new Error('API client not initialized');
    }

    const prompt = this.buildPrompt(content);

    const requestBody = {
      model: this.config.model,
      messages: [
        {
          role: 'system',
          content: '你是一个专业的文本摘要助手。请根据用户提供的文本内容生成简洁的摘要和相关关键词。',
        },
        {
          role: 'user',
          content: prompt,
        },
      ],
      max_tokens: this.config.max_tokens,
      temperature: 0.3,
    };

    const response = await this.apiClient.post('/v1/chat/completions', requestBody);

    if (!response.data || !response.data.choices || response.data.choices.length === 0) {
      throw new Error('Invalid response from AI API');
    }

    const aiResponse = response.data.choices[0].message?.content;

    if (!aiResponse) {
      throw new Error('Empty response from AI API');
    }

    return this.parseAIResponse(aiResponse, content);
  }

  private buildPrompt(content: string): string {
    return `请为以下文本生成一个简洁的摘要（50-200字），并提取3-5个关键词。

文本内容：
${content}

请严格按照以下JSON格式输出结果，不要有其他额外内容：
{
  "summary": "摘要内容",
  "keywords": ["关键词1", "关键词2", "关键词3"]
}`;
  }

  private parseAIResponse(response: string, originalContent: string): SummaryResult {
    try {
      const jsonMatch = response.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        const parsed = JSON.parse(jsonMatch[0]);
        return {
          summary: parsed.summary || this.generateFallbackSummary(originalContent),
          keywords: Array.isArray(parsed.keywords) ? parsed.keywords : [],
        };
      }
    } catch {
      // Continue to fallback
    }

    return {
      summary: this.generateFallbackSummary(originalContent),
      keywords: [],
    };
  }

  private generateFallbackSummary(content: string): string {
    const plainText = content
      .replace(/^#{1,6}\s+/gm, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/\*([^*]+)\*/g, '$1')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/\n{2,}/g, '. ')
      .replace(/\n/g, ' ')
      .trim();

    const sentences = plainText.split(/[。！？.!?]/).filter(s => s.trim().length > 0);
    
    if (sentences.length === 0) {
      return plainText.substring(0, 150) + (plainText.length > 150 ? '...' : '');
    }

    let summary = '';
    for (const sentence of sentences) {
      if ((summary + sentence).length > 150) {
        break;
      }
      summary += sentence + '。';
    }

    return summary || sentences[0] + '。';
  }

  private hashContent(content: string): string {
    let hash = 0;
    for (let i = 0; i < content.length; i++) {
      const char = content.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return hash.toString(36);
  }

  private truncateContent(content: string): string {
    const maxLength = 8000;
    if (content.length <= maxLength) {
      return content;
    }
    
    return content.substring(0, maxLength) + '\n\n[内容已截断...]';
  }

  private checkRateLimit(): boolean {
    const now = Date.now();
    const oneMinuteAgo = now - 60000;

    let recentCalls = 0;
    for (const time of this.lastCallTime.values()) {
      if (time > oneMinuteAgo) {
        recentCalls++;
      }
    }

    if (recentCalls >= this.rateLimitPerMinute) {
      return false;
    }

    const callId = now.toString();
    this.lastCallTime.set(callId, now);

    if (this.lastCallTime.size > 20) {
      const oldestCalls = Array.from(this.lastCallTime.entries())
        .sort((a, b) => a[1] - b[1])
        .slice(0, this.lastCallTime.size - 10);
      
      for (const [id] of oldestCalls) {
        this.lastCallTime.delete(id);
      }
    }

    return true;
  }

  public clearCache(): void {
    this.summaryCache.clear();
  }

  public isConfigured(): boolean {
    return this.config !== null && this.apiClient !== null;
  }
}
