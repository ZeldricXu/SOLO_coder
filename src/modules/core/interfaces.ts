import { TransformRule, StandardizationConfig, ProcessingConfig, CacheConfig, CacheStats, WarmupResult } from './types';
import { HandlerContext, ProcessingResult, RunInstance } from '../../types';

export interface ITransformer {
  applyRule(item: any, rule: TransformRule): any;
  applyRules(item: any, rules: TransformRule[]): any;
  applyStandardization(item: any, config: StandardizationConfig): any;
}

export interface ICacheService {
  get(key: string): any | undefined;
  set(key: string, value: any, hash?: string): void;
  has(key: string): boolean;
  delete(key: string): boolean;
  clear(): void;
  invalidate(pattern?: string): number;
  getStats(): CacheStats;
  getEntries(): Array<{ key: string; entry: any }>;
}

export interface IConfigLoader {
  loadConfig(namespace: string): ProcessingConfig;
  getCacheConfig(): CacheConfig;
}

export interface IPayloadHasher {
  computeHash(payload: any): string;
  buildCacheKey(namespace: string, identifier: string): string;
}

export interface IProcessor {
  processSingleItem(
    item: any,
    config: ProcessingConfig,
    cache?: ICacheService,
  ): Promise<any>;
  processCore(
    payload: any,
    config: ProcessingConfig,
    runInstance: RunInstance,
    cache?: ICacheService,
  ): Promise<any>;
}
