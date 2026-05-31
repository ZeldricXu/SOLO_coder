import { ProcessingContext, ProcessingRequest, ProcessingResult } from './types';
import { Pipeline } from './pipeline';
import { configManager } from './configManager';
import { resourceManager } from './resourceManager';
import { logger, currentDateTime, generateId } from '../utils/common';
import { RunInstance } from '../types/common';

export class CoreProcessor {
  private activeRuns: Map<string, RunInstance> = new Map();
  private pipeline: Pipeline;

  constructor(pipeline: Pipeline) {
    this.pipeline = pipeline;
  }

  async execute(request: ProcessingRequest): Promise<ProcessingResult> {
    const traceId = request.traceId || generateId('trace_');
    const startTime = Date.now();

    logger.info(`Starting processing`, { traceId, namespace: request.namespace });

    const context: ProcessingContext = {
      traceId,
      namespace: request.namespace,
      params: request.params,
      startTime,
      timeoutMs: 30000,
    };

    const runInstance: RunInstance = {
      runId: generateId('run_'),
      entityId: traceId,
      phase: 'executing',
      progress: 0,
      startedAt: currentDateTime(),
      completedAt: null,
      errorDetail: null,
    };
    this.activeRuns.set(runInstance.runId, runInstance);

    let resource;
    try {
      this.validateParams(request.params);

      const config = configManager.getLatestConfig(request.namespace);
      if (config) {
        context.config = config.parameters;
      }

      const poolSize = (context.config?.poolSize as number) || 5;
      const poolId = `pool_${request.namespace}`;

      if (!resourceManager.getPoolStatus(poolId)) {
        resourceManager.createPool(poolId, poolSize, 'worker');
      }

      resource = resourceManager.acquireResource(poolId);
      if (!resource) {
        throw new Error('No resources available');
      }

      runInstance.progress = 0.3;
      this.activeRuns.set(runInstance.runId, runInstance);

      const result = await this.pipeline.execute(context, request.payload);

      runInstance.progress = 1;
      runInstance.phase = result.success ? 'completed' : 'failed';
      runInstance.completedAt = currentDateTime();
      runInstance.errorDetail = result.error || null;

      this.emitEvent('task.completed', {
        runId: runInstance.runId,
        result,
        traceId,
      });

      return result;

    } catch (error) {
      logger.error(`Processing failed`, {
        traceId,
        error: error instanceof Error ? error.message : 'Unknown error',
      });

      runInstance.phase = 'failed';
      runInstance.completedAt = currentDateTime();
      runInstance.errorDetail = error instanceof Error ? error.message : 'Unknown error';

      this.rollbackTransaction(context);

      return {
        success: false,
        data: null,
        error: error instanceof Error ? error.message : 'Internal processing error',
        processingTimeMs: Date.now() - startTime,
      };

    } finally {
      if (resource) {
        resourceManager.releaseResource(`pool_${request.namespace}`, resource.id);
      }

      this.recordMetrics(context);
      this.activeRuns.set(runInstance.runId, runInstance);
    }
  }

  private validateParams(params: Record<string, unknown>): void {
    if (!params || typeof params !== 'object') {
      throw new Error('Invalid parameters');
    }
  }

  private persistResult(result: unknown): void {
    logger.debug(`Persisting result`, { result });
  }

  private emitEvent(eventName: string, data: unknown): void {
    logger.info(`Event emitted`, { eventName, data });
  }

  private rollbackTransaction(context: ProcessingContext): void {
    logger.warn(`Rolling back transaction`, { traceId: context.traceId });
  }

  private recordMetrics(context: ProcessingContext): void {
    const duration = Date.now() - context.startTime;
    logger.debug(`Recording metrics`, {
      traceId: context.traceId,
      durationMs: duration,
    });
  }

  getRunStatus(runId: string): RunInstance | undefined {
    return this.activeRuns.get(runId);
  }

  listRuns(): RunInstance[] {
    return Array.from(this.activeRuns.values());
  }
}

export { createValidationStage, createTransformationStage, createNormalizationStage } from './pipeline';
export { Pipeline } from './pipeline';
