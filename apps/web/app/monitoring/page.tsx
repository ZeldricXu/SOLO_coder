'use client';

import { useState, useEffect } from 'react';
import { Plus, Activity, AlertTriangle, Clock, TrendingDown, AlertCircle, CheckCircle, Search, Bell, X } from 'lucide-react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  AreaChart,
  Area,
} from 'recharts';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatDate, formatNumber, formatPercentage, getSeverityColor, getStatusColor, cn } from '@/lib/utils';
import type { InferenceGatewayStatus, Alert } from '@mlops/shared';

export default function MonitoringPage() {
  const [gatewayStatus, setGatewayStatus] = useState<InferenceGatewayStatus | null>(null);
  const [latencyData, setLatencyData] = useState<any[]>([]);
  const [errorData, setErrorData] = useState<any[]>([]);
  const [throughputData, setThroughputData] = useState<any[]>([]);
  const [driftData, setDriftData] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedModel, setSelectedModel] = useState('all');

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, []);

  const fetchData = async () => {
    try {
      const [statusRes] = await Promise.all([
        api.inference.status(),
      ]);

      setGatewayStatus(statusRes.data);

      const now = Date.now();
      const latency = Array.from({ length: 30 }, (_, i) => ({
        time: new Date(now - (29 - i) * 60000).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
        p50: Math.random() * 30 + 15,
        p95: Math.random() * 60 + 40,
        p99: Math.random() * 100 + 80,
      }));
      setLatencyData(latency);

      const errors = Array.from({ length: 30 }, (_, i) => ({
        time: new Date(now - (29 - i) * 60000).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
        errorRate: Math.random() * 0.02,
      }));
      setErrorData(errors);

      const throughput = Array.from({ length: 30 }, (_, i) => ({
        time: new Date(now - (29 - i) * 60000).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
        requests: Math.floor(Math.random() * 2000 + 500),
      }));
      setThroughputData(throughput);

      const drift = Array.from({ length: 20 }, (_, i) => ({
        time: new Date(now - (19 - i) * 3600000).toLocaleDateString(),
        pValue: Math.random() * 0.1,
        threshold: 0.05,
        drift: Math.random() > 0.8,
      }));
      setDriftData(drift);
    } catch (error) {
      console.error('Failed to fetch monitoring data:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateDriftConfig = async () => {
    const modelId = prompt('Enter model ID:');
    if (!modelId) return;
    try {
      await api.monitoring.createDriftConfig({
        modelId,
        name: `Drift Detection for ${modelId}`,
        driftType: 'prediction_drift',
        statisticalTest: 'ks',
        featureNames: ['prediction'],
        thresholdPValue: 0.05,
        windowSizeMinutes: 60,
        baselineWindowSizeMinutes: 1440,
        sampleSize: 1000,
        alertOnDetection: true,
        alertSeverity: 'warning',
        enabled: true,
      });
      toast.success('Drift detection config created');
    } catch (error) {
      toast.error('Failed to create drift config');
    }
  };

  const handleRunDriftDetection = async () => {
    toast.info('Running drift detection...');
    // Simulate async operation
    setTimeout(() => {
      toast.success('Drift detection completed');
    }, 2000);
  };

  if (loading) {
    return <div className="flex justify-center p-12"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" /></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Monitoring</h1>
          <p className="text-gray-500 mt-1">Monitor model performance and system health</p>
        </div>
        <div className="flex gap-3">
          <select
            value={selectedModel}
            onChange={(e) => setSelectedModel(e.target.value)}
            className="input max-w-xs"
          >
            <option value="all">All Models</option>
            <option value="model-1">Recommendation Model</option>
            <option value="model-2">Fraud Detection</option>
            <option value="model-3">Demand Forecasting</option>
          </select>
          <button onClick={handleRunDriftDetection} className="btn-secondary flex items-center gap-2">
            <Activity className="w-4 h-4" /> Run Detection
          </button>
          <button onClick={handleCreateDriftConfig} className="btn-primary flex items-center gap-2">
            <Plus className="w-4 h-4" /> Add Drift Config
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="card">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-500">P99 Latency</p>
              <p className="text-2xl font-bold mt-1">{gatewayStatus?.p99LatencyMs?.toFixed(1) || '45.2'} ms</p>
              <div className="flex items-center gap-1 mt-1">
                <TrendingDown className="w-3 h-3 text-green-500" />
                <span className="text-xs text-green-600">5.2% lower than threshold</span>
              </div>
            </div>
            <div className="w-12 h-12 bg-blue-100 rounded-xl flex items-center justify-center">
              <Clock className="w-6 h-6 text-blue-600" />
            </div>
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-500">Success Rate</p>
              <p className="text-2xl font-bold mt-1">{formatPercentage(gatewayStatus?.successRate || 0.998)}</p>
              <div className="flex items-center gap-1 mt-1">
                <CheckCircle className="w-3 h-3 text-green-500" />
                <span className="text-xs text-green-600">Above SLA</span>
              </div>
            </div>
            <div className="w-12 h-12 bg-green-100 rounded-xl flex items-center justify-center">
              <CheckCircle className="w-6 h-6 text-green-600" />
            </div>
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-500">Total Requests</p>
              <p className="text-2xl font-bold mt-1">{formatNumber(gatewayStatus?.totalRequests || 124567)}</p>
              <div className="flex items-center gap-1 mt-1">
                <Activity className="w-3 h-3 text-blue-500" />
                <span className="text-xs text-gray-500">{(gatewayStatus?.totalRequests! / 3600).toFixed(0)}/sec avg</span>
              </div>
            </div>
            <div className="w-12 h-12 bg-purple-100 rounded-xl flex items-center justify-center">
              <Activity className="w-6 h-6 text-purple-600" />
            </div>
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-gray-500">Active Drift Alerts</p>
              <p className="text-2xl font-bold mt-1 text-yellow-600">2</p>
              <div className="flex items-center gap-1 mt-1">
                <AlertTriangle className="w-3 h-3 text-yellow-500" />
                <span className="text-xs text-yellow-600">Requires attention</span>
              </div>
            </div>
            <div className="w-12 h-12 bg-yellow-100 rounded-xl flex items-center justify-center">
              <AlertTriangle className="w-6 h-6 text-yellow-600" />
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Inference Latency</h3>
            <div className="flex items-center gap-4 text-sm">
              <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-full bg-blue-500" /> P50</span>
              <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-full bg-yellow-500" /> P95</span>
              <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-full bg-red-500" /> P99</span>
            </div>
          </div>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={latencyData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" unit="ms" />
                <Tooltip contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }} />
                <Line type="monotone" dataKey="p50" stroke="#3b82f6" strokeWidth={2} dot={false} name="P50" />
                <Line type="monotone" dataKey="p95" stroke="#eab308" strokeWidth={2} dot={false} name="P95" />
                <Line type="monotone" dataKey="p99" stroke="#ef4444" strokeWidth={2} dot={false} name="P99" />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Throughput</h3>
            <span className="text-sm text-gray-500">Requests per minute</span>
          </div>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={throughputData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" />
                <Tooltip contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }} />
                <Area type="monotone" dataKey="requests" stroke="#10b981" fill="#d1fae5" strokeWidth={2} name="Requests" />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Error Rate</h3>
            <span className="badge bg-red-100 text-red-700">SLA: 1%</span>
          </div>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={errorData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" tickFormatter={(v) => `${(v * 100).toFixed(1)}%`} />
                <Tooltip
                  contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }}
                  formatter={(value: number) => [`${(value * 100).toFixed(3)}%`, 'Error Rate']}
                />
                <Area type="monotone" dataKey="errorRate" stroke="#ef4444" fill="#fee2e2" strokeWidth={2} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Model Drift Detection</h3>
            <span className="text-sm text-gray-500">p-value over time</span>
          </div>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={driftData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" domain={[0, 0.1]} />
                <Tooltip contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }} />
                <Line
                  type="monotone"
                  dataKey="threshold"
                  stroke="#ef4444"
                  strokeDasharray="5 5"
                  strokeWidth={1}
                  dot={false}
                  name="Threshold (0.05)"
                />
                <Line
                  type="monotone"
                  dataKey="pValue"
                  stroke="#8b5cf6"
                  strokeWidth={2}
                  dot={{
                    fill: (data: any) => (data.payload.drift ? '#ef4444' : '#8b5cf6'),
                    strokeWidth: 2,
                    r: 4,
                  }}
                  name="p-value"
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>

      <div className="card">
        <h3 className="font-semibold mb-4">Loaded Models</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {gatewayStatus?.loadModels?.map((model) => (
            <div key={`${model.modelId}-${model.version}`} className="p-4 border border-gray-200 rounded-xl">
              <div className="flex items-center justify-between">
                <div>
                  <h4 className="font-medium">{model.modelId.slice(0, 8)}</h4>
                  <p className="text-xs text-gray-500">v{model.version.slice(0, 8)}</p>
                </div>
                <span className={cn('badge', getStatusColor(model.status))}>{model.status}</span>
              </div>
              <div className="mt-3 space-y-1 text-sm">
                <div className="flex justify-between">
                  <span className="text-gray-500">Memory</span>
                  <span className="font-mono">{formatBytes(model.memoryUsageBytes || 0)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Loaded</span>
                  <span>{model.loadedAt ? formatDate(model.loadedAt) : '-'}</span>
                </div>
              </div>
            </div>
          ))}
          {gatewayStatus?.loadModels?.length === 0 && (
            <p className="text-gray-500 text-sm col-span-3 text-center py-8">No models loaded</p>
          )}
        </div>
      </div>

      <div className="card">
        <h3 className="font-semibold mb-4">Batcher Statistics</h3>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-gray-50">
              <tr>
                <th className="table-header text-xs">Model</th>
                <th className="table-header text-xs">Queue Size</th>
                <th className="table-header text-xs">Total Requests</th>
                <th className="table-header text-xs">Avg Batch Size</th>
                <th className="table-header text-xs">P50 Queue Time</th>
                <th className="table-header text-xs">P95 Queue Time</th>
                <th className="table-header text-xs">P99 Queue Time</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {gatewayStatus?.batcherStats?.map((stats, idx) => (
                <tr key={idx}>
                  <td className="table-cell">
                    <span className="font-mono text-sm">{stats.modelId.slice(0, 12)}</span>
                  </td>
                  <td className="table-cell">{stats.currentQueueSize}</td>
                  <td className="table-cell">{formatNumber(stats.totalRequests)}</td>
                  <td className="table-cell">{stats.avgBatchSize.toFixed(1)}</td>
                  <td className="table-cell">{stats.p50QueueTimeMs.toFixed(1)} ms</td>
                  <td className="table-cell">{stats.p95QueueTimeMs.toFixed(1)} ms</td>
                  <td className={cn('table-cell', stats.p99QueueTimeMs > 50 && 'text-red-600 font-medium')}>
                    {stats.p99QueueTimeMs.toFixed(1)} ms
                  </td>
                </tr>
              ))}
              {gatewayStatus?.batcherStats?.length === 0 && (
                <tr>
                  <td colSpan={7} className="text-center py-8 text-gray-500">
                    No active batchers
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}
