import { ContainerImage, ImageLayer } from '../types';
import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';

export interface BatchPullRequest {
  imageName: string;
  tag: string;
  priority?: 'high' | 'normal' | 'low';
  retries?: number;
}

export interface BatchPullResult {
  success: Map<string, ContainerImage>;
  failed: Map<string, { error: string; retries: number }>;
  inProgress: string[];
  totalDuration: number;
  networkRequests: number;
  bytesTransferred: number;
}

export interface BatchSyncRequest {
  imageName: string;
  tag: string;
  sourceRegistry: string;
  targetRegistry: string;
}

export interface BatchSyncResult {
  success: string[];
  failed: Map<string, string>;
  totalDuration: number;
  imagesSynced: number;
}

export interface BatchDeleteRequest {
  imageName: string;
  tag: string;
  registry?: string;
}

export interface BatchDeleteResult {
  success: string[];
  failed: Map<string, string>;
  totalFreedSpace: number;
}

export interface BatchOperationOptions {
  concurrency?: number;
  timeout?: number;
  continueOnError?: boolean;
  mergeThreshold?: number;
  minBatchSize?: number;
  maxBatchSize?: number;
}

export interface MergedRequest {
  registry: string;
  images: string[];
  requestIds: string[];
}

export class BatchImageOperations {
  private defaultOptions: Required<BatchOperationOptions> = {
    concurrency: 5,
    timeout: 300000,
    continueOnError: true,
    mergeThreshold: 5,
    minBatchSize: 2,
    maxBatchSize: 50
  };

  private requestQueue: Map<string, {
    request: BatchPullRequest;
    resolve: (value: ContainerImage) => void;
    reject: (reason: any) => void;
    createdAt: number;
  }> = new Map();

  private processingBatch = false;
  private batchTimer?: NodeJS.Timeout;

  constructor(private options: BatchOperationOptions = {}) {
    this.options = { ...this.defaultOptions, ...options };
  }

  async batchPullImages(
    requests: BatchPullRequest[],
    pullFn: (imageName: string, tag: string) => Promise<ContainerImage>,
    options: BatchOperationOptions = {}
  ): Promise<BatchPullResult> {
    const opts = { ...this.defaultOptions, ...options };
    const startTime = Date.now();
    const success = new Map<string, ContainerImage>();
    const failed = new Map<string, { error: string; retries: number }>();
    const inProgress: string[] = [];
    let networkRequests = 0;
    let bytesTransferred = 0;

    const merged = this.mergeRequests(requests);
    logger.info('Batch pull started', { requests: requests.length, mergedBatches: merged.length });

    const processBatch = async (batch: BatchPullRequest[]): Promise<void> => {
      const results = await Promise.allSettled(
        batch.map(async (req) => {
          const imageKey = `${req.imageName}:${req.tag}`;
          try {
            networkRequests++;
            const image = await pullFn(req.imageName, req.tag);
            bytesTransferred += image.size;
            return { key: imageKey, image };
          } catch (error) {
            throw { key: imageKey, error };
          }
        })
      );

      for (const result of results) {
        if (result.status === 'fulfilled') {
          success.set(result.value.key, result.value.image);
        } else {
          const reason = result.reason as { key: string; error: Error };
          failed.set(reason.key, { error: reason.error.message, retries: 0 });
        }
      }
    };

    for (let i = 0; i < requests.length; i += opts.maxBatchSize) {
      const batch = requests.slice(i, i + opts.maxBatchSize);
      await processBatch(batch);
    }

    return {
      success,
      failed,
      inProgress,
      totalDuration: Date.now() - startTime,
      networkRequests,
      bytesTransferred
    };
  }

  async batchSyncImages(
    requests: BatchSyncRequest[],
    syncFn: (imageName: string, tag: string, source: string, target: string) => Promise<void>,
    options: BatchOperationOptions = {}
  ): Promise<BatchSyncResult> {
    const opts = { ...this.defaultOptions, ...options };
    const startTime = Date.now();
    const success: string[] = [];
    const failed = new Map<string, string>();

    logger.info('Batch sync started', { requests: requests.length });

    const processWithConcurrency = async (items: BatchSyncRequest[]): Promise<void> => {
      const executing: Promise<void>[] = [];
      
      for (const req of items) {
        const p = syncFn(req.imageName, req.tag, req.sourceRegistry, req.targetRegistry)
          .then(() => success.push(`${req.sourceRegistry}/${req.imageName}:${req.tag}`))
          .catch((error) => {
            if (!opts.continueOnError) throw error;
            failed.set(`${req.sourceRegistry}/${req.imageName}:${req.tag}`, error.message);
          });
        
        executing.push(p);
        
        if (executing.length >= opts.concurrency) {
          await Promise.race(executing);
        }
      }
      
      await Promise.all(executing);
    };

    try {
      await processWithConcurrency(requests);
    } catch (error) {
      logger.error('Batch sync failed', error as Error);
      if (!opts.continueOnError) throw error;
    }

    return {
      success,
      failed,
      totalDuration: Date.now() - startTime,
      imagesSynced: success.length
    };
  }

  async batchDeleteImages(
    requests: BatchDeleteRequest[],
    deleteFn: (imageName: string, tag: string, registry?: string) => Promise<number>,
    options: BatchOperationOptions = {}
  ): Promise<BatchDeleteResult> {
    const opts = { ...this.defaultOptions, ...options };
    const success: string[] = [];
    const failed = new Map<string, string>();
    let totalFreedSpace = 0;

    logger.info('Batch delete started', { requests: requests.length });

    await Promise.allSettled(
      requests.map(async (req) => {
        const key = req.registry ? `${req.registry}/${req.imageName}:${req.tag}` : `${req.imageName}:${req.tag}`;
        try {
          const freed = await deleteFn(req.imageName, req.tag, req.registry);
          totalFreedSpace += freed;
          success.push(key);
        } catch (error) {
          failed.set(key, (error as Error).message);
          if (!opts.continueOnError) throw error;
        }
      })
    );

    return { success, failed, totalFreedSpace };
  }

  queuePullRequest(request: BatchPullRequest): Promise<ContainerImage> {
    return new Promise((resolve, reject) => {
      const requestId = uuidv4();
      this.requestQueue.set(requestId, { request, resolve, reject, createdAt: Date.now() });
      
      if (!this.processingBatch && this.requestQueue.size >= this.options.mergeThreshold!) {
        this.processQueuedRequests();
      } else if (!this.batchTimer) {
        this.batchTimer = setTimeout(() => this.processQueuedRequests(), 1000);
      }
    });
  }

  private async processQueuedRequests(): Promise<void> {
    if (this.processingBatch || this.requestQueue.size === 0) return;
    
    this.processingBatch = true;
    if (this.batchTimer) {
      clearTimeout(this.batchTimer);
      this.batchTimer = undefined;
    }

    const requests = Array.from(this.requestQueue.entries());
    const batchSize = Math.min(requests.length, this.options.maxBatchSize!);
    const batch = requests.slice(0, batchSize);

    logger.info('Processing queued batch', { size: batch.length });

    for (const [requestId, entry] of batch) {
      try {
        entry.resolve({} as ContainerImage);
      } catch (error) {
        entry.reject(error);
      }
      this.requestQueue.delete(requestId);
    }

    this.processingBatch = false;
    
    if (this.requestQueue.size > 0) {
      setImmediate(() => this.processQueuedRequests());
    }
  }

  private mergeRequests(requests: BatchPullRequest[]): MergedRequest[] {
    const byRegistry = new Map<string, string[]>();
    const requestIds = new Map<string, string[]>();

    for (const req of requests) {
      const registry = req.imageName.split('/')[0] || 'default';
      if (!byRegistry.has(registry)) {
        byRegistry.set(registry, []);
        requestIds.set(registry, []);
      }
      byRegistry.get(registry)!.push(`${req.imageName}:${req.tag}`);
    }

    const merged: MergedRequest[] = [];
    for (const [registry, images] of byRegistry) {
      if (images.length >= this.options.minBatchSize!) {
        merged.push({
          registry,
          images,
          requestIds: requestIds.get(registry)!
        });
      }
    }

    return merged;
  }

  getQueueSize(): number {
    return this.requestQueue.size;
  }

  clearQueue(): void {
    this.requestQueue.clear();
    if (this.batchTimer) {
      clearTimeout(this.batchTimer);
      this.batchTimer = undefined;
    }
    logger.info('Batch queue cleared');
  }
}

export const createBatchOperations = (options?: BatchOperationOptions): BatchImageOperations => {
  return new BatchImageOperations(options);
};
