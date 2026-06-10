'use client';

import { useState, useEffect } from 'react';
import { Plus, GitMerge, Play, Clock, CheckCircle2, XCircle, Settings, Trash2 } from 'lucide-react';
import ReactFlow, { Background, Controls, MiniMap, Node, Edge, Handle, Position } from 'reactflow';
import 'reactflow/dist/style.css';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatDate, formatRelativeTime, getStatusColor, cn } from '@/lib/utils';
import type { ModelPipeline, PipelineStep } from '@mlops/shared';

function PipelineNode({ data }: { data: any }) {
  return (
    <div className={cn(
      'px-4 py-3 rounded-xl border-2 shadow-sm min-w-[180px]',
      data.type === 'model' ? 'bg-blue-50 border-blue-300' :
      data.type === 'transform' ? 'bg-purple-50 border-purple-300' :
      data.type === 'condition' ? 'bg-amber-50 border-amber-300' :
      'bg-green-50 border-green-300'
    )}>
      <Handle type="target" position={Position.Left} className="!bg-gray-400 !w-3 !h-3" />
      <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">{data.type}</div>
      <div className="font-semibold text-sm">{data.label}</div>
      {data.modelId && (
        <div className="text-xs text-gray-500 mt-1 font-mono">{data.modelId.slice(0, 8)}...</div>
      )}
      <Handle type="source" position={Position.Right} className="!bg-gray-400 !w-3 !h-3" />
    </div>
  );
}

const nodeTypes = {
  pipelineNode: PipelineNode,
};

export default function PipelinesPage() {
  const [pipelines, setPipelines] = useState<ModelPipeline[]>([]);
  const [selectedPipeline, setSelectedPipeline] = useState<ModelPipeline | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showRunModal, setShowRunModal] = useState(false);
  const [runInputs, setRunInputs] = useState('{}');
  const [runResult, setRunResult] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [graphData, setGraphData] = useState<{ nodes: Node[]; edges: Edge[] }>({ nodes: [], edges: [] });

  useEffect(() => {
    fetchPipelines();
  }, []);

  const fetchPipelines = async () => {
    try {
      const res = await api.pipelines.list({ pageSize: 20 });
      setPipelines(res.data.data || []);
    } catch (error) {
      console.error('Failed to fetch pipelines:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleSelectPipeline = async (pipeline: ModelPipeline) => {
    setSelectedPipeline(pipeline);
    buildPipelineGraph(pipeline);
  };

  const buildPipelineGraph = (pipeline: ModelPipeline) => {
    const nodes: Node[] = [];
    const edges: Edge[] = [];
    const stepMap = new Map<string, PipelineStep>();

    pipeline.steps.forEach((step, index) => {
      stepMap.set(step.id, step);
      nodes.push({
        id: step.id,
        type: 'pipelineNode',
        position: { x: index * 220, y: 0 },
        data: {
          label: step.name,
          type: step.type,
          modelId: step.modelId,
        },
      });
    });

    pipeline.steps.forEach((step) => {
      (step.dependsOn || []).forEach((depId) => {
        edges.push({
          id: `${depId}-${step.id}`,
          source: depId,
          target: step.id,
          animated: true,
          style: { stroke: '#94a3b8', strokeWidth: 2 },
        });
      });
    });

    if (edges.length === 0 && pipeline.steps.length > 1) {
      for (let i = 0; i < pipeline.steps.length - 1; i++) {
        edges.push({
          id: `edge-${i}`,
          source: pipeline.steps[i]!.id,
          target: pipeline.steps[i + 1]!.id,
          animated: true,
          style: { stroke: '#94a3b8', strokeWidth: 2 },
        });
      }
    }

    setGraphData({ nodes, edges });
  };

  const handleCreatePipeline = async () => {
    const name = prompt('Enter pipeline name:');
    if (!name) return;

    try {
      await api.pipelines.create({
        name,
        description: 'Created from dashboard',
        projectId: 'proj-1',
        ownerId: 'admin',
        team: 'data-science',
        entryPoint: 'step-1',
        outputStep: 'step-1',
        steps: [
          {
            id: 'step-1',
            name: 'First Step',
            type: 'model',
            inputMapping: { type: 'direct', mappings: [] },
            outputMapping: { type: 'direct', mappings: [] },
            dependsOn: [],
            enabled: true,
          },
        ],
      });
      toast.success('Pipeline created');
      fetchPipelines();
      setShowCreateModal(false);
    } catch (error) {
      toast.error('Failed to create pipeline');
    }
  };

  const handleRunPipeline = async () => {
    if (!selectedPipeline) return;

    try {
      const inputs = JSON.parse(runInputs);
      const res = await api.pipelines.run({
        pipelineId: selectedPipeline.id,
        inputs,
      });
      setRunResult(res.data);
      toast.success('Pipeline executed successfully');
    } catch (error) {
      toast.error('Failed to run pipeline');
    }
  };

  const handleDeletePipeline = async (id: string) => {
    if (!confirm('Delete this pipeline?')) return;
    try {
      await api.pipelines.delete(id);
      toast.success('Pipeline deleted');
      fetchPipelines();
      if (selectedPipeline?.id === id) {
        setSelectedPipeline(null);
      }
    } catch (error) {
      toast.error('Failed to delete pipeline');
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center p-12">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Model Pipelines</h1>
          <p className="text-gray-500 mt-1">Build and manage multi-model inference pipelines</p>
        </div>
        <button
          onClick={() => setShowCreateModal(true)}
          className="btn-primary flex items-center gap-2"
        >
          <Plus className="w-4 h-4" /> New Pipeline
        </button>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center">
              <GitMerge className="w-5 h-5 text-blue-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">{pipelines.length}</div>
              <div className="text-sm text-gray-500">Total Pipelines</div>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
              <CheckCircle2 className="w-5 h-5 text-green-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">
                {pipelines.filter((p) => p.status === 'active').length}
              </div>
              <div className="text-sm text-gray-500">Active</div>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-amber-100 rounded-lg flex items-center justify-center">
              <Clock className="w-5 h-5 text-amber-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">
                {pipelines.filter((p) => p.status === 'draft').length}
              </div>
              <div className="text-sm text-gray-500">Drafts</div>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
              <Play className="w-5 h-5 text-purple-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">
                {pipelines.reduce((sum, p) => sum + (p.runCount || 0), 0)}
              </div>
              <div className="text-sm text-gray-500">Total Runs</div>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-1 space-y-3">
          <h2 className="font-semibold">Pipelines</h2>
          <div className="space-y-2">
            {pipelines.map((pipeline) => (
              <div
                key={pipeline.id}
                className={cn(
                  'card cursor-pointer transition-all hover:shadow-md',
                  selectedPipeline?.id === pipeline.id && 'ring-2 ring-primary-500'
                )}
                onClick={() => handleSelectPipeline(pipeline)}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
                      <GitMerge className="w-4 h-4 text-blue-600" />
                    </div>
                    <div>
                      <div className="font-medium">{pipeline.name}</div>
                      <div className="text-xs text-gray-500">
                        {pipeline.steps.length} steps
                      </div>
                    </div>
                  </div>
                  <span className={cn('badge', getStatusColor(pipeline.status))}>
                    {pipeline.status}
                  </span>
                </div>
                {pipeline.description && (
                  <p className="text-xs text-gray-500 mt-2">{pipeline.description}</p>
                )}
                <div className="flex items-center justify-between mt-3 pt-3 border-t border-gray-100">
                  <span className="text-xs text-gray-400">
                    {formatRelativeTime(pipeline.updatedAt)}
                  </span>
                  <div className="flex gap-1">
                    <button
                      onClick={(e) => { e.stopPropagation(); setShowRunModal(true); }}
                      className="p-1.5 hover:bg-gray-100 rounded"
                      title="Run pipeline"
                    >
                      <Play className="w-4 h-4 text-green-500" />
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleDeletePipeline(pipeline.id); }}
                      className="p-1.5 hover:bg-gray-100 rounded"
                      title="Delete"
                    >
                      <Trash2 className="w-4 h-4 text-red-500" />
                    </button>
                  </div>
                </div>
              </div>
            ))}
            {pipelines.length === 0 && (
              <div className="card text-center py-8 text-gray-500">
                <GitMerge className="w-12 h-12 mx-auto mb-3 text-gray-300" />
                <p>No pipelines yet</p>
                <p className="text-sm">Create your first pipeline</p>
              </div>
            )}
          </div>
        </div>

        <div className="lg:col-span-2">
          {selectedPipeline ? (
            <div className="space-y-4">
              <div className="card">
                <div className="flex items-center justify-between">
                  <div>
                    <h2 className="font-semibold text-lg">{selectedPipeline.name}</h2>
                    <p className="text-sm text-gray-500">{selectedPipeline.description}</p>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={() => setShowRunModal(true)}
                      className="btn-primary flex items-center gap-2"
                    >
                      <Play className="w-4 h-4" /> Run
                    </button>
                  </div>
                </div>

                <div className="grid grid-cols-3 gap-4 mt-6">
                  <div className="bg-gray-50 rounded-lg p-3">
                    <div className="text-xs text-gray-500">Steps</div>
                    <div className="text-xl font-bold">{selectedPipeline.steps.length}</div>
                  </div>
                  <div className="bg-gray-50 rounded-lg p-3">
                    <div className="text-xs text-gray-500">Runs</div>
                    <div className="text-xl font-bold">{selectedPipeline.runCount || 0}</div>
                  </div>
                  <div className="bg-gray-50 rounded-lg p-3">
                    <div className="text-xs text-gray-500">Avg Latency</div>
                    <div className="text-xl font-bold">
                      {selectedPipeline.avgLatencyMs
                        ? `${selectedPipeline.avgLatencyMs.toFixed(0)}ms`
                        : '-'}
                    </div>
                  </div>
                </div>
              </div>

              <div className="card">
                <h3 className="font-semibold mb-4">Pipeline Graph</h3>
                <div className="h-[300px] border border-gray-200 rounded-lg">
                  <ReactFlow
                    nodes={graphData.nodes}
                    edges={graphData.edges}
                    nodeTypes={nodeTypes}
                    fitView
                    nodesDraggable
                  >
                    <Background />
                    <Controls />
                    <MiniMap />
                  </ReactFlow>
                </div>
              </div>

              <div className="card">
                <h3 className="font-semibold mb-4">Steps</h3>
                <div className="space-y-3">
                  {selectedPipeline.steps.map((step, idx) => (
                    <div key={step.id} className="flex items-center gap-4 p-3 bg-gray-50 rounded-lg">
                      <div className="w-8 h-8 bg-blue-100 rounded-full flex items-center justify-center text-sm font-bold">
                        {idx + 1}
                      </div>
                      <div className="flex-1">
                        <div className="font-medium">{step.name}</div>
                        <div className="text-xs text-gray-500">
                          Type: {step.type}
                          {step.modelId && ` · Model: ${step.modelId.slice(0, 12)}...`}
                        </div>
                      </div>
                      <span className={cn(
                        'badge',
                        step.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                      )}>
                        {step.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="card h-full flex flex-col items-center justify-center py-16 text-gray-500">
              <GitMerge className="w-16 h-16 mb-4 text-gray-300" />
              <p className="font-medium">Select a pipeline to view details</p>
              <p className="text-sm">Click on a pipeline from the list</p>
            </div>
          )}
        </div>
      </div>

      {showRunModal && selectedPipeline && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-xl">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-xl font-bold">Run Pipeline</h2>
              <button
                onClick={() => { setShowRunModal(false); setRunResult(null); }}
                className="btn-secondary"
              >
                Close
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Input Data (JSON)
                </label>
                <textarea
                  value={runInputs}
                  onChange={(e) => setRunInputs(e.target.value)}
                  className="input font-mono text-sm h-40"
                  placeholder='{"feature1": 1.0, "feature2": 2.0}'
                />
              </div>

              <button onClick={handleRunPipeline} className="btn-primary w-full">
                <Play className="w-4 h-4 inline mr-2" />
                Execute Pipeline
              </button>

              {runResult && (
                <div className="mt-4">
                  <h3 className="font-medium mb-2">Result</h3>
                  <div className={cn(
                    'p-4 rounded-lg',
                    runResult.success ? 'bg-green-50' : 'bg-red-50'
                  )}>
                    <div className="flex items-center gap-2 mb-2">
                      {runResult.success ? (
                        <CheckCircle2 className="w-5 h-5 text-green-500" />
                      ) : (
                        <XCircle className="w-5 h-5 text-red-500" />
                      )}
                      <span className={cn(
                        'font-medium',
                        runResult.success ? 'text-green-700' : 'text-red-700'
                      )}>
                        {runResult.success ? 'Success' : 'Failed'}
                      </span>
                      <span className="text-sm text-gray-500 ml-auto">
                        {runResult.totalLatencyMs}ms
                      </span>
                    </div>
                    <pre className="text-xs bg-white p-3 rounded border overflow-x-auto">
                      {JSON.stringify(runResult.outputs, null, 2)}
                    </pre>
                    {runResult.stepResults && (
                      <div className="mt-3 space-y-1">
                        {runResult.stepResults.map((sr: any, i: number) => (
                          <div key={i} className="text-xs flex items-center gap-2">
                            <span className={cn(
                              'w-2 h-2 rounded-full',
                              sr.success ? 'bg-green-500' : 'bg-red-500'
                            )} />
                            <span className="text-gray-600">{sr.stepName}</span>
                            <span className="text-gray-400 ml-auto">{sr.latencyMs}ms</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
