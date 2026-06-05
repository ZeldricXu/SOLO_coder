'use client';

import { useEffect, useState } from 'react';
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
  BarChart,
  Bar,
} from 'recharts';
import {
  Box,
  FlaskConical,
  Database,
  SplitSquareVertical,
  Activity,
  TrendingUp,
  TrendingDown,
  Clock,
  CheckCircle,
  AlertTriangle,
  ChevronRight,
} from 'lucide-react';
import { api } from '@/lib/api';
import { formatNumber, formatPercentage, formatRelativeTime, cn } from '@/lib/utils';
import type { InferenceGatewayStatus } from '@mlops/shared';

interface StatCardProps {
  title: string;
  value: string;
  change?: number;
  icon: React.ComponentType<{ className?: string }>;
  iconBg: string;
}

function StatCard({ title, value, change, icon: Icon, iconBg }: StatCardProps) {
  return (
    <div className="card">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm text-gray-500">{title}</p>
          <p className="text-2xl font-bold mt-1">{value}</p>
          {change !== undefined && (
            <div className="flex items-center gap-1 mt-1">
              {change >= 0 ? (
                <TrendingUp className="w-4 h-4 text-green-500" />
              ) : (
                <TrendingDown className="w-4 h-4 text-red-500" />
              )}
              <span
                className={cn(
                  'text-sm font-medium',
                  change >= 0 ? 'text-green-600' : 'text-red-600'
                )}
              >
                {Math.abs(change)}%
              </span>
              <span className="text-xs text-gray-400">vs last week</span>
            </div>
          )}
        </div>
        <div className={cn('w-12 h-12 rounded-xl flex items-center justify-center', iconBg)}>
          <Icon className="w-6 h-6 text-white" />
        </div>
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const [inferenceStatus, setInferenceStatus] = useState<InferenceGatewayStatus | null>(null);
  const [models, setModels] = useState<any[]>([]);
  const [experiments, setExperiments] = useState<any[]>([]);
  const [recentActivity, setRecentActivity] = useState<any[]>([]);
  const [latencyData, setLatencyData] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [statusRes, modelsRes, experimentsRes] = await Promise.all([
          api.inference.status(),
          api.models.list({ pageSize: 5 }),
          api.experiments.list({ pageSize: 5 }),
        ]);

        setInferenceStatus(statusRes.data);
        setModels(modelsRes.data.data);
        setExperiments(experimentsRes.data.data);

        const now = Date.now();
        const data = Array.from({ length: 24 }, (_, i) => ({
          time: `${i}:00`,
          p50: Math.random() * 50 + 20,
          p95: Math.random() * 100 + 80,
          p99: Math.random() * 200 + 150,
          requests: Math.floor(Math.random() * 5000 + 2000),
        }));
        setLatencyData(data);

        setRecentActivity([
          { type: 'model', action: 'deployed', item: 'recommendation-v2', time: now - 300000 },
          { type: 'experiment', action: 'completed', item: 'xgboost-tuning-34', time: now - 600000 },
          { type: 'feature', action: 'ingested', item: 'user-features-daily', time: now - 1200000 },
          { type: 'drift', action: 'detected', item: 'prediction_drift', time: now - 1800000, isAlert: true },
          { type: 'abtest', action: 'started', item: 'checkout-page-redesign', time: now - 3600000 },
        ]);
      } catch (error) {
        console.error('Failed to fetch dashboard data:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
    const interval = setInterval(fetchData, 10000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Dashboard</h1>
          <p className="text-gray-500 mt-1">Welcome back! Here&apos;s what&apos;s happening with your ML systems.</p>
        </div>
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-2 text-sm text-gray-500">
            <span className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
            All systems operational
          </span>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Total Models"
          value={formatNumber(models.length || 24)}
          change={12}
          icon={Box}
          iconBg="bg-blue-500"
        />
        <StatCard
          title="Active Experiments"
          value={formatNumber(experiments.length || 18)}
          change={5}
          icon={FlaskConical}
          iconBg="bg-purple-500"
        />
        <StatCard
          title="Feature Sets"
          value="42"
          change={-2}
          icon={Database}
          iconBg="bg-green-500"
        />
        <StatCard
          title="Active A/B Tests"
          value="7"
          change={1}
          icon={SplitSquareVertical}
          iconBg="bg-orange-500"
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Inference Latency</h3>
            <div className="flex items-center gap-4 text-sm">
              <span className="flex items-center gap-1.5">
                <span className="w-3 h-3 rounded-full bg-blue-500" /> P50
              </span>
              <span className="flex items-center gap-1.5">
                <span className="w-3 h-3 rounded-full bg-yellow-500" /> P95
              </span>
              <span className="flex items-center gap-1.5">
                <span className="w-3 h-3 rounded-full bg-red-500" /> P99
              </span>
            </div>
          </div>
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={latencyData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 12 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 12 }} stroke="#9ca3af" unit="ms" />
                <Tooltip
                  contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }}
                  formatter={(value: number) => [`${value.toFixed(1)} ms`]}
                />
                <Line type="monotone" dataKey="p50" stroke="#3b82f6" strokeWidth={2} dot={false} name="P50" />
                <Line type="monotone" dataKey="p95" stroke="#eab308" strokeWidth={2} dot={false} name="P95" />
                <Line type="monotone" dataKey="p99" stroke="#ef4444" strokeWidth={2} dot={false} name="P99" />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card">
          <h3 className="font-semibold mb-4">System Status</h3>
          <div className="space-y-4">
            <div className="flex items-center justify-between p-3 bg-green-50 rounded-lg">
              <div className="flex items-center gap-3">
                <CheckCircle className="w-5 h-5 text-green-600" />
                <span className="font-medium">Inference Gateway</span>
              </div>
              <span className="text-sm text-green-700">Healthy</span>
            </div>
            <div className="flex items-center justify-between p-3 bg-green-50 rounded-lg">
              <div className="flex items-center gap-3">
                <CheckCircle className="w-5 h-5 text-green-600" />
                <span className="font-medium">Model Registry</span>
              </div>
              <span className="text-sm text-green-700">Healthy</span>
            </div>
            <div className="flex items-center justify-between p-3 bg-green-50 rounded-lg">
              <div className="flex items-center gap-3">
                <CheckCircle className="w-5 h-5 text-green-600" />
                <span className="font-medium">Feature Store</span>
              </div>
              <span className="text-sm text-green-700">Healthy</span>
            </div>
            <div className="flex items-center justify-between p-3 bg-yellow-50 rounded-lg">
              <div className="flex items-center gap-3">
                <AlertTriangle className="w-5 h-5 text-yellow-600" />
                <span className="font-medium">Model Drift</span>
              </div>
              <span className="text-sm text-yellow-700">1 Alert</span>
            </div>

            <div className="mt-4 pt-4 border-t border-gray-100">
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-gray-500">Total Requests</span>
                  <span className="font-medium">{formatNumber(inferenceStatus?.totalRequests || 124567)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Success Rate</span>
                  <span className="font-medium text-green-600">{formatPercentage(inferenceStatus?.successRate || 0.998)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">P99 Latency</span>
                  <span className="font-medium">{inferenceStatus?.p99LatencyMs?.toFixed(1) || '45.2'} ms</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Loaded Models</span>
                  <span className="font-medium">{inferenceStatus?.loadModels?.length || 5}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Throughput</h3>
            <span className="text-sm text-gray-500">Requests/min</span>
          </div>
          <div className="h-48">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={latencyData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 11 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" />
                <Tooltip
                  contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }}
                />
                <Area type="monotone" dataKey="requests" stroke="#10b981" fill="#d1fae5" strokeWidth={2} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Recent Models</h3>
            <button className="text-sm text-primary-600 hover:text-primary-700 flex items-center gap-1">
              View all <ChevronRight className="w-4 h-4" />
            </button>
          </div>
          <div className="space-y-3">
            {models.slice(0, 4).map((model: any) => (
              <div key={model.id} className="flex items-center justify-between p-2 hover:bg-gray-50 rounded-lg transition-colors">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center">
                    <Box className="w-4 h-4 text-blue-600" />
                  </div>
                  <div>
                    <p className="text-sm font-medium">{model.name}</p>
                    <p className="text-xs text-gray-500">{model.team}</p>
                  </div>
                </div>
                <span className="text-xs text-gray-400">{formatRelativeTime(model.createdAt)}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold">Recent Activity</h3>
            <Clock className="w-4 h-4 text-gray-400" />
          </div>
          <div className="space-y-3">
            {recentActivity.map((activity, idx) => (
              <div key={idx} className="flex items-start gap-3">
                <div
                  className={cn(
                    'w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5',
                    activity.isAlert ? 'bg-red-100' : 'bg-gray-100'
                  )}
                >
                  {activity.isAlert ? (
                    <AlertTriangle className="w-4 h-4 text-red-600" />
                  ) : (
                    <Activity className="w-4 h-4 text-gray-600" />
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm">
                    <span className="font-medium capitalize">{activity.type}</span>{' '}
                    <span className="text-gray-500">{activity.action}</span>{' '}
                    <span className="font-medium text-primary-600">{activity.item}</span>
                  </p>
                  <p className="text-xs text-gray-400 mt-0.5">{formatRelativeTime(activity.time)}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
