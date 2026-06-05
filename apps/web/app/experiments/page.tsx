'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { Plus, FlaskConical, Play, Square, GitBranch, Clock, Tag, TrendingUp, Search, BarChart3 } from 'lucide-react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';
import ReactFlow, { Background, Controls, MiniMap, Node, Edge } from 'reactflow';
import 'reactflow/dist/style.css';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatDate, formatRelativeTime, getStatusColor, cn } from '@/lib/utils';
import type { Experiment, ExperimentRun } from '@mlops/shared';

export default function ExperimentsPage() {
  const [experiments, setExperiments] = useState<Experiment[]>([]);
  const [runs, setRuns] = useState<ExperimentRun[]>([]);
  const [selectedRun, setSelectedRun] = useState<ExperimentRun | null>(null);
  const [compareMode, setCompareMode] = useState(false);
  const [selectedRunIds, setSelectedRunIds] = useState<string[]>([]);
  const [comparisonData, setComparisonData] = useState<any>(null);
  const [showLineage, setShowLineage] = useState(false);
  const [lineageData, setLineageData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      const [expRes, runsRes] = await Promise.all([
        api.experiments.list({ pageSize: 10 }),
        api.experiments.listRuns({ pageSize: 20 }),
      ]);
      setExperiments(expRes.data.data);
      setRuns(runsRes.data.data);
    } catch (error) {
      console.error('Failed to fetch experiments:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateExperiment = async () => {
    const name = prompt('Enter experiment name:');
    if (!name) return;
    try {
      await api.experiments.create({
        name,
        projectId: 'proj-1',
        ownerId: 'admin',
        team: 'data-science',
      });
      toast.success('Experiment created');
      fetchData();
    } catch (error) {
      toast.error('Failed to create experiment');
    }
  };

  const handleStartRun = async () => {
    if (experiments.length === 0) {
      toast.error('Create an experiment first');
      return;
    }
    try {
      await api.experiments.createRun({
        experimentId: experiments[0]!.id,
        name: `Run ${new Date().toLocaleTimeString()}`,
        hyperParameters: [
          { name: 'learning_rate', value: 0.001, type: 'number' },
          { name: 'batch_size', value: 32, type: 'number' },
          { name: 'optimizer', value: 'adam', type: 'string' },
        ],
      });
      toast.success('Run started');
      fetchData();
    } catch (error) {
      toast.error('Failed to start run');
    }
  };

  const handleCompareRuns = async () => {
    if (selectedRunIds.length < 2) {
      toast.error('Select at least 2 runs to compare');
      return;
    }
    try {
      const res = await api.experiments.compareRuns(selectedRunIds);
      setComparisonData(res.data);
      setCompareMode(true);
    } catch (error) {
      toast.error('Failed to compare runs');
    }
  };

  const handleShowLineage = async (runId: string) => {
    try {
      const res = await api.experiments.getLineage(runId, 3);
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
      setLineageData({ nodes, edges });
      setShowLineage(true);
    } catch (error) {
      toast.error('Failed to load lineage');
    }
  };

  const toggleRunSelection = (runId: string) => {
    setSelectedRunIds((prev) =>
      prev.includes(runId) ? prev.filter((id) => id !== runId) : [...prev, runId]
    );
  };

  const filteredRuns = runs.filter((r) =>
    r.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    r.experimentId.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const chartData = selectedRun?.metrics
    ? selectedRun.metrics.reduce((acc: any[], m) => {
        const existing = acc.find((a) => a.step === m.step);
        if (existing) {
          existing[m.name] = m.value;
        } else {
          acc.push({ step: m.step || acc.length, [m.name]: m.value });
        }
        return acc;
      }, [])
    : [];

  if (loading) {
    return <div className="flex justify-center p-12"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" /></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Experiment Tracking</h1>
          <p className="text-gray-500 mt-1">Track, compare, and visualize your ML experiments</p>
        </div>
        <div className="flex gap-3">
          {selectedRunIds.length > 0 && (
            <button onClick={handleCompareRuns} className="btn-secondary flex items-center gap-2">
              <BarChart3 className="w-4 h-4" /> Compare ({selectedRunIds.length})
            </button>
          )}
          <button onClick={handleStartRun} className="btn-secondary flex items-center gap-2">
            <Play className="w-4 h-4" /> New Run
          </button>
          <button onClick={handleCreateExperiment} className="btn-primary flex items-center gap-2">
            <Plus className="w-4 h-4" /> New Experiment
          </button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        {experiments.slice(0, 4).map((exp) => (
          <div key={exp.id} className="card">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
                  <FlaskConical className="w-5 h-5 text-purple-600" />
                </div>
                <div>
                  <h3 className="font-medium">{exp.name}</h3>
                  <p className="text-xs text-gray-500">{exp.runs.length} runs</p>
                </div>
              </div>
              <span className={cn('badge', getStatusColor(exp.status))}>{exp.status}</span>
            </div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold">Recent Runs</h2>
            <div className="flex items-center gap-3">
              <div className="relative">
                <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  placeholder="Search runs..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="input pl-10 w-64"
                />
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={compareMode}
                  onChange={(e) => {
                    setCompareMode(e.target.checked);
                    if (!e.target.checked) {
                      setSelectedRunIds([]);
                      setComparisonData(null);
                    }
                  }}
                  className="rounded"
                />
                Compare mode
              </label>
            </div>
          </div>

          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  {compareMode && <th className="table-header w-12"></th>}
                  <th className="table-header">Name</th>
                  <th className="table-header">Experiment</th>
                  <th className="table-header">Status</th>
                  <th className="table-header">Duration</th>
                  <th className="table-header">Metrics</th>
                  <th className="table-header">Created</th>
                  <th className="table-header">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {filteredRuns.map((run) => (
                  <tr
                    key={run.id}
                    className={cn(
                      'hover:bg-gray-50 cursor-pointer transition-colors',
                      selectedRun?.id === run.id && 'bg-primary-50'
                    )}
                    onClick={() => !compareMode && setSelectedRun(run)}
                  >
                    {compareMode && (
                      <td className="table-cell">
                        <input
                          type="checkbox"
                          checked={selectedRunIds.includes(run.id)}
                          onChange={() => toggleRunSelection(run.id)}
                          onClick={(e) => e.stopPropagation()}
                          className="rounded"
                        />
                      </td>
                    )}
                    <td className="table-cell">
                      <div className="flex items-center gap-2">
                        <GitBranch className="w-4 h-4 text-gray-400" />
                        <span className="font-medium">{run.name}</span>
                      </div>
                    </td>
                    <td className="table-cell text-gray-500">
                      {experiments.find((e) => e.id === run.experimentId)?.name || run.experimentId.slice(0, 8)}
                    </td>
                    <td className="table-cell">
                      <span className={cn('badge', getStatusColor(run.status))}>{run.status}</span>
                    </td>
                    <td className="table-cell text-gray-500">
                      {run.durationMs ? `${(run.durationMs / 1000).toFixed(1)}s` : '-'}
                    </td>
                    <td className="table-cell">
                      {run.metrics.slice(0, 2).map((m, i) => (
                        <span key={i} className="inline-flex items-center gap-1 text-xs mr-2">
                          <span className="text-gray-500">{m.name}:</span>
                          <span className="font-mono font-medium">{m.value.toFixed(4)}</span>
                        </span>
                      ))}
                    </td>
                    <td className="table-cell text-gray-500 text-xs">{formatRelativeTime(run.startTime)}</td>
                    <td className="table-cell">
                      <div className="flex items-center gap-1">
                        <button
                          onClick={(e) => { e.stopPropagation(); handleShowLineage(run.id); }}
                          className="p-1.5 hover:bg-gray-100 rounded"
                          title="View lineage"
                        >
                          <GitBranch className="w-4 h-4 text-gray-500" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {selectedRun && !compareMode && (
          <div className="space-y-6">
            <div className="card">
              <h3 className="font-semibold">{selectedRun.name}</h3>
              <div className="mt-4 space-y-3">
                <div>
                  <h4 className="text-sm font-medium text-gray-700 mb-2">Hyperparameters</h4>
                  <div className="bg-gray-50 rounded-lg p-3 space-y-1">
                    {selectedRun.hyperParameters.map((hp, idx) => (
                      <div key={idx} className="flex justify-between text-sm">
                        <span className="text-gray-500">{hp.name}</span>
                        <span className="font-mono">{String(hp.value)}</span>
                      </div>
                    ))}
                  </div>
                </div>

                <div>
                  <h4 className="text-sm font-medium text-gray-700 mb-2">Tags</h4>
                  <div className="flex flex-wrap gap-2">
                    {selectedRun.tags.map((tag, idx) => (
                      <span key={idx} className="badge bg-gray-100 text-gray-700">{tag}</span>
                    ))}
                  </div>
                </div>

                <div className="flex items-center gap-4 text-sm text-gray-500 pt-3 border-t border-gray-100">
                  <span className="flex items-center gap-1"><Clock className="w-4 h-4" /> {formatDate(selectedRun.startTime)}</span>
                  {selectedRun.datasetVersion && (
                    <span className="flex items-center gap-1"><Tag className="w-4 h-4" /> {selectedRun.datasetVersion.slice(0, 8)}</span>
                  )}
                </div>
              </div>
            </div>

            {chartData.length > 0 && (
              <div className="card">
                <h3 className="font-semibold mb-4">Metrics</h3>
                <div className="h-64">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={chartData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                      <XAxis dataKey="step" tick={{ fontSize: 11 }} stroke="#9ca3af" />
                      <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" />
                      <Tooltip contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }} />
                      <Legend />
                      {Object.keys(chartData[0] || {})
                        .filter((k) => k !== 'step')
                        .map((key, idx) => (
                          <Line
                            key={key}
                            type="monotone"
                            dataKey={key}
                            stroke={['#3b82f6', '#10b981', '#f59e0b', '#ef4444'][idx % 4]}
                            strokeWidth={2}
                            dot={false}
                          />
                        ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>
            )}
          </div>
        )}

        {compareMode && comparisonData && (
          <div className="card lg:col-span-3">
            <h3 className="font-semibold mb-4">Run Comparison</h3>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b border-gray-200">
                    <th className="text-left py-3 px-4 text-sm font-medium text-gray-500">Metric</th>
                    {comparisonData.runIds.map((id: string) => {
                      const run = runs.find((r) => r.id === id);
                      return (
                        <th key={id} className="text-left py-3 px-4 text-sm font-medium">
                          {run?.name || id.slice(0, 8)}
                        </th>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  <tr className="border-b border-gray-100">
                    <td className="py-3 px-4 text-sm text-gray-500 font-semibold" colSpan={comparisonData.runIds.length + 1}>
                      Hyperparameters
                    </td>
                  </tr>
                  {comparisonData.hyperParameters.map((hp: any) => (
                    <tr key={hp.name} className="border-b border-gray-50">
                      <td className="py-2 px-4 text-sm text-gray-600">{hp.name}</td>
                      {comparisonData.runIds.map((id: string) => (
                        <td key={id} className="py-2 px-4 text-sm font-mono">{String(hp.values[id])}</td>
                      ))}
                    </tr>
                  ))}
                  <tr className="border-b border-gray-100">
                    <td className="py-3 px-4 text-sm text-gray-500 font-semibold" colSpan={comparisonData.runIds.length + 1}>
                      Metrics
                    </td>
                  </tr>
                  {comparisonData.metrics.map((metric: any) => (
                    <tr key={metric.name} className="border-b border-gray-50">
                      <td className="py-2 px-4 text-sm text-gray-600">
                        <div className="flex items-center gap-1">
                          {metric.name}
                          <TrendingUp className="w-3 h-3 text-green-500" />
                        </div>
                      </td>
                      {comparisonData.runIds.map((id: string) => (
                        <td
                          key={id}
                          className={cn(
                            'py-2 px-4 text-sm font-mono',
                            id === metric.best.runId && 'bg-green-50 font-bold text-green-700'
                          )}
                        >
                          {metric.values[id]?.toFixed(4) || '-'}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {showLineage && lineageData && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-4xl h-[600px]">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-xl font-bold">Experiment Lineage</h2>
              <button onClick={() => setShowLineage(false)} className="btn-secondary">Close</button>
            </div>
            <ReactFlow nodes={lineageData.nodes} edges={lineageData.edges} fitView>
              <Background />
              <Controls />
              <MiniMap />
            </ReactFlow>
          </div>
        </div>
      )}
    </div>
  );
}
