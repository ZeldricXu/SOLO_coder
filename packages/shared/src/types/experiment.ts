import type { Status, PaginatedResponse, MetricValue, HyperParameter, LineageGraph } from './common';

export interface Experiment {
  id: string;
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  tags: string[];
  status: Status;
  createdAt: number;
  updatedAt: number;
  runs: ExperimentRun[];
  bestRun?: ExperimentRun;
  metadata: Record<string, unknown>;
}

export interface ExperimentRun {
  id: string;
  experimentId: string;
  name: string;
  status: 'running' | 'completed' | 'failed' | 'killed';
  startTime: number;
  endTime?: number;
  durationMs?: number;
  hyperParameters: HyperParameter[];
  metrics: MetricValue[];
  artifactPaths: string[];
  modelVersionId?: string;
  datasetVersion?: string;
  parentRunId?: string;
  childRunIds: string[];
  notes?: string;
  tags: string[];
  source?: ExperimentSource;
}

export interface ExperimentSource {
  type: 'git' | 'notebook' | 'script' | 'manual';
  uri?: string;
  commitHash?: string;
  entryPoint?: string;
}

export interface ExperimentListRequest {
  name?: string;
  projectId?: string;
  ownerId?: string;
  team?: string;
  tags?: string[];
  status?: Status;
  page?: number;
  pageSize?: number;
}

export type ExperimentListResponse = PaginatedResponse<Experiment>;

export interface RunListRequest {
  experimentId?: string;
  status?: ExperimentRun['status'];
  parentRunId?: string;
  tags?: string[];
  page?: number;
  pageSize?: number;
}

export type RunListResponse = PaginatedResponse<ExperimentRun>;

export interface ExperimentCreateRequest {
  name: string;
  description?: string;
  projectId: string;
  ownerId: string;
  team: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface RunCreateRequest {
  experimentId: string;
  name: string;
  hyperParameters?: HyperParameter[];
  tags?: string[];
  notes?: string;
  source?: ExperimentSource;
  datasetVersion?: string;
  parentRunId?: string;
}

export interface RunUpdateRequest {
  status?: ExperimentRun['status'];
  endTime?: number;
  metrics?: MetricValue[];
  artifactPaths?: string[];
  modelVersionId?: string;
  notes?: string;
  tags?: string[];
}

export interface MetricChartData {
  metricName: string;
  runIds: string[];
  dataPoints: {
    runId: string;
    x: number;
    y: number;
    timestamp: number;
  }[];
}

export interface ExperimentComparison {
  runIds: string[];
  hyperParameters: {
    name: string;
    values: Record<string, string | number | boolean | null>;
  }[];
  metrics: {
    name: string;
    values: Record<string, number>;
    best: { runId: string; value: number };
  }[];
}

export type LineageResponse = LineageGraph;
