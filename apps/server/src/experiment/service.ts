import { v4 as uuidv4 } from 'uuid';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { prisma } from '../config/database';
import { logger } from '../config/logger';
import type {
  Experiment,
  ExperimentRun,
  ExperimentCreateRequest,
  RunCreateRequest,
  RunUpdateRequest,
  ExperimentListRequest,
  RunListRequest,
  PaginatedResponse,
  MetricChartData,
  ExperimentComparison,
  LineageResponse,
  LineageNode,
  LineageEdge,
} from '@mlops/shared';
import {
  experimentCreateRequestSchema,
  runCreateRequestSchema,
  runUpdateRequestSchema,
  experimentListRequestSchema,
  runListRequestSchema,
} from '@mlops/shared';

export class ExperimentService {
  async createExperiment(request: ExperimentCreateRequest): Promise<Experiment> {
    const validated = experimentCreateRequestSchema.parse(request);

    const experiment = await prisma.experiment.create({
      data: {
        id: uuidv4(),
        name: validated.name,
        description: validated.description,
        projectId: validated.projectId,
        ownerId: validated.ownerId,
        team: validated.team,
        tags: validated.tags || [],
        metadata: validated.metadata || {},
      },
      include: {
        runs: {
          orderBy: { startTime: 'desc' },
          take: 5,
        },
      },
    });

    logger.info({ experimentId: experiment.id, name: experiment.name }, 'Experiment created');
    return this.transformExperiment(experiment);
  }

  async getExperiment(id: string): Promise<Experiment | null> {
    const experiment = await prisma.experiment.findUnique({
      where: { id },
      include: {
        runs: {
          orderBy: { startTime: 'desc' },
          take: 20,
          include: {
            hyperParameters: true,
            metrics: true,
          },
        },
      },
    });

    if (!experiment) return null;
    return this.transformExperiment(experiment);
  }

  async listExperiments(request: ExperimentListRequest): Promise<PaginatedResponse<Experiment>> {
    const validated = experimentListRequestSchema.parse(request);
    const page = validated.page || 1;
    const pageSize = validated.pageSize || 20;

    const where: Record<string, unknown> = {};
    if (validated.name) where.name = { contains: validated.name };
    if (validated.projectId) where.projectId = validated.projectId;
    if (validated.ownerId) where.ownerId = validated.ownerId;
    if (validated.team) where.team = validated.team;
    if (validated.status) where.status = validated.status;
    if (validated.tags && validated.tags.length > 0) {
      where.tags = { hasEvery: validated.tags };
    }

    const [total, experiments] = await Promise.all([
      prisma.experiment.count({ where }),
      prisma.experiment.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
        include: {
          runs: {
            orderBy: { startTime: 'desc' },
            take: 1,
            include: {
              metrics: {
                take: 5,
                orderBy: { timestamp: 'desc' },
              },
            },
          },
        },
      }),
    ]);

    return {
      data: experiments.map((e) => this.transformExperiment(e)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async createRun(request: RunCreateRequest): Promise<ExperimentRun> {
    const validated = runCreateRequestSchema.parse(request);

    const experiment = await prisma.experiment.findUnique({
      where: { id: validated.experimentId },
    });

    if (!experiment) {
      throw new Error(`Experiment not found: ${validated.experimentId}`);
    }

    const run = await prisma.experimentRun.create({
      data: {
        id: uuidv4(),
        experimentId: validated.experimentId,
        name: validated.name,
        status: 'running',
        startTime: new Date(),
        datasetVersion: validated.datasetVersion,
        parentRunId: validated.parentRunId,
        notes: validated.notes,
        tags: validated.tags || [],
        sourceType: validated.source?.type,
        sourceUri: validated.source?.uri,
        sourceCommit: validated.source?.commitHash,
        sourceEntry: validated.source?.entryPoint,
        hyperParameters: {
          create: (validated.hyperParameters || []).map((hp) => ({
            id: uuidv4(),
            name: hp.name,
            value: String(hp.value),
            type: hp.type,
          })),
        },
      },
      include: {
        hyperParameters: true,
        metrics: true,
      },
    });

    if (validated.parentRunId) {
      await prisma.experimentRun.update({
        where: { id: validated.parentRunId },
        data: {
          childRunIds: {
            push: run.id,
          },
        },
      });
    }

    logger.info({ runId: run.id, experimentId: validated.experimentId }, 'Run created');
    return this.transformRun(run);
  }

  async updateRun(runId: string, request: RunUpdateRequest): Promise<ExperimentRun | null> {
    const validated = runUpdateRequestSchema.parse(request);

    const existingRun = await prisma.experimentRun.findUnique({ where: { id: runId } });
    if (!existingRun) return null;

    const updateData: Record<string, unknown> = {};
    if (validated.status) updateData.status = validated.status;
    if (validated.endTime !== undefined) updateData.endTime = new Date(validated.endTime);
    if (validated.endTime && existingRun.startTime) {
      updateData.durationMs = BigInt(validated.endTime - existingRun.startTime.getTime());
    }
    if (validated.artifactPaths) updateData.artifactPaths = validated.artifactPaths;
    if (validated.modelVersionId) updateData.modelVersionId = validated.modelVersionId;
    if (validated.notes) updateData.notes = validated.notes;
    if (validated.tags) updateData.tags = validated.tags;

    if (validated.status === 'completed' || validated.status === 'failed') {
      if (!updateData.endTime) {
        updateData.endTime = new Date();
        updateData.durationMs = BigInt(Date.now() - existingRun.startTime.getTime());
      }
    }

    const run = await prisma.experimentRun.update({
      where: { id: runId },
      data: updateData,
      include: {
        hyperParameters: true,
        metrics: true,
      },
    });

    if (validated.metrics && validated.metrics.length > 0) {
      await prisma.runMetric.createMany({
        data: validated.metrics.map((m) => ({
          id: uuidv4(),
          runId,
          name: m.name,
          value: m.value,
          timestamp: new Date(m.timestamp),
          step: m.step,
          context: m.context,
        })),
      });

      const updatedRun = await prisma.experimentRun.findUnique({
        where: { id: runId },
        include: { hyperParameters: true, metrics: true },
      });

      logger.info({ runId, metricCount: validated.metrics.length }, 'Run metrics added');
      return updatedRun ? this.transformRun(updatedRun) : null;
    }

    logger.info({ runId, status: run.status }, 'Run updated');
    return this.transformRun(run);
  }

  async getRun(runId: string): Promise<ExperimentRun | null> {
    const run = await prisma.experimentRun.findUnique({
      where: { id: runId },
      include: {
        hyperParameters: true,
        metrics: {
          orderBy: { timestamp: 'asc' },
        },
      },
    });

    if (!run) return null;
    return this.transformRun(run);
  }

  async listRuns(request: RunListRequest): Promise<PaginatedResponse<ExperimentRun>> {
    const validated = runListRequestSchema.parse(request);
    const page = validated.page || 1;
    const pageSize = validated.pageSize || 20;

    const where: Record<string, unknown> = {};
    if (validated.experimentId) where.experimentId = validated.experimentId;
    if (validated.status) where.status = validated.status;
    if (validated.parentRunId) where.parentRunId = validated.parentRunId;
    if (validated.tags && validated.tags.length > 0) {
      where.tags = { hasEvery: validated.tags };
    }

    const [total, runs] = await Promise.all([
      prisma.experimentRun.count({ where }),
      prisma.experimentRun.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { startTime: 'desc' },
        include: {
          hyperParameters: true,
          metrics: {
            take: 10,
            orderBy: { timestamp: 'desc' },
          },
        },
      }),
    ]);

    return {
      data: runs.map((r) => this.transformRun(r)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async getMetricChartData(
    runIds: string[],
    metricName: string
  ): Promise<MetricChartData> {
    const metrics = await prisma.runMetric.findMany({
      where: {
        runId: { in: runIds },
        name: metricName,
      },
      orderBy: { timestamp: 'asc' },
    });

    return {
      metricName,
      runIds,
      dataPoints: metrics.map((m) => ({
        runId: m.runId,
        x: m.step ?? m.timestamp.getTime(),
        y: m.value,
        timestamp: m.timestamp.getTime(),
      })),
    };
  }

  async compareRuns(runIds: string[]): Promise<ExperimentComparison> {
    const runs = await prisma.experimentRun.findMany({
      where: { id: { in: runIds } },
      include: {
        hyperParameters: true,
        metrics: {
          orderBy: { timestamp: 'desc' },
          distinct: ['name'],
        },
      },
    });

    const hyperParamMap = new Map<string, Record<string, unknown>>();
    const metricMap = new Map<string, Record<string, number>>();

    for (const run of runs) {
      for (const hp of run.hyperParameters) {
        if (!hyperParamMap.has(hp.name)) {
          hyperParamMap.set(hp.name, {});
        }
        hyperParamMap.get(hp.name)![run.id] = this.parseValue(hp.value, hp.type);
      }

      for (const metric of run.metrics) {
        if (!metricMap.has(metric.name)) {
          metricMap.set(metric.name, {});
        }
        metricMap.get(metric.name)![run.id] = metric.value;
      }
    }

    const hyperParameters = Array.from(hyperParamMap.entries()).map(([name, values]) => ({
      name,
      values,
    }));

    const metrics = Array.from(metricMap.entries()).map(([name, values]) => {
      const entries = Object.entries(values) as [string, number][];
      const best = entries.reduce((acc, [runId, value]) =>
        value > acc.value ? { runId, value } : acc
      , { runId: entries[0]?.[0] || '', value: entries[0]?.[1] || 0 });
      return { name, values, best };
    });

    return {
      runIds,
      hyperParameters,
      metrics,
    };
  }

  async getLineage(runId: string, depth = 3): Promise<LineageResponse> {
    const nodes: LineageNode[] = [];
    const edges: LineageEdge[] = [];
    const visited = new Set<string>();

    async function traverse(id: string, currentDepth: number): Promise<void> {
      if (currentDepth > depth || visited.has(id)) return;
      visited.add(id);

      const run = await prisma.experimentRun.findUnique({
        where: { id },
        include: {
          experiment: true,
        },
      });

      if (!run) return;

      nodes.push({
        id: run.id,
        type: 'experiment',
        name: run.name,
        metadata: {
          status: run.status,
          startTime: run.startTime?.getTime(),
        },
      });

      if (run.modelVersionId) {
        nodes.push({
          id: run.modelVersionId,
          type: 'model',
          name: `model-${run.modelVersionId.slice(0, 8)}`,
        });
        edges.push({
          source: run.id,
          target: run.modelVersionId,
          relation: 'produces',
        });
      }

      if (run.parentRunId && !visited.has(run.parentRunId)) {
        edges.push({
          source: run.parentRunId,
          target: run.id,
          relation: 'parent',
        });
        await traverse(run.parentRunId, currentDepth + 1);
      }

      for (const childId of run.childRunIds) {
        if (!visited.has(childId)) {
          edges.push({
            source: run.id,
            target: childId,
            relation: 'child',
          });
          await traverse(childId, currentDepth + 1);
        }
      }
    }

    await traverse(runId, 0);

    return { nodes, edges };
  }

  private transformExperiment(prismaExp: any): Experiment {
    const runs = (prismaExp.runs || []).map((r: any) => this.transformRun(r));
    const completedRuns = runs.filter(
      (r: ExperimentRun) => r.status === 'completed' && r.metrics.length > 0
    );
    const bestRun = completedRuns.length > 0 ? completedRuns[0] : undefined;

    return {
      id: prismaExp.id,
      name: prismaExp.name,
      description: prismaExp.description ?? undefined,
      projectId: prismaExp.projectId,
      ownerId: prismaExp.ownerId,
      team: prismaExp.team,
      tags: prismaExp.tags || [],
      status: prismaExp.status as Experiment['status'],
      createdAt: prismaExp.createdAt.getTime(),
      updatedAt: prismaExp.updatedAt.getTime(),
      runs,
      bestRun,
      metadata: (prismaExp.metadata as Record<string, unknown>) || {},
    };
  }

  private transformRun(prismaRun: any): ExperimentRun {
    return {
      id: prismaRun.id,
      experimentId: prismaRun.experimentId,
      name: prismaRun.name,
      status: prismaRun.status as ExperimentRun['status'],
      startTime: prismaRun.startTime.getTime(),
      endTime: prismaRun.endTime?.getTime(),
      durationMs: prismaRun.durationMs ? Number(prismaRun.durationMs) : undefined,
      hyperParameters: (prismaRun.hyperParameters || []).map((hp: any) => ({
        name: hp.name,
        value: this.parseValue(hp.value, hp.type),
        type: hp.type,
      })),
      metrics: (prismaRun.metrics || []).map((m: any) => ({
        name: m.name,
        value: m.value,
        timestamp: m.timestamp.getTime(),
        step: m.step ?? undefined,
        context: (m.context as Record<string, unknown>) ?? undefined,
      })),
      artifactPaths: prismaRun.artifactPaths || [],
      modelVersionId: prismaRun.modelVersionId ?? undefined,
      datasetVersion: prismaRun.datasetVersion ?? undefined,
      parentRunId: prismaRun.parentRunId ?? undefined,
      childRunIds: prismaRun.childRunIds || [],
      notes: prismaRun.notes ?? undefined,
      tags: prismaRun.tags || [],
      source: prismaRun.sourceType
        ? {
            type: prismaRun.sourceType,
            uri: prismaRun.sourceUri ?? undefined,
            commitHash: prismaRun.sourceCommit ?? undefined,
            entryPoint: prismaRun.sourceEntry ?? undefined,
          }
        : undefined,
    };
  }

  private parseValue(value: string, type: string): string | number | boolean | null {
    if (type === 'number') return parseFloat(value);
    if (type === 'boolean') return value === 'true';
    if (value === 'null') return null;
    return value;
  }
}

export const experimentService = new ExperimentService();

export async function registerExperimentRoutes(fastify: any): Promise<void> {
  const service = experimentService;

  fastify.post('/api/v1/experiments', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createExperiment(request.body as ExperimentCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/experiments/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getExperiment(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Experiment not found' });
    return result;
  });

  fastify.get('/api/v1/experiments', async (request: FastifyRequest) => {
    return service.listExperiments(request.query as ExperimentListRequest);
  });

  fastify.post('/api/v1/runs', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createRun(request.body as RunCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.patch('/api/v1/runs/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.updateRun(request.params.id, request.body as RunUpdateRequest);
    if (!result) return reply.status(404).send({ error: 'Run not found' });
    return result;
  });

  fastify.get('/api/v1/runs/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getRun(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Run not found' });
    return result;
  });

  fastify.get('/api/v1/runs', async (request: FastifyRequest) => {
    return service.listRuns(request.query as RunListRequest);
  });

  fastify.get('/api/v1/runs/metrics/chart', async (request: FastifyRequest<{ Querystring: { runIds: string; metricName: string } }>) => {
    const runIds = (request.query.runIds as string).split(',');
    return service.getMetricChartData(runIds, request.query.metricName);
  });

  fastify.post('/api/v1/runs/compare', async (request: FastifyRequest<{ Body: { runIds: string[] } }>) => {
    return service.compareRuns(request.body.runIds);
  });

  fastify.get('/api/v1/runs/:id/lineage', async (request: FastifyRequest<{ Params: { id: string }; Querystring: { depth?: string } }>) => {
    const depth = request.query.depth ? parseInt(request.query.depth) : 3;
    return service.getLineage(request.params.id, depth);
  });
}
