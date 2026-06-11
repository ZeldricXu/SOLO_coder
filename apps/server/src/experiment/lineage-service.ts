import type { PrismaClient } from '@prisma/client';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { Graph } from 'graphology';
import { bfsFromNode, dfsFromNode } from 'graphology-traversal';
import pino from 'pino';
import { v4 as uuidv4 } from 'uuid';
import { prisma } from '../config/database';
import {
  type ExperimentEvolutionTree,
  type ExperimentLineageNode,
  type MetricDelta,
  type HyperParameterDelta,
  type LineageEdge,
  type LineageQueryRequest,
  type LineageCompareRequest,
  type LineageCompareResponse,
  type LineageGenerationSummary,
  type LineageStats,
  type ExperimentForkRequest,
  type RunForkResult,
  type MetricValue,
  type ExperimentRun,
} from '@mlops/shared';

const logger = pino({ name: 'experiment-lineage' });

export class ExperimentLineageService {
  private graphCache: Map<string, Graph> = new Map();

  constructor(private prisma: PrismaClient) {}

  async getEvolutionTree(request: LineageQueryRequest): Promise<ExperimentEvolutionTree> {
    const {
      runId,
      experimentId,
      depth = 3,
      direction = 'both',
      includeMetrics,
      primaryMetric,
      improvementDirection = 'higher',
    } = request;

    let startRunId = runId;

    if (!startRunId && experimentId) {
      const latestRun = await this.prisma.experimentRun.findFirst({
        where: { experimentId, status: 'completed' },
        orderBy: { endTime: 'desc' },
      });
      if (!latestRun) {
        return {
          rootNodes: [],
          allNodes: {},
          edges: [],
          maxDepth: 0,
          totalExperiments: 0,
          totalRuns: 0,
          generationSummary: [],
        };
      }
      startRunId = latestRun.id;
    }

    if (!startRunId) {
      throw new Error('Either runId or experimentId must be provided');
    }

    const graph = new Graph({ directed: true, allowSelfLoops: false });

    await this.buildLineageGraph(startRunId, graph, depth, direction);

    const allNodes: Record<string, ExperimentLineageNode> = {};
    const edges: LineageEdge[] = [];
    let maxDepth = 0;
    const experimentIds = new Set<string>();

    const startNode = graph.getNodeAttributes(startRunId) as any;
    const baselineDepth = startNode?.depth || 0;

    graph.forEachNode((node, attrs) => {
      const nodeData = attrs as any;
      experimentIds.add(nodeData.experimentId);

      const nodeDepth = (nodeData.depth || 0) - baselineDepth;
      const adjustedDepth = direction === 'up' ? -nodeDepth : nodeDepth;
      maxDepth = Math.max(maxDepth, Math.abs(adjustedDepth));

      allNodes[node] = {
        experimentId: nodeData.experimentId,
        experimentName: nodeData.experimentName,
        runId: node,
        runName: nodeData.runName,
        parentRunId: nodeData.parentRunId,
        baselineRunId: nodeData.baselineRunId,
        metricDeltas: nodeData.metricDeltas || {},
        hyperParameterDeltas: nodeData.hyperParameterDeltas || {},
        createdAt: nodeData.createdAt,
        status: nodeData.status,
        tags: nodeData.tags || [],
        notes: nodeData.notes,
        depth: adjustedDepth,
        direction: nodeDepth === 0 ? 'current' : nodeDepth < 0 ? 'up' : 'down',
        hasParent: graph.inDegree(node) > 0,
        hasChildren: graph.outDegree(node) > 0,
      };
    });

    graph.forEachEdge((edge, attrs, source, target) => {
      const edgeData = attrs as any;
      edges.push({
        id: edge,
        source,
        target,
        type: edgeData.type || 'parent_child',
        relationship: edgeData.relationship || 'direct',
        metricDeltas: edgeData.metricDeltas || {},
      });
    });

    const rootNodes = Object.values(allNodes).filter((n) => n.depth === 0);

    const generationSummary = this.buildGenerationSummary(allNodes, primaryMetric, improvementDirection);

    const bestPerformer = this.findBestPerformer(allNodes, primaryMetric, improvementDirection);

    return {
      rootNodes,
      allNodes,
      edges,
      maxDepth,
      totalExperiments: experimentIds.size,
      totalRuns: graph.order,
      bestPerformer,
      generationSummary,
    };
  }

  private async buildLineageGraph(
    startRunId: string,
    graph: Graph,
    maxDepth: number,
    direction: 'up' | 'down' | 'both',
  ): Promise<void> {
    const startRun = await this.prisma.experimentRun.findUnique({
      where: { id: startRunId },
      include: {
        experiment: true,
        hyperParameters: true,
        metrics: {
          orderBy: { timestamp: 'desc' },
          distinct: ['name'],
        },
      },
    });

    if (!startRun) {
      throw new Error(`Run not found: ${startRunId}`);
    }

    const startMetrics = this.buildMetricsMap(startRun.metrics as any[]);
    const startHyperParams = this.buildHyperParamsMap(startRun.hyperParameters as any[]);

    graph.addNode(startRunId, {
      experimentId: startRun.experimentId,
      experimentName: startRun.experiment.name,
      runName: startRun.name,
      parentRunId: startRun.parentRunId,
      status: startRun.status,
      tags: startRun.tags,
      notes: startRun.notes,
      createdAt: startRun.startTime?.getTime() || Date.now(),
      depth: 0,
      metrics: startMetrics,
      hyperParameters: startHyperParams,
      metricDeltas: {},
      hyperParameterDeltas: {},
    });

    if (direction === 'up' || direction === 'both') {
      await this.traverseUpward(startRunId, graph, startMetrics, startHyperParams, 1, maxDepth);
    }

    if (direction === 'down' || direction === 'both') {
      await this.traverseDownward(startRunId, graph, startMetrics, startHyperParams, 1, maxDepth);
    }
  }

  private async traverseUpward(
    fromRunId: string,
    graph: Graph,
    childMetrics: Record<string, number>,
    childHyperParams: Record<string, unknown>,
    currentDepth: number,
    maxDepth: number,
  ): Promise<void> {
    if (currentDepth > maxDepth) return;

    const fromNode = graph.getNodeAttributes(fromRunId) as any;
    const parentRunId = fromNode.parentRunId;

    if (!parentRunId || graph.hasNode(parentRunId)) return;

    const parentRun = await this.prisma.experimentRun.findUnique({
      where: { id: parentRunId },
      include: {
        experiment: true,
        hyperParameters: true,
        metrics: {
          orderBy: { timestamp: 'desc' },
          distinct: ['name'],
        },
      },
    });

    if (!parentRun) return;

    const parentMetrics = this.buildMetricsMap(parentRun.metrics as any[]);
    const parentHyperParams = this.buildHyperParamsMap(parentRun.hyperParameters as any[]);

    const metricDeltas = this.calculateMetricDeltas(childMetrics, parentMetrics, 'higher');
    const hyperParamDeltas = this.calculateHyperParamDeltas(childHyperParams, parentHyperParams);

    graph.addNode(parentRunId, {
      experimentId: parentRun.experimentId,
      experimentName: parentRun.experiment.name,
      runName: parentRun.name,
      parentRunId: parentRun.parentRunId,
      status: parentRun.status,
      tags: parentRun.tags,
      notes: parentRun.notes,
      createdAt: parentRun.startTime?.getTime() || Date.now(),
      depth: -currentDepth,
      metrics: parentMetrics,
      hyperParameters: parentHyperParams,
      metricDeltas: {},
      hyperParameterDeltas: {},
    });

    graph.addDirectedEdgeWithKey(`${parentRunId}->${fromRunId}`, parentRunId, fromRunId, {
      type: 'parent_child',
      relationship: 'direct',
      metricDeltas,
      hyperParamDeltas,
    });

    graph.updateNodeAttributes(fromRunId, (attrs: any) => ({
      ...attrs,
      metricDeltas,
      hyperParameterDeltas: hyperParamDeltas,
    }));

    await this.traverseUpward(parentRunId, graph, parentMetrics, parentHyperParams, currentDepth + 1, maxDepth);
  }

  private async traverseDownward(
    fromRunId: string,
    graph: Graph,
    parentMetrics: Record<string, number>,
    parentHyperParams: Record<string, unknown>,
    currentDepth: number,
    maxDepth: number,
  ): Promise<void> {
    if (currentDepth > maxDepth) return;

    const fromNode = graph.getNodeAttributes(fromRunId) as any;

    const childRuns = await this.prisma.experimentRun.findMany({
      where: { parentRunId: fromRunId },
      include: {
        experiment: true,
        hyperParameters: true,
        metrics: {
          orderBy: { timestamp: 'desc' },
          distinct: ['name'],
        },
      },
    });

    for (const childRun of childRuns) {
      if (graph.hasNode(childRun.id)) continue;

      const childMetrics = this.buildMetricsMap(childRun.metrics as any[]);
      const childHyperParams = this.buildHyperParamsMap(childRun.hyperParameters as any[]);

      const metricDeltas = this.calculateMetricDeltas(childMetrics, parentMetrics, 'higher');
      const hyperParamDeltas = this.calculateHyperParamDeltas(childHyperParams, parentHyperParams);

      graph.addNode(childRun.id, {
        experimentId: childRun.experimentId,
        experimentName: childRun.experiment.name,
        runName: childRun.name,
        parentRunId: childRun.parentRunId,
        status: childRun.status,
        tags: childRun.tags,
        notes: childRun.notes,
        createdAt: childRun.startTime?.getTime() || Date.now(),
        depth: currentDepth,
        metrics: childMetrics,
        hyperParameters: childHyperParams,
        metricDeltas,
        hyperParameterDeltas: hyperParamDeltas,
      });

      graph.addDirectedEdgeWithKey(`${fromRunId}->${childRun.id}`, fromRunId, childRun.id, {
        type: 'parent_child',
        relationship: 'direct',
        metricDeltas,
        hyperParamDeltas,
      });

      await this.traverseDownward(
        childRun.id,
        graph,
        childMetrics,
        childHyperParams,
        currentDepth + 1,
        maxDepth,
      );
    }
  }

  private buildMetricsMap(metrics: any[]): Record<string, number> {
    const map: Record<string, number> = {};
    for (const m of metrics) {
      if (map[m.name] === undefined) {
        map[m.name] = m.value;
      }
    }
    return map;
  }

  private buildHyperParamsMap(hps: any[]): Record<string, unknown> {
    const map: Record<string, unknown> = {};
    for (const hp of hps) {
      map[hp.name] = this.parseHpValue(hp.value, hp.type);
    }
    return map;
  }

  private parseHpValue(value: string, type: string): unknown {
    if (type === 'number') return parseFloat(value);
    if (type === 'boolean') return value === 'true';
    if (type === 'json') {
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    }
    return value;
  }

  private calculateMetricDeltas(
    current: Record<string, number>,
    parent: Record<string, number>,
    improvementDirection: 'higher' | 'lower',
  ): Record<string, MetricDelta> {
    const deltas: Record<string, MetricDelta> = {};

    for (const [name, currentValue] of Object.entries(current)) {
      const parentValue = parent[name];

      if (parentValue === undefined) continue;

      const absoluteChange = currentValue - parentValue;
      const relativeChange = parentValue !== 0 ? absoluteChange / Math.abs(parentValue) : 0;
      const percentageChange = relativeChange * 100;

      const isImprovement =
        improvementDirection === 'higher' ? currentValue > parentValue : currentValue < parentValue;

      deltas[name] = {
        metricName: name,
        currentValue,
        parentValue,
        absoluteChange,
        relativeChange,
        percentageChange,
        isImprovement,
        improvementDirection,
      };
    }

    return deltas;
  }

  private calculateHyperParamDeltas(
    current: Record<string, unknown>,
    parent: Record<string, unknown>,
  ): Record<string, HyperParameterDelta> {
    const deltas: Record<string, HyperParameterDelta> = {};
    const allKeys = new Set([...Object.keys(current), ...Object.keys(parent)]);

    for (const key of allKeys) {
      const currentValue = current[key];
      const parentValue = parent[key];

      let changeType: 'added' | 'removed' | 'modified' = 'modified';
      let changed = true;

      if (parentValue === undefined) {
        changeType = 'added';
      } else if (currentValue === undefined) {
        changeType = 'removed';
      } else if (JSON.stringify(currentValue) === JSON.stringify(parentValue)) {
        changed = false;
      }

      deltas[key] = {
        paramName: key,
        currentValue,
        parentValue,
        changed,
        changeType,
      };
    }

    return deltas;
  }

  private buildGenerationSummary(
    allNodes: Record<string, ExperimentLineageNode>,
    primaryMetric?: string,
    improvementDirection: 'higher' | 'lower' = 'higher',
  ): LineageGenerationSummary[] {
    const generations: Map<number, ExperimentLineageNode[]> = new Map();

    for (const node of Object.values(allNodes)) {
      const gen = node.depth;
      if (!generations.has(gen)) {
        generations.set(gen, []);
      }
      generations.get(gen)!.push(node);
    }

    const sortedGens = Array.from(generations.keys()).sort((a, b) => a - b);

    return sortedGens.map((gen) => {
      const nodes = generations.get(gen)!;
      const avgImprovement: Record<string, number> = {};

      if (primaryMetric) {
        const improvements = nodes
          .map((n) => n.metricDeltas[primaryMetric])
          .filter((d): d is MetricDelta => d !== undefined);

        if (improvements.length > 0) {
          const total = improvements.reduce((sum, d) => sum + d.relativeChange, 0);
          avgImprovement[primaryMetric] = total / improvements.length;
        }
      }

      let bestRunId: string | undefined;
      let bestValue: number | undefined;

      if (primaryMetric) {
        for (const node of nodes) {
          const delta = node.metricDeltas[primaryMetric];
          if (delta) {
            const isBetter =
              improvementDirection === 'higher'
                ? bestValue === undefined || delta.currentValue > bestValue
                : bestValue === undefined || delta.currentValue < bestValue;
            if (isBetter) {
              bestValue = delta.currentValue;
              bestRunId = node.runId;
            }
          }
        }
      }

      return {
        generation: gen,
        runCount: nodes.length,
        avgImprovement,
        bestRunId,
        primaryMetricValue: bestValue,
      };
    });
  }

  private findBestPerformer(
    allNodes: Record<string, ExperimentLineageNode>,
    primaryMetric?: string,
    improvementDirection: 'higher' | 'lower' = 'higher',
  ): ExperimentEvolutionTree['bestPerformer'] {
    if (!primaryMetric) return undefined;

    let bestRun: ExperimentLineageNode | undefined;
    let bestValue: number | undefined;

    for (const node of Object.values(allNodes)) {
      const delta = node.metricDeltas[primaryMetric];
      if (!delta) continue;

      const isBetter =
        improvementDirection === 'higher'
          ? bestValue === undefined || delta.currentValue > bestValue
          : bestValue === undefined || delta.currentValue < bestValue;

      if (isBetter) {
        bestValue = delta.currentValue;
        bestRun = node;
      }
    }

    if (!bestRun || bestValue === undefined) return undefined;

    return {
      runId: bestRun.runId,
      experimentName: bestRun.experimentName,
      primaryMetric,
      primaryMetricValue: bestValue,
    };
  }

  async compareLineage(request: LineageCompareRequest): Promise<LineageCompareResponse> {
    const { runIds, primaryMetric, improvementDirection = 'higher', includeAllMetrics = false } = request;

    const runs = await this.prisma.experimentRun.findMany({
      where: { id: { in: runIds } },
      include: {
        experiment: true,
        hyperParameters: true,
        metrics: {
          orderBy: { timestamp: 'desc' },
          distinct: ['name'],
        },
      },
    });

    const runData = runs.map((r) => ({
      runId: r.id,
      experimentId: r.experimentId,
      experimentName: r.experiment.name,
      runName: r.name,
      metrics: this.buildMetricsMap(r.metrics as any[]),
      hyperParameters: this.buildHyperParamsMap(r.hyperParameters as any[]),
    }));

    const comparisonMatrix: Record<string, Record<string, Record<string, MetricDelta>>> = {};

    for (const runA of runData) {
      comparisonMatrix[runA.runId] = {};
      for (const runB of runData) {
        if (runA.runId === runB.runId) continue;
        comparisonMatrix[runA.runId]![runB.runId] = this.calculateMetricDeltas(
          runA.metrics,
          runB.metrics,
          improvementDirection,
        );
      }
    }

    let bestRunId: string | undefined;
    if (primaryMetric) {
      let bestValue: number | undefined;
      for (const run of runData) {
        const value = run.metrics[primaryMetric];
        if (value === undefined) continue;
        const isBetter =
          improvementDirection === 'higher'
            ? bestValue === undefined || value > bestValue
            : bestValue === undefined || value < bestValue;
        if (isBetter) {
          bestValue = value;
          bestRunId = run.runId;
        }
      }
    }

    return {
      runs: runData.map((r) => ({
        runId: r.runId,
        experimentId: r.experimentId,
        experimentName: r.experimentName,
        runName: r.runName,
        metrics: r.metrics,
        hyperParameters: r.hyperParameters,
      })),
      bestRunId,
      primaryMetric,
      comparisonMatrix,
    };
  }

  async forkExperiment(request: ExperimentForkRequest): Promise<RunForkResult> {
    const {
      sourceRunId,
      newExperimentName,
      newRunName,
      description,
      hyperParameterOverrides = {},
      projectId,
      ownerId,
      team,
    } = request;

    const sourceRun = await this.prisma.experimentRun.findUnique({
      where: { id: sourceRunId },
      include: {
        experiment: true,
        hyperParameters: true,
      },
    });

    if (!sourceRun) {
      throw new Error(`Source run not found: ${sourceRunId}`);
    }

    const targetProjectId = projectId || sourceRun.experiment.projectId;
    const targetOwnerId = ownerId || sourceRun.experiment.ownerId;
    const targetTeam = team || sourceRun.experiment.team;

    const newExperiment = await this.prisma.experiment.create({
      data: {
        id: uuidv4(),
        name: newExperimentName,
        description: description || `Forked from ${sourceRun.experiment.name} - ${sourceRun.name}`,
        projectId: targetProjectId,
        ownerId: targetOwnerId,
        team: targetTeam,
        tags: ['forked', ...(sourceRun.experiment.tags || [])],
        metadata: {
          forkedFrom: {
            experimentId: sourceRun.experimentId,
            runId: sourceRunId,
            runName: sourceRun.name,
          },
        },
      },
    });

    const newRunHyperParams = sourceRun.hyperParameters.map((hp) => {
      const override = hyperParameterOverrides[hp.name];
      if (override !== undefined) {
        return {
          name: hp.name,
          value: String(override),
          type: hp.type,
        };
      }
      return { name: hp.name, value: hp.value, type: hp.type };
    });

    const newRun = await this.prisma.experimentRun.create({
      data: {
        id: uuidv4(),
        experimentId: newExperiment.id,
        name: newRunName || `${sourceRun.name} (fork)`,
        status: 'running',
        startTime: new Date(),
        parentRunId: sourceRunId,
        notes: `Forked from run ${sourceRunId}`,
        tags: ['forked', ...(sourceRun.tags || [])],
        sourceType: sourceRun.sourceType || 'manual',
        sourceUri: sourceRun.sourceUri,
        sourceCommit: sourceRun.sourceCommit,
        sourceEntry: sourceRun.sourceEntry,
        datasetVersion: sourceRun.datasetVersion,
        hyperParameters: {
          create: newRunHyperParams.map((hp) => ({
            id: uuidv4(),
            name: hp.name,
            value: hp.value,
            type: hp.type,
          })),
        },
      },
    });

    await this.prisma.experimentRun.update({
      where: { id: sourceRunId },
      data: {
        childRunIds: {
          push: newRun.id,
        },
      },
    });

    await this.prisma.lineageEdge.create({
      data: {
        id: uuidv4(),
        sourceRunId: sourceRunId,
        targetRunId: newRun.id,
        type: 'parent_child',
        relationship: 'variant',
      },
    });

    logger.info(
      { sourceRunId, newExperimentId: newExperiment.id, newRunId: newRun.id },
      'Experiment forked successfully',
    );

    return {
      newExperimentId: newExperiment.id,
      newRunId: newRun.id,
      parentRunId: sourceRunId,
      baselineRunId: sourceRunId,
    };
  }

  async getLineageStats(projectId?: string): Promise<LineageStats> {
    const where = projectId ? { experiment: { projectId } } : {};

    const allRuns = await this.prisma.experimentRun.findMany({
      where,
      select: {
        id: true,
        parentRunId: true,
        status: true,
      },
    });

    const graph = new Graph({ directed: true });

    for (const run of allRuns) {
      if (!graph.hasNode(run.id)) {
        graph.addNode(run.id);
      }
      if (run.parentRunId) {
        if (!graph.hasNode(run.parentRunId)) {
          graph.addNode(run.parentRunId);
        }
        if (!graph.hasEdge(run.parentRunId, run.id)) {
          graph.addDirectedEdge(run.parentRunId, run.id);
        }
      }
    }

    let maxChainDepth = 0;
    let totalLineageChains = 0;
    const forkCounts: Record<string, number> = {};
    let mostForkedRunId: string | undefined;
    let maxForkCount = 0;

    const rootNodes: string[] = [];
    graph.forEachNode((node) => {
      if (graph.inDegree(node) === 0) {
        rootNodes.push(node);
        totalLineageChains++;
      }
    });

    for (const root of rootNodes) {
      let maxDepthFromRoot = 0;
      try {
        bfsFromNode(graph, root, (_node: string, _depth: number, depth: number) => {
          maxDepthFromRoot = Math.max(maxDepthFromRoot, depth);
        });
      } catch {
        // ignore
      }
      maxChainDepth = Math.max(maxChainDepth, maxDepthFromRoot);
    }

    graph.forEachNode((node) => {
      const outDegree = graph.outDegree(node);
      forkCounts[node] = outDegree;
      if (outDegree > maxForkCount) {
        maxForkCount = outDegree;
        mostForkedRunId = node;
      }
    });

    const completedRuns = allRuns.filter((r) => r.status === 'completed').length;
    const successRate = allRuns.length > 0 ? completedRuns / allRuns.length : 0;

    return {
      totalLineageChains,
      maxChainDepth,
      avgImprovementPerGeneration: {},
      mostForkedRunId,
      forkCount: maxForkCount,
      successRate,
    };
  }
}

export const experimentLineageService = new ExperimentLineageService(prisma);

export async function registerLineageRoutes(fastify: any): Promise<void> {
  const service = experimentLineageService;

  fastify.get('/api/v1/runs/:id/evolution-tree', async (request: any) => {
    const result = await service.getEvolutionTree({
      runId: request.params.id,
      depth: request.query.depth ? parseInt(request.query.depth) : 3,
      direction: request.query.direction || 'both',
      primaryMetric: request.query.primaryMetric,
      improvementDirection: request.query.improvementDirection || 'higher',
    });
    return result;
  });

  fastify.get('/api/v1/experiments/:id/evolution-tree', async (request: any) => {
    const result = await service.getEvolutionTree({
      experimentId: request.params.id,
      depth: request.query.depth ? parseInt(request.query.depth) : 3,
      direction: request.query.direction || 'both',
      primaryMetric: request.query.primaryMetric,
      improvementDirection: request.query.improvementDirection || 'higher',
    });
    return result;
  });

  fastify.post('/api/v1/lineage/compare', async (request: FastifyRequest) => {
    return service.compareLineage(request.body as LineageCompareRequest);
  });

  fastify.post('/api/v1/experiments/fork', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.forkExperiment(request.body as ExperimentForkRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/lineage/stats', async (
    request: FastifyRequest<{ Querystring: { projectId?: string } }>,
  ) => {
    return service.getLineageStats(request.query.projectId);
  });
}
