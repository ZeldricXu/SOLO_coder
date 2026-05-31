import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type { DecentralizedStoragePort, StorageProvider, StorageConfig } from '@core/ports/storage.port';
import type { StorageContent, PinStatus } from '@core/domain/blockchain';
import { NotFoundError, ConflictError, ValidationError } from '@shared/errors';
import { z } from 'zod';

export class DecentralizedStorageService implements DecentralizedStoragePort {
  private providers: Map<string, DecentralizedStoragePort> = new Map();
  private defaultProvider: string;

  constructor(
    private readonly providerFactory: (config: { type: string; endpoint?: string; gateway?: string; apiKey?: string }) => DecentralizedStoragePort,
    private readonly logger: Logger,
    config: StorageConfig,
    private readonly cache?: CachePort
  ) {
    this.defaultProvider = config.defaultProvider;

    for (const providerConfig of config.providers) {
      const provider = this.providerFactory(providerConfig);
      this.providers.set(providerConfig.name, provider);
      this.logger.info('Registered storage provider', { name: providerConfig.name, type: providerConfig.type });
    }

    if (!this.providers.has(this.defaultProvider)) {
      throw new ConflictError(`Default provider ${this.defaultProvider} not found in configuration`);
    }
  }

  private getProvider(providerName?: string): DecentralizedStoragePort {
    const name = providerName || this.defaultProvider;
    const provider = this.providers.get(name);
    if (!provider) {
      throw new NotFoundError('StorageProvider', name);
    }
    return provider;
  }

  private validateContent(content: Uint8Array, maxSize = 10 * 1024 * 1024): void {
    const schema = z.instanceof(Uint8Array);
    const result = schema.safeParse(content);
    if (!result.success) {
      throw new ValidationError({ content: ['Content must be a Uint8Array'] });
    }
    if (content.length > maxSize) {
      throw new ValidationError({
        content: [`Content size ${content.length} exceeds maximum allowed size ${maxSize}`],
      });
    }
  }

  private validateCid(cid: string): void {
    const schema = z.string().min(2, 'CID cannot be empty');
    const result = schema.safeParse(cid);
    if (!result.success) {
      throw new ValidationError({ cid: result.error.issues.map(i => i.message) });
    }
  }

  private getCacheKey(cid: string): string {
    return `storage:content:${cid}`;
  }

  async upload(content: Uint8Array, contentType = 'application/octet-stream', providerName?: string): Promise<StorageContent> {
    this.validateContent(content);

    const provider = this.getProvider(providerName);
    this.logger.info('Uploading content to storage', {
      size: content.length,
      contentType,
      provider: providerName || this.defaultProvider,
    });

    const result = await provider.upload(content, contentType);

    if (this.cache) {
      await this.cache.set(this.getCacheKey(result.cid), content, 86400);
    }

    return result;
  }

  async uploadJSON<T = unknown>(data: T, providerName?: string): Promise<StorageContent> {
    const jsonString = JSON.stringify(data);
    const encoder = new TextEncoder();
    const content = encoder.encode(jsonString);

    this.logger.info('Uploading JSON to storage', {
      provider: providerName || this.defaultProvider,
    });

    return this.upload(content, 'application/json', providerName);
  }

  async download(cid: string, providerName?: string): Promise<Uint8Array> {
    this.validateCid(cid);

    const cacheKey = this.getCacheKey(cid);
    if (this.cache) {
      const cached = await this.cache.get<Uint8Array>(cacheKey);
      if (cached) {
        this.logger.debug('Returning cached content', { cid });
        return cached;
      }
    }

    const provider = this.getProvider(providerName);
    this.logger.info('Downloading content from storage', {
      cid,
      provider: providerName || this.defaultProvider,
    });

    const content = await provider.download(cid);

    if (this.cache) {
      await this.cache.set(cacheKey, content, 86400);
    }

    return content;
  }

  async downloadJSON<T = unknown>(cid: string, providerName?: string): Promise<T> {
    const content = await this.download(cid, providerName);
    const decoder = new TextDecoder();
    const jsonString = decoder.decode(content);

    try {
      return JSON.parse(jsonString) as T;
    } catch (error) {
      this.logger.error('Failed to parse JSON content', { cid, error });
      throw new ValidationError({ cid: ['Content is not valid JSON'] });
    }
  }

  async pin(cid: string, providerName?: string): Promise<PinStatus> {
    this.validateCid(cid);

    const provider = this.getProvider(providerName);
    this.logger.info('Pinning content', {
      cid,
      provider: providerName || this.defaultProvider,
    });

    return provider.pin(cid);
  }

  async unpin(cid: string, providerName?: string): Promise<boolean> {
    this.validateCid(cid);

    const provider = this.getProvider(providerName);
    this.logger.info('Unpinning content', {
      cid,
      provider: providerName || this.defaultProvider,
    });

    const result = await provider.unpin(cid);

    if (result && this.cache) {
      await this.cache.delete(this.getCacheKey(cid));
    }

    return result;
  }

  async getPinStatus(cid: string, providerName?: string): Promise<PinStatus | null> {
    this.validateCid(cid);

    const provider = this.getProvider(providerName);
    return provider.getPinStatus(cid);
  }

  async listPins(providerName?: string): Promise<PinStatus[]> {
    const provider = this.getProvider(providerName);
    return provider.listPins();
  }

  getGatewayUrl(cid: string, providerName?: string): string {
    const provider = this.getProvider(providerName);
    return provider.getGatewayUrl(cid);
  }

  addProvider(name: string, provider: DecentralizedStoragePort): void {
    if (this.providers.has(name)) {
      throw new ConflictError(`Storage provider ${name} already exists`);
    }
    this.providers.set(name, provider);
    this.logger.info('Added storage provider', { name });
  }

  removeProvider(name: string): void {
    if (name === this.defaultProvider) {
      throw new ConflictError('Cannot remove the default storage provider');
    }
    const deleted = this.providers.delete(name);
    if (deleted) {
      this.logger.info('Removed storage provider', { name });
    }
  }

  setDefaultProvider(name: string): void {
    if (!this.providers.has(name)) {
      throw new NotFoundError('StorageProvider', name);
    }
    this.defaultProvider = name;
    this.logger.info('Set default storage provider', { name });
  }

  getAvailableProviders(): string[] {
    return Array.from(this.providers.keys());
  }

  async replicate(
    cid: string,
    fromProvider: string,
    toProvider: string
  ): Promise<StorageContent> {
    this.logger.info('Replicating content between providers', {
      cid,
      fromProvider,
      toProvider,
    });

    const content = await this.download(cid, fromProvider);
    const contentType = 'application/octet-stream';

    return this.upload(content, contentType, toProvider);
  }

  async verifyContent(cid: string, content: Uint8Array, providerName?: string): Promise<boolean> {
    try {
      const storedContent = await this.download(cid, providerName);
      if (storedContent.length !== content.length) return false;
      for (let i = 0; i < content.length; i++) {
        if (storedContent[i] !== content[i]) return false;
      }
      return true;
    } catch (error) {
      this.logger.error('Error verifying content', { cid, error });
      return false;
    }
  }
}
