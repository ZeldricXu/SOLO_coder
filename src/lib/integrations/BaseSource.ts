import {
  KnowledgeSource,
  KnowledgeDocument,
  FetchOptions,
  FetchResult,
  SourceType,
  RetryConfig,
  DEFAULT_RETRY_CONFIG,
  SyncError,
} from './types';

export abstract class BaseSource {
  protected readonly source: KnowledgeSource;
  protected readonly retryConfig: RetryConfig;

  constructor(source: KnowledgeSource, retryConfig?: Partial<RetryConfig>) {
    this.source = source;
    this.retryConfig = { ...DEFAULT_RETRY_CONFIG, ...retryConfig };
  }

  abstract get sourceType(): SourceType;

  abstract fetchDocuments(
    options?: FetchOptions
  ): Promise<FetchResult<KnowledgeDocument>>;

  abstract fetchIncremental(
    since: Date,
    options?: FetchOptions
  ): Promise<FetchResult<KnowledgeDocument>>;

  abstract fetchSingleDocument(
    externalId: string
  ): Promise<KnowledgeDocument | null>;

  abstract normalizeContent(
    content: string,
    metadata?: Record<string, unknown>
  ): Promise<{
    markdown: string;
    headings: Array<{ level: number; text: string; id?: string; children: unknown[] }>;
    internalLinks: string[];
  }>;

  abstract validateConfig(): Promise<boolean>;

  protected async withRetry<T>(
    operation: () => Promise<T>,
    context: Record<string, unknown> = {}
  ): Promise<T> {
    let lastError: Error | null = null;
    for (let attempt = 1; attempt <= this.retryConfig.maxRetries; attempt++) {
      try {
        const signal = AbortSignal.timeout(this.retryConfig.timeoutMs);
        const result = await Promise.race([
          operation(),
          new Promise<never>((_, reject) => {
            signal.addEventListener('abort', () => {
              reject(new Error('Operation timed out'));
            });
          }),
        ]);
        return result;
      } catch (error) {
          lastError = error as Error;
          if (attempt < this.retryConfig.maxRetries) {
            const delay = this.retryConfig.delayMs * Math.pow(this.retryConfig.backoffFactor, attempt - 1);
            await this.delay(delay);
          }
        }
    }
    throw lastError || new Error('Operation failed after retries');
  }

  protected delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  protected createSyncError(
    message: string,
    code: string,
    externalId?: string,
    details?: Record<string, unknown>
  ): SyncError {
    return {
      externalId,
      message,
      code,
      details,
      timestamp: new Date(),
    };
  }

  protected buildDocument(
    externalId: string,
    title: string,
    content: string,
    metadata: Record<string, unknown> = {},
    url?: string,
    lastModifiedAt?: Date
  ): KnowledgeDocument {
    return {
      externalId,
      sourceId: this.source.id,
      sourceType: this.sourceType,
      title,
      content,
      metadata,
      tags: [],
      internalLinks: [],
      headings: [],
      url,
      lastModifiedAt: lastModifiedAt || new Date(),
      lastSyncedAt: new Date(),
    };
  }

  protected isAbortError(error: unknown): boolean {
    return error instanceof Error && error.name === 'AbortError';
  }

  protected isRateLimitError(error: unknown): boolean {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      return (
        message.includes('rate limit') ||
        message.includes('too many requests') ||
        message.includes('429')
      );
    }
    return false;
  }

  protected async handleApiError(
    error: unknown,
    context: Record<string, unknown> = {}
  ): Promise<never> {
    if (this.isAbortError(error)) {
      throw new Error(`Request timeout: ${(error as Error).message}`);
    }
    if (this.isRateLimitError(error)) {
      await this.delay(this.retryConfig.delayMs * 2);
    }
    throw error;
  }

  async testConnection(): Promise<{ success: boolean; error?: string }> {
    try {
      const isValid = await this.validateConfig();
      if (!isValid) {
        return { success: false, error: 'Invalid configuration' };
      }
      const result = await this.fetchDocuments({ limit: 1 });
      return { success: result.data.length >= 0 };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }
}
