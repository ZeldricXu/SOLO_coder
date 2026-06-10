import type { ExperimentRun, MetricValue, HyperParameter } from './experiment';

export interface ExperimentLineageNode {
  experimentId: string;
  experimentName: string;
  runId: string;
  runName: string;
  parentRunId?: string;
  baselineRunId?: string;
  metricDeltas: Record<string, MetricDelta>;
  hyperParameterDeltas: Record<string, HyperParameterDelta>;
  createdAt: number;
  status: 'running' | 'completed' | 'failed' | 'killed';
  tags: string[];
  notes?: string;
  depth: number;
  direction: 'up' | 'down' | 'current';
  hasParent: boolean;
  hasChildren: boolean;
}

export interface MetricDelta {
  metricName: string;
  currentValue: number;
  parentValue: number;
  absoluteChange: number;
  relativeChange: number;
  percentageChange: number;
  isImprovement: boolean;
  improvementDirection: 'higher' | 'lower';
  statisticalSignificance?: {
    pValue: number;
    isSignificant: boolean;
    confidenceInterval: [number, number];
  };
}

export interface HyperParameterDelta {
  paramName: string;
  currentValue: unknown;
  parentValue: unknown;
  changed: boolean;
  changeType: 'added' | 'removed' | 'modified';
}

export interface ExperimentEvolutionTree {
  rootNodes: ExperimentLineageNode[];
  allNodes: Record<string, ExperimentLineageNode>;
  edges: LineageEdge[];
  maxDepth: number;
  totalExperiments: number;
  totalRuns: number;
  bestPerformer?: {
    runId: string;
    experimentName: string;
    primaryMetric: string;
    primaryMetricValue: number;
  };
  generationSummary: LineageGenerationSummary[];
}

export interface LineageEdge {
  id: string;
  source: string;
  target: string;
  type: 'parent_child' | 'baseline' | 'derived';
  metricDeltas: Record<string, MetricDelta>;
  relationship: 'direct' | 'baseline' | 'variant';
}

export interface LineageGenerationSummary {
  generation: number;
  runCount: number;
  avgImprovement: Record<string, number>;
  bestRunId?: string;
  primaryMetricValue?: number;
}

export interface LineageQueryRequest {
  experimentId?: string;
  runId?: string;
  depth?: number;
  direction?: 'up' | 'down' | 'both';
  includeMetrics?: string[];
  primaryMetric?: string;
  improvementDirection?: 'higher' | 'lower';
}

export interface LineageCompareRequest {
  runIds: string[];
  primaryMetric?: string;
  improvementDirection?: 'higher' | 'lower';
  includeAllMetrics?: boolean;
}

export interface LineageCompareResponse {
  runs: {
    runId: string;
    experimentId: string;
    experimentName: string;
    runName: string;
    metrics: Record<string, number>;
    hyperParameters: Record<string, unknown>;
    baselineDelta?: Record<string, MetricDelta>;
  }[];
  bestRunId?: string;
  primaryMetric?: string;
  comparisonMatrix: Record<string, Record<string, Record<string, MetricDelta>>>;
}

export interface ExperimentPromoteRequest {
  sourceRunId: string;
  targetExperimentId: string;
  newRunName?: string;
  inheritMetrics?: boolean;
  inheritHyperParameters?: boolean;
  note?: string;
}

export interface ExperimentForkRequest {
  sourceRunId: string;
  newExperimentName: string;
  newRunName?: string;
  description?: string;
  hyperParameterOverrides?: Record<string, unknown>;
  projectId?: string;
  ownerId?: string;
  team?: string;
}

export interface RunForkResult {
  newExperimentId: string;
  newRunId: string;
  parentRunId: string;
  baselineRunId: string;
}

export interface LineageStats {
  totalLineageChains: number;
  maxChainDepth: number;
  avgImprovementPerGeneration: Record<string, number>;
  mostForkedRunId?: string;
  forkCount: number;
  successRate: number;
}
