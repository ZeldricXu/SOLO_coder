import { create } from 'zustand';
import { api } from '@/lib/api';
import type { Experiment, ExperimentRun } from '@mlops/shared';
import type { Node, Edge } from 'reactflow';

interface EvolutionTreeNodeData {
  runId: string;
  runName: string;
  experimentName: string;
  metricDeltas: Record<string, any>;
  status: string;
  depth: number;
  direction: 'current' | 'up' | 'down';
}

interface LineageVisualizationData {
  nodes: Node[];
  edges: Edge[];
}

interface EvolutionTreeVisualizationData {
  rootNodes: any[];
  allNodes: Record<string, EvolutionTreeNodeData>;
  edges: any[];
  maxDepth: number;
  totalExperiments: number;
  totalRuns: number;
  generationSummary: any[];
  reactFlowNodes: Node[];
  reactFlowEdges: Edge[];
  bestPerformer?: {
    runId: string;
    metricName: string;
    value: number;
    improvement: number;
  };
}

interface ExperimentComparisonData {
  runIds: string[];
  hyperParameters: any[];
  metrics: any[];
}

interface ExperimentStore {
  experiments: Experiment[];
  runs: ExperimentRun[];
  selectedRun: ExperimentRun | null;
  compareMode: boolean;
  selectedRunIds: string[];
  comparisonData: ExperimentComparisonData | null;
  lineageData: LineageVisualizationData | null;
  evolutionTreeData: EvolutionTreeVisualizationData | null;
  loading: boolean;
  searchTerm: string;
  evolutionDirection: 'both' | 'up' | 'down';
  evolutionDepth: number;
  primaryMetric: string;

  fetchExperimentsAndRuns: () => Promise<void>;
  setSelectedRun: (run: ExperimentRun | null) => void;
  setCompareMode: (mode: boolean) => void;
  toggleRunSelection: (runId: string) => void;
  setSelectedRunIds: (ids: string[]) => void;
  setComparisonData: (data: ExperimentComparisonData | null) => void;
  setLineageData: (data: LineageVisualizationData | null) => void;
  setEvolutionTreeData: (data: EvolutionTreeVisualizationData | null) => void;
  setSearchTerm: (term: string) => void;
  setEvolutionDirection: (direction: 'both' | 'up' | 'down') => void;
  setEvolutionDepth: (depth: number) => void;
  setPrimaryMetric: (metric: string) => void;
  createExperiment: (name: string, projectId: string, ownerId: string, team: string) => Promise<void>;
  startRun: (experimentId: string, name: string, hyperParameters: any[]) => Promise<void>;
  compareRuns: (runIds: string[]) => Promise<void>;
  fetchLineage: (runId: string, depth: number) => Promise<void>;
  fetchEvolutionTree: (runId: string, options: { depth: number; direction: 'both' | 'up' | 'down'; primaryMetric: string; improvementDirection: string }) => Promise<void>;
  clearSelection: () => void;
}

function buildEvolutionTreeLayout(treeData: any, primaryMetric: string): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  const allNodes = treeData.allNodes || {};

  const nodeLevels = new Map<number, string[]>();
  Object.values(allNodes).forEach((node: any) => {
    const depth = node.depth || 0;
    if (!nodeLevels.has(depth)) nodeLevels.set(depth, []);
    nodeLevels.get(depth)!.push(node.runId);
  });

  const sortedLevels = Array.from(nodeLevels.keys()).sort((a, b) => a - b);
  const levelWidth = 280;
  const nodeHeight = 120;

  sortedLevels.forEach((level) => {
    const nodeIds = nodeLevels.get(level)!;
    const startY = (nodeIds.length - 1) * nodeHeight / -2;

    nodeIds.forEach((nodeId, idx) => {
      const nodeData = allNodes[nodeId] as EvolutionTreeNodeData | undefined;
      const metricDelta = nodeData?.metricDeltas?.[primaryMetric];

      nodes.push({
        id: nodeId,
        type: 'evolutionNode',
        position: {
          x: (level - (sortedLevels[0] || 0)) * levelWidth,
          y: startY + idx * nodeHeight,
        },
        data: {
          nodeData: {
            ...nodeData,
            primaryMetric,
            metricDelta,
          },
        },
        style: {
          background: nodeData?.direction === 'current'
            ? '#fef3c7'
            : nodeData?.direction === 'up'
            ? '#dbeafe'
            : '#d1fae5',
          border: `2px solid ${
            nodeData?.direction === 'current'
              ? '#f59e0b'
              : nodeData?.direction === 'up'
              ? '#3b82f6'
              : '#10b981'
          }`,
          borderRadius: '12px',
          padding: '0px',
          width: 220,
        },
      });
    });
  });

  (treeData.edges || []).forEach((edge: any, i: number) => {
    edges.push({
      id: `edge-${i}`,
      source: edge.source,
      target: edge.target,
      animated: true,
      style: { stroke: '#94a3b8', strokeWidth: 2 },
    });
  });

  return { nodes, edges };
}

export const useExperimentStore = create<ExperimentStore>((set, get) => ({
  experiments: [],
  runs: [],
  selectedRun: null,
  compareMode: false,
  selectedRunIds: [],
  comparisonData: null,
  lineageData: null,
  evolutionTreeData: null,
  loading: true,
  searchTerm: '',
  evolutionDirection: 'both',
  evolutionDepth: 3,
  primaryMetric: 'accuracy',

  fetchExperimentsAndRuns: async () => {
    set({ loading: true });
    try {
      const [expRes, runsRes] = await Promise.all([
        api.experiments.list({ pageSize: 10 }),
        api.experiments.listRuns({ pageSize: 20 }),
      ]);
      set({
        experiments: expRes.data.data,
        runs: runsRes.data.data,
        loading: false,
      });
    } catch (error) {
      console.error('Failed to fetch experiments:', error);
      set({ loading: false });
    }
  },

  setSelectedRun: (run) => set({ selectedRun: run }),
  setCompareMode: (mode) => {
    if (!mode) {
      set({ compareMode: false, selectedRunIds: [], comparisonData: null });
    } else {
      set({ compareMode: true });
    }
  },
  toggleRunSelection: (runId) =>
    set((state) => ({
      selectedRunIds: state.selectedRunIds.includes(runId)
        ? state.selectedRunIds.filter((id) => id !== runId)
        : [...state.selectedRunIds, runId],
    })),
  setSelectedRunIds: (ids) => set({ selectedRunIds: ids }),
  setComparisonData: (data) => set({ comparisonData: data }),
  setLineageData: (data) => set({ lineageData: data }),
  setEvolutionTreeData: (data) => set({ evolutionTreeData: data }),
  setSearchTerm: (term) => set({ searchTerm: term }),
  setEvolutionDirection: (direction) => set({ evolutionDirection: direction }),
  setEvolutionDepth: (depth) => set({ evolutionDepth: depth }),
  setPrimaryMetric: (metric) => set({ primaryMetric: metric }),

  createExperiment: async (name, projectId, ownerId, team) => {
    await api.experiments.create({ name, projectId, ownerId, team });
    await get().fetchExperimentsAndRuns();
  },

  startRun: async (experimentId, name, hyperParameters) => {
    await api.experiments.createRun({ experimentId, name, hyperParameters });
    await get().fetchExperimentsAndRuns();
  },

  compareRuns: async (runIds) => {
    const res = await api.experiments.compareRuns(runIds);
    set({ comparisonData: res.data, compareMode: true });
  },

  fetchLineage: async (runId, depth) => {
    const res = await api.experiments.getLineage(runId, depth);
    const nodes: Node[] = res.data.nodes.map((n: any) => ({
      id: n.id,
      data: { label: n.name },
      position: { x: Math.random() * 400, y: Math.random() * 300 },
      style: {
        background: n.type === 'model' ? '#dbeafe' : n.type === 'experiment' ? '#f3e8ff' : '#d1fae5',
        border: '1px solid #d1d5db',
        borderRadius: '8px',
        padding: '8px',
      },
    }));
    const edges: Edge[] = res.data.edges.map((e: any, i: number) => ({
      id: `edge-${i}`,
      source: e.source,
      target: e.target,
      label: e.relation,
    }));
    set({ lineageData: { nodes, edges } });
  },

  fetchEvolutionTree: async (runId, options) => {
    const res = await api.experiments.getEvolutionTree(runId, options);
    const treeData = res.data;
    const { nodes: rfNodes, edges: rfEdges } = buildEvolutionTreeLayout(
      treeData,
      get().primaryMetric
    );
    set({
      evolutionTreeData: { ...treeData, reactFlowNodes: rfNodes, reactFlowEdges: rfEdges },
    });
  },

  clearSelection: () =>
    set({
      selectedRun: null,
      selectedRunIds: [],
      comparisonData: null,
      compareMode: false,
      lineageData: null,
      evolutionTreeData: null,
    }),
}));

export type { EvolutionTreeNodeData, ExperimentComparisonData, LineageVisualizationData, EvolutionTreeVisualizationData };
