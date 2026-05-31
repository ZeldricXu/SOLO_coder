import logger from '../../utils/logger';
import { sleep } from '../../utils/helpers';
import { ProcessingConfig } from './types';
import { ITransformer, ICacheService, IProcessor } from './interfaces';
import { RunInstance } from '../../types';
import { Transformer } from './Transformer';

export class Processor implements IProcessor {
  private transformer: ITransformer;
  private hashCache: Map<string, string> = new Map();

  constructor(transformer?: ITransformer) {
    this.transformer = transformer || new Transformer();
  }

  async processSingleItem(
    item: any,
    config: ProcessingConfig,
    cache?: ICacheService,
  ): Promise<any> {
    if (cache) {
      const itemHash = this.computeItemHash(item);
      const cacheKey = `item:${itemHash}`;
      const cached = cache.get(cacheKey);
      if (cached) {
        return cached;
      }

      const result = this.processItemSync(item, config);
      cache.set(cacheKey, result, itemHash);
      return result;
    }

    return this.processItemSync(item, config);
  }

  private processItemSync(item: any, config: ProcessingConfig): any {
    const transformed = this.transformer.applyRules(item, config.rules);
    return this.transformer.applyStandardization(transformed, config.standardization);
  }

  private computeItemHash(item: any): string {
    const str = typeof item === 'string' ? item : JSON.stringify(item);
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return String(hash);
  }

  async processCore(
    payload: any,
    config: ProcessingConfig,
    runInstance: RunInstance,
    cache?: ICacheService,
  ): Promise<any> {
    if (Array.isArray(payload)) {
      return this.processBatch(payload, config, runInstance, cache);
    }
    return this.processSingleItem(payload, config, cache);
  }

  private async processBatch(
    payload: any[],
    config: ProcessingConfig,
    runInstance: RunInstance,
    cache?: ICacheService,
  ): Promise<any[]> {
    const results: any[] = [];
    const batchSize = config.batchSize;
    
    for (let i = 0; i < payload.length; i += batchSize) {
      const batch = payload.slice(i, i + batchSize);
      const batchResults = await this.processBatchChunk(batch, config, cache);
      results.push(...batchResults);
      
      runInstance.progress = Math.min(1, (i + batchSize) / payload.length);
      await sleep(10);
    }
    
    return results;
  }

  private async processBatchChunk(
    batch: any[],
    config: ProcessingConfig,
    cache?: ICacheService,
  ): Promise<any[]> {
    const concurrency = config.concurrency || 5;
    const results: any[] = new Array(batch.length);
    let index = 0;

    async function processNext(): Promise<void> {
      while (index < batch.length) {
        const currentIndex = index++;
        results[currentIndex] = await this.processSingleItem(batch[currentIndex], config, cache);
      }
    }

    const workers = Array.from({ length: concurrency }, () => processNext.call(this));
    await Promise.all(workers);

    return results;
  }
}
