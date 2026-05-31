import { PipelineStage, PipelineConfig, ProcessingContext, ProcessingResult } from './types';
import { logger, withTimeout } from '../utils/common';

export class Pipeline {
  private stages: PipelineStage[];
  private timeoutMs: number;

  constructor(config: PipelineConfig) {
    this.stages = config.stages;
    this.timeoutMs = config.timeoutMs || 30000;
  }

  async execute(context: ProcessingContext, initialData: unknown): Promise<ProcessingResult> {
    const startTime = Date.now();
    let currentData = initialData;

    try {
      for (const stage of this.stages) {
        logger.debug(`Executing pipeline stage`, {
          traceId: context.traceId,
          stage: stage.name,
        });

        currentData = await withTimeout(
          stage.execute(context, currentData),
          this.timeoutMs / this.stages.length,
          `Stage ${stage.name} timed out`
        );
      }

      return {
        success: true,
        data: currentData,
        processingTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      logger.error(`Pipeline execution failed`, {
        traceId: context.traceId,
        error: error instanceof Error ? error.message : 'Unknown error',
      });

      return {
        success: false,
        data: currentData,
        error: error instanceof Error ? error.message : 'Unknown error',
        processingTimeMs: Date.now() - startTime,
      };
    }
  }

  addStage(stage: PipelineStage): void {
    this.stages.push(stage);
  }

  getStages(): PipelineStage[] {
    return [...this.stages];
  }
}

export const createValidationStage = (
  validator: (data: unknown) => boolean
): PipelineStage => ({
  name: 'validation',
  execute: async (context, data) => {
    if (!validator(data)) {
      throw new Error('Data validation failed');
    }
    return data;
  },
});

export const createTransformationStage = (
  transformer: (data: unknown) => unknown
): PipelineStage => ({
  name: 'transformation',
  execute: async (context, data) => transformer(data),
});

export const createNormalizationStage = (
  normalizer: (data: unknown) => unknown
): PipelineStage => ({
  name: 'normalization',
  execute: async (context, data) => normalizer(data),
});
