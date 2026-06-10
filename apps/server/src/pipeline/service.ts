import type { PrismaClient } from '@prisma/client';
import {
  type ModelPipeline,
  type PipelineStep,
  type PipelineCreateRequest,
  type PipelineUpdateRequest,
  type PipelineInferenceRequest,
  type PipelineInferenceResponse,
  type PipelineStepResult,
  type PipelineValidationResult,
  type PipelineListRequest,
  type PipelineListResponse,
  type InputOutputMapping,
  type FieldMapping,
  type InferenceRequest,
  type InferenceResponse,
} from '@mlops/shared';
import pino from 'pino';
import { v4 as uuidv4 } from 'uuid';
import { prisma } from '../config/database';
import { inferenceGateway, InferenceGateway } from '../inference/gateway';

const logger = pino({ name: 'pipeline-service' });

export class PipelineService {
  constructor(
    private prisma: PrismaClient,
    private inferenceGateway: InferenceGateway,
  ) {}

  async createPipeline(request: PipelineCreateRequest): Promise<ModelPipeline> {
    const validation = await this.validatePipeline(request.steps, request.entryPoint, request.outputStep);
    if (!validation.valid) {
      throw new Error(`Pipeline validation failed: ${validation.errors.join(', ')}`);
    }

    const pipeline = await this.prisma.modelPipeline.create({
      data: {
        name: request.name,
        description: request.description,
        projectId: request.projectId,
        ownerId: request.ownerId,
        team: request.team,
        status: 'draft',
        entryPoint: request.entryPoint,
        outputStep: request.outputStep,
        tags: request.tags || [],
        metadata: request.metadata || {},
        steps: {
          create: request.steps.map((step, index) => ({
            stepId: step.id,
            name: step.name,
            type: step.type,
            description: step.description,
            modelId: step.modelId,
            version: step.version,
            inputMapping: step.inputMapping,
            outputMapping: step.outputMapping,
            condition: step.condition,
            aggregatorConfig: step.aggregatorConfig,
            transformConfig: step.transformConfig,
            dependsOn: step.dependsOn || [],
            timeoutMs: step.timeoutMs || 30000,
            retryCount: step.retryCount || 0,
            enabled: step.enabled !== false,
            orderIndex: index,
          })),
        },
      },
      include: {
        steps: true,
      },
    });

    return this.transformPipeline(pipeline);
  }

  async updatePipeline(pipelineId: string, request: PipelineUpdateRequest): Promise<ModelPipeline> {
    const existing = await this.prisma.modelPipeline.findUnique({
      where: { id: pipelineId },
      include: { steps: true },
    });

    if (!existing) {
      throw new Error(`Pipeline ${pipelineId} not found`);
    }

    if (request.steps) {
      const entryPoint = request.entryPoint || existing.entryPoint;
      const outputStep = request.outputStep || existing.outputStep;
      const validation = await this.validatePipeline(request.steps, entryPoint, outputStep);
      if (!validation.valid) {
        throw new Error(`Pipeline validation failed: ${validation.errors.join(', ')}`);
      }

      await this.prisma.pipelineStep.deleteMany({ where: { pipelineId } });

      const updateData: any = {
        ...(request.name && { name: request.name }),
        ...(request.description !== undefined && { description: request.description }),
        ...(request.status && { status: request.status }),
        ...(request.entryPoint && { entryPoint: request.entryPoint }),
        ...(request.outputStep && { outputStep: request.outputStep }),
        ...(request.tags && { tags: request.tags }),
        ...(request.metadata && { metadata: request.metadata }),
        steps: {
          create: request.steps.map((step, index) => ({
            stepId: step.id,
            name: step.name,
            type: step.type,
            description: step.description,
            modelId: step.modelId,
            version: step.version,
            inputMapping: step.inputMapping,
            outputMapping: step.outputMapping,
            condition: step.condition,
            aggregatorConfig: step.aggregatorConfig,
            transformConfig: step.transformConfig,
            dependsOn: step.dependsOn || [],
            timeoutMs: step.timeoutMs || 30000,
            retryCount: step.retryCount || 0,
            enabled: step.enabled !== false,
            orderIndex: index,
          })),
        },
      };

      const pipeline = await this.prisma.modelPipeline.update({
        where: { id: pipelineId },
        data: updateData,
        include: { steps: true },
      });

      return this.transformPipeline(pipeline);
    }

    const updateData: any = {
      ...(request.name && { name: request.name }),
      ...(request.description !== undefined && { description: request.description }),
      ...(request.status && { status: request.status }),
      ...(request.entryPoint && { entryPoint: request.entryPoint }),
      ...(request.outputStep && { outputStep: request.outputStep }),
      ...(request.tags && { tags: request.tags }),
      ...(request.metadata && { metadata: request.metadata }),
    };

    const pipeline = await this.prisma.modelPipeline.update({
      where: { id: pipelineId },
      data: updateData,
      include: { steps: true },
    });

    return this.transformPipeline(pipeline);
  }

  async getPipeline(pipelineId: string): Promise<ModelPipeline | null> {
    const pipeline = await this.prisma.modelPipeline.findUnique({
      where: { id: pipelineId },
      include: { steps: { orderBy: { orderIndex: 'asc' } } },
    });

    return pipeline ? this.transformPipeline(pipeline) : null;
  }

  async listPipelines(request: PipelineListRequest): Promise<PipelineListResponse> {
    const where: any = {};
    if (request.name) where.name = { contains: request.name };
    if (request.projectId) where.projectId = request.projectId;
    if (request.ownerId) where.ownerId = request.ownerId;
    if (request.team) where.team = request.team;
    if (request.status) where.status = request.status;
    if (request.tags && request.tags.length > 0) where.tags = { hasEvery: request.tags };

    const page = request.page || 1;
    const pageSize = request.pageSize || 20;
    const skip = (page - 1) * pageSize;

    const [total, pipelines] = await Promise.all([
      this.prisma.modelPipeline.count({ where }),
      this.prisma.modelPipeline.findMany({
        where,
        include: { steps: { orderBy: { orderIndex: 'asc' } } },
        skip,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
    ]);

    return {
      data: pipelines.map(p => this.transformPipeline(p)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async deletePipeline(pipelineId: string): Promise<void> {
    await this.prisma.modelPipeline.delete({ where: { id: pipelineId } });
  }

  async validatePipeline(
    steps: PipelineStep[],
    entryPoint: string,
    outputStep: string,
  ): Promise<PipelineValidationResult> {
    const errors: string[] = [];
    const warnings: string[] = [];

    const stepMap = new Map(steps.map(s => [s.id, s]));
    const stepIds = new Set(steps.map(s => s.id));

    if (!stepIds.has(entryPoint)) {
      errors.push(`Entry point step ${entryPoint} not found in steps`);
    }

    if (!stepIds.has(outputStep)) {
      errors.push(`Output step ${outputStep} not found in steps`);
    }

    for (const step of steps) {
      if (step.type === 'model' && !step.modelId) {
        errors.push(`Step ${step.id} is of type 'model' but has no modelId`);
      }

      for (const dep of step.dependsOn || []) {
        if (!stepIds.has(dep)) {
          errors.push(`Step ${step.id} depends on non-existent step ${dep}`);
        }
      }
    }

    const topologicalOrder = this.topologicalSort(steps, errors);
    const hasCycle = topologicalOrder.length === 0 && steps.length > 0;
    if (hasCycle) {
      errors.push('Pipeline has a cycle in dependencies');
    }

    if (!errors.includes(`Output step ${outputStep} not found in steps`) && topologicalOrder.length > 0) {
      const outputStepIndex = topologicalOrder.indexOf(outputStep);
      if (outputStepIndex === -1) {
        errors.push(`Output step ${outputStep} is unreachable from entry point`);
      }
    }

    let estimatedLatency = 0;
    for (const step of steps) {
      if (step.enabled !== false) {
        estimatedLatency += step.timeoutMs || 30000;
        if ((step.retryCount || 0) > 2) {
          warnings.push(`Step ${step.id} has high retry count (${step.retryCount})`);
        }
      }
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      topologicalOrder,
      estimatedLatencyMs: estimatedLatency,
    };
  }

  private topologicalSort(steps: PipelineStep[], errors: string[]): string[] {
    const visited = new Set<string>();
    const visiting = new Set<string>();
    const result: string[] = [];

    const visit = (stepId: string): boolean => {
      if (visited.has(stepId)) return true;
      if (visiting.has(stepId)) return false;

      visiting.add(stepId);
      const step = steps.find(s => s.id === stepId);
      if (!step) return false;

      for (const dep of step.dependsOn || []) {
        if (!visit(dep)) return false;
      }

      visiting.delete(stepId);
      visited.add(stepId);
      result.push(stepId);
      return true;
    };

    for (const step of steps) {
      if (!visit(step.id)) {
        return [];
      }
    }

    return result;
  }

  async runPipeline(request: PipelineInferenceRequest): Promise<PipelineInferenceResponse> {
    const pipeline = await this.prisma.modelPipeline.findUnique({
      where: { id: request.pipelineId },
      include: { steps: { orderBy: { orderIndex: 'asc' } } },
    });

    if (!pipeline) {
      throw new Error(`Pipeline ${request.pipelineId} not found`);
    }

    if (pipeline.status !== 'active') {
      throw new Error(`Pipeline ${request.pipelineId} is not active`);
    }

    const steps = pipeline.steps.filter(s => s.enabled);
    const stepMap = new Map(steps.map(s => [s.stepId, s]));

    const validation = await this.validatePipeline(
      steps.map(s => this.transformStep(s)),
      pipeline.entryPoint,
      pipeline.outputStep,
    );

    if (!validation.valid) {
      throw new Error(`Pipeline validation failed: ${validation.errors.join(', ')}`);
    }

    const startTime = Date.now();
    const requestId = request.requestId || uuidv4();
    const inferenceId = uuidv4();
    const stepResults: PipelineStepResult[] = [];
    const stepOutputs: Map<string, Record<string, unknown>> = new Map();

    let currentInputs = { ...request.inputs };
    let success = true;
    let error: string | undefined;

    try {
      for (const stepId of validation.topologicalOrder) {
        const step = stepMap.get(stepId);
        if (!step || !step.enabled) continue;

        const stepResult = await this.executeStep(
          step,
          currentInputs,
          stepOutputs,
          request,
        );

        stepResults.push(stepResult);

        if (!stepResult.success) {
          success = false;
          error = stepResult.error;
          break;
        }

        stepOutputs.set(stepId, stepResult.outputs);

        if (stepId === pipeline.outputStep) {
          currentInputs = stepResult.outputs;
        } else {
          currentInputs = { ...currentInputs, ...stepResult.outputs };
        }
      }

      await this.prisma.modelPipeline.update({
        where: { id: request.pipelineId },
        data: {
          lastRunAt: new Date(),
          runCount: { increment: 1 },
        },
      });
    } catch (err) {
      success = false;
      error = err instanceof Error ? err.message : String(err);
      logger.error({ err, pipelineId: request.pipelineId, requestId }, 'Pipeline execution failed');
    }

    const totalLatencyMs = Date.now() - startTime;

    const outputStepResult = stepResults.find(r => r.stepId === pipeline.outputStep);
    const outputs = outputStepResult?.outputs || {};

    return {
      pipelineId: request.pipelineId,
      requestId,
      inferenceId,
      outputs,
      totalLatencyMs,
      stepResults,
      success,
      error,
      timestamp: Date.now(),
    };
  }

  private async executeStep(
    step: any,
    inputs: Record<string, unknown>,
    stepOutputs: Map<string, Record<string, unknown>>,
    request: PipelineInferenceRequest,
  ): Promise<PipelineStepResult> {
    const stepStartTime = Date.now();

    try {
      const transformedInputs = this.applyInputMapping(
        step.inputMapping as InputOutputMapping,
        inputs,
        stepOutputs,
      );

      let outputs: Record<string, unknown>;

      switch (step.type) {
        case 'model':
          outputs = await this.executeModelStep(step, transformedInputs, request);
          break;
        case 'transform':
          outputs = this.executeTransformStep(step, transformedInputs);
          break;
        case 'condition':
          outputs = this.executeConditionStep(step, transformedInputs);
          break;
        case 'aggregator':
          outputs = this.executeAggregatorStep(step, transformedInputs, stepOutputs);
          break;
        default:
          throw new Error(`Unknown step type: ${step.type}`);
      }

      const transformedOutputs = this.applyOutputMapping(
        step.outputMapping as InputOutputMapping,
        outputs,
      );

      return {
        stepId: step.stepId,
        stepName: step.name,
        inputs: transformedInputs,
        outputs: transformedOutputs,
        latencyMs: Date.now() - stepStartTime,
        success: true,
        modelId: step.modelId,
        modelVersion: step.version,
      };
    } catch (err) {
      return {
        stepId: step.stepId,
        stepName: step.name,
        inputs,
        outputs: {},
        latencyMs: Date.now() - stepStartTime,
        success: false,
        error: err instanceof Error ? err.message : String(err),
      };
    }
  }

  private async executeModelStep(
    step: any,
    inputs: Record<string, unknown>,
    request: PipelineInferenceRequest,
  ): Promise<Record<string, unknown>> {
    if (!step.modelId) {
      throw new Error(`Step ${step.stepId} has no modelId`);
    }

    const inferenceRequest: InferenceRequest = {
      modelId: step.modelId,
      version: step.version,
      inputs,
      requestId: request.requestId,
      userId: request.userId,
      sessionId: request.sessionId,
      context: request.context,
      bypassCache: request.bypassCache,
    };

    const response = await this.inferenceGateway.infer(inferenceRequest);
    return response.outputs;
  }

  private executeTransformStep(
    step: any,
    inputs: Record<string, unknown>,
  ): Record<string, unknown> {
    const config = step.transformConfig;
    if (!config) return inputs;

    const outputs: Record<string, unknown> = { ...inputs };

    switch (config.type) {
      case 'scale':
        for (const [key, value] of Object.entries(inputs)) {
          if (typeof value === 'number') {
            outputs[key] = value * (config.scale?.factor || 1) + (config.scale?.offset || 0);
          }
        }
        break;
      case 'normalize':
        for (const [key, value] of Object.entries(inputs)) {
          if (typeof value === 'number' && config.normalize) {
            const { method, min, max, mean, std } = config.normalize;
            if (method === 'min_max' && min !== undefined && max !== undefined) {
              outputs[key] = (value - min) / (max - min);
            } else if (method === 'z_score' && mean !== undefined && std !== undefined) {
              outputs[key] = (value - mean) / std;
            }
          }
        }
        break;
      case 'one_hot':
        if (config.oneHot) {
          for (const [key, value] of Object.entries(inputs)) {
            const categories = config.oneHot.categories;
            const strValue = String(value);
            categories.forEach((cat: string, idx: number) => {
              if (!config.oneHot.dropFirst || idx > 0) {
                outputs[`${key}_${cat}`] = strValue === cat ? 1 : 0;
              }
            });
          }
        }
        break;
      case 'bucketize':
        if (config.bucketize) {
          for (const [key, value] of Object.entries(inputs)) {
            if (typeof value === 'number') {
              const boundaries = config.bucketize.boundaries;
              let bucket = 0;
              for (let i = 0; i < boundaries.length; i++) {
                if (value > boundaries[i]) bucket = i + 1;
              }
              outputs[`${key}_bucket`] = config.bucketize.labels?.[bucket] || bucket;
            }
          }
        }
        break;
    }

    return outputs;
  }

  private executeConditionStep(
    step: any,
    inputs: Record<string, unknown>,
  ): Record<string, unknown> {
    const condition = step.condition;
    if (!condition) return inputs;

    const fieldValue = inputs[condition.field];
    let result = false;

    switch (condition.type) {
      case 'field_equals':
        result = fieldValue === condition.value;
        break;
      case 'field_greater':
        result = typeof fieldValue === 'number' && fieldValue > (condition.value as number);
        break;
      case 'field_less':
        result = typeof fieldValue === 'number' && fieldValue < (condition.value as number);
        break;
      case 'field_contains':
        result = String(fieldValue).includes(String(condition.value));
        break;
      case 'custom':
        try {
          const fn = new Function('inputs', condition.expression);
          result = fn(inputs);
        } catch {
          result = false;
        }
        break;
    }

    return {
      ...inputs,
      __condition_result: result,
      __condition_step: condition.trueStepId,
      __condition_else_step: condition.falseStepId,
    };
  }

  private executeAggregatorStep(
    step: any,
    inputs: Record<string, unknown>,
    stepOutputs: Map<string, Record<string, unknown>>,
  ): Record<string, unknown> {
    const config = step.aggregatorConfig;
    if (!config) return inputs;

    const sourceData: Record<string, unknown>[] = [];
    for (const sourceStepId of config.sourceSteps) {
      const outputs = stepOutputs.get(sourceStepId);
      if (outputs) {
        sourceData.push(outputs);
      }
    }

    if (sourceData.length === 0) {
      return inputs;
    }

    const outputField = config.outputField;
    let aggregatedValue: unknown;

    switch (config.type) {
      case 'mean':
        {
          const values = sourceData
            .map(d => d[outputField])
            .filter(v => typeof v === 'number') as number[];
          aggregatedValue = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
        }
        break;
      case 'sum':
        {
          const values = sourceData
            .map(d => d[outputField])
            .filter(v => typeof v === 'number') as number[];
          aggregatedValue = values.reduce((a, b) => a + b, 0);
        }
        break;
      case 'max':
        {
          const values = sourceData
            .map(d => d[outputField])
            .filter(v => typeof v === 'number') as number[];
          aggregatedValue = values.length > 0 ? Math.max(...values) : 0;
        }
        break;
      case 'min':
        {
          const values = sourceData
            .map(d => d[outputField])
            .filter(v => typeof v === 'number') as number[];
          aggregatedValue = values.length > 0 ? Math.min(...values) : 0;
        }
        break;
      case 'weighted_sum':
        {
          const weights = config.weights || {};
          let sum = 0;
          for (const sourceStepId of config.sourceSteps) {
            const outputs = stepOutputs.get(sourceStepId);
            const value = outputs?.[outputField];
            const weight = weights[sourceStepId] || 1;
            if (typeof value === 'number') {
              sum += value * weight;
            }
          }
          aggregatedValue = sum;
        }
        break;
      case 'concat':
        {
          const values = sourceData.map(d => d[outputField]).filter(v => v !== undefined);
          aggregatedValue = values;
        }
        break;
    }

    return {
      ...inputs,
      [outputField]: aggregatedValue,
    };
  }

  private applyInputMapping(
    mapping: InputOutputMapping,
    inputs: Record<string, unknown>,
    stepOutputs: Map<string, Record<string, unknown>>,
  ): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    for (const fieldMapping of mapping.mappings) {
      let value: unknown;

      if (fieldMapping.source.startsWith('step:')) {
        const [stepId, field] = fieldMapping.source.slice(5).split('.');
        const stepData = stepOutputs.get(stepId);
        value = stepData?.[field];
      } else {
        value = this.getNestedValue(inputs, fieldMapping.source);
      }

      if (value === undefined && fieldMapping.defaultValue !== undefined) {
        value = fieldMapping.defaultValue;
      }

      if (value === undefined && fieldMapping.required) {
        throw new Error(`Required field ${fieldMapping.source} is missing`);
      }

      if (value !== undefined) {
        value = this.applyTransform(value, fieldMapping.transform || 'identity');
      }

      this.setNestedValue(result, fieldMapping.target, value);
    }

    return result;
  }

  private applyOutputMapping(
    mapping: InputOutputMapping,
    outputs: Record<string, unknown>,
  ): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    for (const fieldMapping of mapping.mappings) {
      let value = this.getNestedValue(outputs, fieldMapping.source);

      if (value === undefined && fieldMapping.defaultValue !== undefined) {
        value = fieldMapping.defaultValue;
      }

      if (value === undefined && fieldMapping.required) {
        throw new Error(`Required output field ${fieldMapping.source} is missing`);
      }

      if (value !== undefined) {
        value = this.applyTransform(value, fieldMapping.transform || 'identity');
      }

      this.setNestedValue(result, fieldMapping.target, value);
    }

    return result;
  }

  private getNestedValue(obj: Record<string, unknown>, path: string): unknown {
    return path.split('.').reduce((current, part) => {
      if (current && typeof current === 'object') {
        return (current as Record<string, unknown>)[part];
      }
      return undefined;
    }, obj);
  }

  private setNestedValue(obj: Record<string, unknown>, path: string, value: unknown): void {
    const parts = path.split('.');
    let current: Record<string, unknown> = obj;

    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i]!;
      if (!current[part] || typeof current[part] !== 'object') {
        current[part] = {};
      }
      current = current[part] as Record<string, unknown>;
    }

    current[parts[parts.length - 1]!] = value;
  }

  private applyTransform(value: unknown, transform: FieldMapping['transform']): unknown {
    switch (transform) {
      case 'json_parse':
        if (typeof value === 'string') {
          try {
            return JSON.parse(value);
          } catch {
            return value;
          }
        }
        return value;
      case 'json_stringify':
        return JSON.stringify(value);
      case 'flatten':
        return this.flattenObject(value as Record<string, unknown>);
      case 'nest':
        return value;
      default:
        return value;
    }
  }

  private flattenObject(
    obj: Record<string, unknown>,
    prefix = '',
  ): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    for (const [key, value] of Object.entries(obj)) {
      const newKey = prefix ? `${prefix}.${key}` : key;

      if (value && typeof value === 'object' && !Array.isArray(value)) {
        Object.assign(result, this.flattenObject(value as Record<string, unknown>, newKey));
      } else {
        result[newKey] = value;
      }
    }

    return result;
  }

  private transformPipeline(pipeline: any): ModelPipeline {
    return {
      id: pipeline.id,
      name: pipeline.name,
      description: pipeline.description || undefined,
      projectId: pipeline.projectId,
      ownerId: pipeline.ownerId,
      team: pipeline.team,
      status: pipeline.status as ModelPipeline['status'],
      steps: pipeline.steps.map((s: any) => this.transformStep(s)),
      entryPoint: pipeline.entryPoint,
      outputStep: pipeline.outputStep,
      tags: pipeline.tags,
      metadata: pipeline.metadata as Record<string, unknown>,
      createdAt: pipeline.createdAt.getTime(),
      updatedAt: pipeline.updatedAt.getTime(),
      lastRunAt: pipeline.lastRunAt?.getTime(),
      runCount: pipeline.runCount,
      avgLatencyMs: pipeline.avgLatencyMs ?? undefined,
      successRate: pipeline.successRate ?? undefined,
    };
  }

  private transformStep(step: any): PipelineStep {
    return {
      id: step.stepId || step.id,
      name: step.name,
      type: step.type as PipelineStep['type'],
      description: step.description || undefined,
      modelId: step.modelId || undefined,
      version: step.version || undefined,
      inputMapping: step.inputMapping as InputOutputMapping,
      outputMapping: step.outputMapping as InputOutputMapping,
      condition: step.condition || undefined,
      aggregatorConfig: step.aggregatorConfig || undefined,
      transformConfig: step.transformConfig || undefined,
      dependsOn: step.dependsOn || [],
      timeoutMs: step.timeoutMs,
      retryCount: step.retryCount,
      enabled: step.enabled,
    };
  }
}

export const pipelineService = new PipelineService(prisma, inferenceGateway);

export async function registerPipelineRoutes(fastify: any): Promise<void> {
  const service = pipelineService;

  fastify.post('/api/v1/pipelines', async (request: any, reply: any) => {
    const result = await service.createPipeline(request.body as PipelineCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/pipelines/:id', async (request: any, reply: any) => {
    const result = await service.getPipeline(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Pipeline not found' });
    return result;
  });

  fastify.get('/api/v1/pipelines', async (request: any) => {
    return service.listPipelines(request.query as PipelineListRequest);
  });

  fastify.patch('/api/v1/pipelines/:id', async (request: any, reply: any) => {
    try {
      const result = await service.updatePipeline(request.params.id, request.body as PipelineUpdateRequest);
      return result;
    } catch (error) {
      return reply.status(404).send({ error: error instanceof Error ? error.message : 'Not found' });
    }
  });

  fastify.delete('/api/v1/pipelines/:id', async (request: any, reply: any) => {
    await service.deletePipeline(request.params.id);
    return reply.status(204).send();
  });

  fastify.post('/api/v1/pipelines/:id/validate', async (request: any, reply: any) => {
    const pipeline = await service.getPipeline(request.params.id);
    if (!pipeline) return reply.status(404).send({ error: 'Pipeline not found' });
    return service.validatePipeline(pipeline.steps, pipeline.entryPoint, pipeline.outputStep);
  });

  fastify.post('/api/v1/pipelines/run', async (request: any) => {
    return service.runPipeline(request.body as PipelineInferenceRequest);
  });
}
