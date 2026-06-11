export interface MetricDelta {
  metricName: string;
  parentValue: number;
  currentValue: number;
  absoluteChange: number;
  relativeChange: number;
  isImprovement: boolean;
}

export interface HyperParameterDelta {
  name: string;
  parentValue: unknown;
  currentValue: unknown;
  changed: boolean;
}

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
  status: string;
  tags: string[];
  notes?: string;
  depth: number;
  direction: 'current' | 'up' | 'down';
  hasParent: boolean;
  hasChildren: boolean;
  metrics?: Record<string, number>;
  hyperParameters?: Record<string, unknown>;
}

export interface LineageEdge {
  id: string;
  source: string;
  target: string;
  type: 'parent_child' | 'baseline_variant' | 'variant_finetune';
  relationship: 'direct' | 'baseline' | 'derived';
  metricDeltas: Record<string, MetricDelta>;
  hyperParamDeltas?: Record<string, HyperParameterDelta>;
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
    metricName: string;
    value: number;
    improvement: number;
  };
  generationSummary: LineageGenerationSummary[];
}

export interface LineageGenerationSummary {
  generation: number;
  runCount: number;
  primaryMetricValue?: number;
  bestRunId?: string;
  avgImprovement?: number;
}

export interface LineageQueryRequest {
  runId?: string;
  experimentId?: string;
  depth?: number;
  direction?: 'up' | 'down' | 'both';
  includeMetrics?: boolean;
  primaryMetric?: string;
  improvementDirection?: 'higher' | 'lower';
}

export interface LineageCompareRequest {
  runIds: string[];
  metrics?: string[];
  includeHyperParameters?: boolean;
}

export interface LineageCompareResponse {
  runIds: string[];
  metrics: {
    name: string;
    values: Record<string, number>;
    best: { runId: string; value: number };
  }[];
  hyperParameters: {
    name: string;
    values: Record<string, unknown>;
    changed: boolean;
  }[];
  relationships: {
    from: string;
    to: string;
    type: string;
  }[];
}

export interface LineageStats {
  totalNodes: number;
  totalEdges: number;
  maxDepth: number;
  avgBranchingFactor: number;
  longestPath: string[];
}

export interface ExperimentForkRequest {
  sourceRunId: string;
  name: string;
  description?: string;
  hyperParameterOverrides?: Record<string, unknown>;
  tags?: string[];
  notes?: string;
}

export interface RunForkResult {
  newRunId: string;
  newExperimentId: string;
  sourceRunId: string;
  inheritedHyperParameters: Record<string, unknown>;
  overriddenHyperParameters: Record<string, unknown>;
}

export interface ExperimentCreateWithParentRequest {
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team?: string;
  parentExperimentId: string;
  parentRunId?: string;
  variantType: 'baseline' | 'variant' | 'finetune';
  tags?: string[];
  metadata?: Record<string, unknown>;
}
