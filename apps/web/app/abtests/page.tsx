'use client';

import { useState, useEffect } from 'react';
import { Plus, SplitSquareVertical, Users, Target, TrendingUp, TrendingDown, Play, Pause, Search, BarChart3 } from 'lucide-react';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from 'recharts';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatDate, formatPercentage, getStatusColor, cn } from '@/lib/utils';
import type { ABTest } from '@mlops/shared';

export default function ABTestsPage() {
  const [tests, setTests] = useState<ABTest[]>([]);
  const [selectedTest, setSelectedTest] = useState<ABTest | null>(null);
  const [stats, setStats] = useState<any>(null);
  const [results, setResults] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [showCreateModal, setShowCreateModal] = useState(false);

  useEffect(() => {
    fetchData();
  }, []);

  useEffect(() => {
    if (selectedTest) {
      fetchStats(selectedTest.id);
    }
  }, [selectedTest]);

  const fetchData = async () => {
    try {
      const res = await api.abtests.list();
      setTests(res.data.data);
      if (res.data.data.length > 0) {
        setSelectedTest(res.data.data[0]);
      }
    } catch (error) {
      console.error('Failed to fetch A/B tests:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchStats = async (testId: string) => {
    try {
      const [statsRes, resultsRes] = await Promise.all([
        api.abtests.getStats(testId),
        api.abtests.getResults(testId),
      ]);
      setStats(statsRes.data);
      setResults(resultsRes.data);
    } catch (error) {
      console.error('Failed to fetch stats:', error);
    }
  };

  const handleCreateTest = async (e: React.FormEvent) => {
    e.preventDefault();
    const form = e.target as HTMLFormElement;
    const formData = new FormData(form);

    try {
      await api.abtests.create({
        name: formData.get('name') as string,
        hypothesis: formData.get('hypothesis') as string,
        primaryMetric: 'conversion_rate',
        projectId: 'proj-1',
        ownerId: 'admin',
        team: 'growth',
        bucketStrategy: 'user_id',
        variants: [
          { name: 'Control', isControl: true, trafficWeight: 50, config: {} },
          { name: 'Variant A', isControl: false, trafficWeight: 50, config: { buttonColor: 'green' } },
        ],
        trafficAllocation: { type: 'equal', totalTrafficPercentage: 100 },
        metrics: [{ name: 'conversion_rate', type: 'primary', goal: 'increase', significanceLevel: 0.05, minimumDetectableEffect: 0.05 }],
      });
      toast.success('A/B test created');
      setShowCreateModal(false);
      fetchData();
    } catch (error) {
      toast.error('Failed to create A/B test');
    }
  };

  const handleStartTest = async (test: ABTest) => {
    try {
      await api.abtests.update(test.id, { status: 'running', startTime: Date.now() });
      toast.success('Test started');
      fetchData();
    } catch (error) {
      toast.error('Failed to start test');
    }
  };

  const handlePauseTest = async (test: ABTest) => {
    try {
      await api.abtests.update(test.id, { status: 'paused' });
      toast.success('Test paused');
      fetchData();
    } catch (error) {
      toast.error('Failed to pause test');
    }
  };

  const handleGetAssignment = async () => {
    if (!selectedTest) return;
    try {
      const res = await api.abtests.assign({
        experimentId: selectedTest.id,
        userId: 'user-' + Math.floor(Math.random() * 1000),
      });
      toast.success(`Assigned to: ${res.data.variantName}`);
    } catch (error) {
      toast.error('Failed to get assignment');
    }
  };

  const handleTrackConversion = async () => {
    if (!selectedTest || !stats) return;
    const variant = selectedTest.variants[0];
    if (!variant) return;
    try {
      await api.abtests.track({
        experimentId: selectedTest.id,
        variantId: variant.id,
        userId: 'user-' + Math.floor(Math.random() * 1000),
        eventName: selectedTest.primaryMetric,
        properties: { conversion: true, value: 50 },
      });
      toast.success('Conversion tracked');
      fetchStats(selectedTest.id);
    } catch (error) {
      toast.error('Failed to track conversion');
    }
  };

  const filteredTests = tests.filter((t) =>
    t.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const trafficData = selectedTest?.variants.map((v) => ({
    name: v.name,
    value: v.trafficPercentage,
    isControl: v.isControl,
  })) || [];

  const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6'];

  if (loading) {
    return <div className="flex justify-center p-12"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" /></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">A/B Testing</h1>
          <p className="text-gray-500 mt-1">Run experiments and analyze their impact</p>
        </div>
        <div className="flex gap-3">
          <button onClick={handleGetAssignment} className="btn-secondary flex items-center gap-2" disabled={!selectedTest}>
            <Users className="w-4 h-4" /> Test Assignment
          </button>
          <button onClick={handleTrackConversion} className="btn-secondary flex items-center gap-2" disabled={!selectedTest}>
            <TrendingUp className="w-4 h-4" /> Track Event
          </button>
          <button onClick={() => setShowCreateModal(true)} className="btn-primary flex items-center gap-2">
            <Plus className="w-4 h-4" /> New Experiment
          </button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center">
              <SplitSquareVertical className="w-5 h-5 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{tests.length}</p>
              <p className="text-sm text-gray-500">Total Tests</p>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
              <Play className="w-5 h-5 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{tests.filter((t) => t.status === 'running').length}</p>
              <p className="text-sm text-gray-500">Running</p>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
              <Target className="w-5 h-5 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{tests.filter((t) => t.status === 'completed').length}</p>
              <p className="text-sm text-gray-500">Completed</p>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-yellow-100 rounded-lg flex items-center justify-center">
              <Users className="w-5 h-5 text-yellow-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">
                {tests.reduce((acc, t) => acc + t.variants.length, 0)}
              </p>
              <p className="text-sm text-gray-500">Total Variants</p>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-1">
          <div className="mb-3">
            <div className="relative">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                placeholder="Search experiments..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="input pl-10"
              />
            </div>
          </div>
          <div className="space-y-2">
            {filteredTests.map((test) => (
              <div
                key={test.id}
                onClick={() => setSelectedTest(test)}
                className={cn(
                  'card p-4 cursor-pointer transition-all hover:shadow-md',
                  selectedTest?.id === test.id && 'ring-2 ring-primary-500'
                )}
              >
                <div className="flex items-start justify-between">
                  <div>
                    <h3 className="font-medium">{test.name}</h3>
                    <p className="text-xs text-gray-500 mt-1 line-clamp-2">{test.hypothesis}</p>
                  </div>
                  <span className={cn('badge text-xs', getStatusColor(test.status))}>
                    {test.status}
                  </span>
                </div>
                <div className="mt-3 flex items-center justify-between">
                  <span className="text-xs text-gray-500">{test.variants.length} variants</span>
                  <div className="flex gap-1">
                    {test.status === 'running' ? (
                      <button
                        onClick={(e) => { e.stopPropagation(); handlePauseTest(test); }}
                        className="p-1.5 hover:bg-yellow-50 rounded"
                      >
                        <Pause className="w-4 h-4 text-yellow-600" />
                      </button>
                    ) : (
                      <button
                        onClick={(e) => { e.stopPropagation(); handleStartTest(test); }}
                        className="p-1.5 hover:bg-green-50 rounded"
                      >
                        <Play className="w-4 h-4 text-green-600" />
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {selectedTest && (
          <div className="lg:col-span-2 space-y-6">
            <div className="card">
              <div className="flex items-start justify-between">
                <div>
                  <h2 className="text-lg font-semibold">{selectedTest.name}</h2>
                  <p className="text-sm text-gray-500 mt-1">{selectedTest.description}</p>
                </div>
                <span className={cn('badge', getStatusColor(selectedTest.status))}>
                  {selectedTest.status}
                </span>
              </div>

              <div className="mt-4 p-4 bg-blue-50 rounded-lg">
                <p className="text-sm text-blue-800">
                  <span className="font-semibold">Hypothesis:</span> {selectedTest.hypothesis}
                </p>
              </div>

              <div className="mt-4 grid grid-cols-3 gap-4">
                <div className="p-3 bg-gray-50 rounded-lg">
                  <p className="text-xs text-gray-500">Primary Metric</p>
                  <p className="font-medium mt-1">{selectedTest.primaryMetric}</p>
                </div>
                <div className="p-3 bg-gray-50 rounded-lg">
                  <p className="text-xs text-gray-500">Bucket Strategy</p>
                  <p className="font-medium mt-1">{selectedTest.bucketStrategy}</p>
                </div>
                <div className="p-3 bg-gray-50 rounded-lg">
                  <p className="text-xs text-gray-500">Traffic</p>
                  <p className="font-medium mt-1">{selectedTest.trafficAllocation.totalTrafficPercentage}%</p>
                </div>
              </div>

              <div className="mt-6">
                <h3 className="font-medium mb-3">Traffic Allocation</h3>
                <div className="h-48">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={trafficData} layout="vertical">
                      <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                      <XAxis type="number" domain={[0, 100]} tick={{ fontSize: 11 }} stroke="#9ca3af" unit="%" />
                      <YAxis type="category" dataKey="name" tick={{ fontSize: 11 }} stroke="#9ca3af" width={100} />
                      <Tooltip
                        formatter={(value: number) => [`${value.toFixed(1)}%`, 'Traffic']}
                        contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }}
                      />
                      <Bar dataKey="value" radius={[0, 4, 4, 0]}>
                        {trafficData.map((_, index) => (
                          <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </div>

            <div className="card">
              <h3 className="font-medium mb-4">Variants</h3>
              <div className="space-y-3">
                {selectedTest.variants.map((variant, idx) => {
                  const variantStats = stats?.variantStats?.[variant.id];
                  return (
                    <div key={variant.id} className="p-4 border border-gray-200 rounded-xl">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <div
                            className="w-4 h-4 rounded-full"
                            style={{ backgroundColor: COLORS[idx % COLORS.length] }}
                          />
                          <div>
                            <h4 className="font-medium flex items-center gap-2">
                              {variant.name}
                              {variant.isControl && (
                                <span className="badge bg-gray-100 text-gray-700 text-xs">Control</span>
                              )}
                            </h4>
                            <p className="text-xs text-gray-500">{variant.trafficPercentage.toFixed(1)}% traffic</p>
                          </div>
                        </div>
                        {variantStats && (
                          <div className="text-right">
                            <p className="text-2xl font-bold">
                              {formatPercentage(variantStats.conversionRates[selectedTest.primaryMetric] || 0)}
                            </p>
                            <p className="text-xs text-gray-500">
                              {variantStats.impressions.toLocaleString()} impressions
                            </p>
                          </div>
                        )}
                      </div>

                      {results?.variantResults?.[variant.id] && (
                        <div className="mt-4 pt-4 border-t border-gray-100">
                          <h5 className="text-sm font-medium text-gray-700 mb-2">Statistical Results</h5>
                          <div className="grid grid-cols-3 gap-3">
                            {Object.entries(results.variantResults[variant.id].metricValues || {}).map(
                              ([metricName, metricResult]: [string, any]) => (
                                <div key={metricName} className="p-2 bg-gray-50 rounded">
                                  <p className="text-xs text-gray-500">{metricName}</p>
                                  <p className="font-mono text-sm">{metricResult.mean?.toFixed(4) || '-'}</p>
                                  <p className={cn(
                                    'text-xs',
                                    metricResult.isSignificant ? 'text-green-600' : 'text-gray-400'
                                  )}>
                                    p={metricResult.pValue?.toFixed(4) || '-'}
                                  </p>
                                </div>
                              )
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            {selectedTest.startTime && (
              <div className="card">
                <h3 className="font-medium mb-3">Timeline</h3>
                <div className="flex items-center gap-6 text-sm">
                  <div>
                    <p className="text-gray-500">Started</p>
                    <p className="font-medium">{formatDate(selectedTest.startTime)}</p>
                  </div>
                  {selectedTest.endTime && (
                    <div>
                      <p className="text-gray-500">Ended</p>
                      <p className="font-medium">{formatDate(selectedTest.endTime)}</p>
                    </div>
                  )}
                  {results?.lastCalculatedAt && (
                    <div>
                      <p className="text-gray-500">Last Analysis</p>
                      <p className="font-medium">{formatDate(results.lastCalculatedAt)}</p>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-lg">
            <h2 className="text-xl font-bold mb-6">Create New A/B Experiment</h2>
            <form onSubmit={handleCreateTest} className="space-y-4">
              <div>
                <label className="label">Experiment Name</label>
                <input type="text" name="name" className="input" placeholder="e.g. Checkout Button Color Test" required />
              </div>
              <div>
                <label className="label">Hypothesis</label>
                <textarea
                  name="hypothesis"
                  className="input"
                  rows={3}
                  placeholder="Changing the button color from blue to green will increase conversions by 10%..."
                  required
                />
              </div>
              <div className="flex gap-3 pt-4">
                <button type="button" onClick={() => setShowCreateModal(false)} className="btn-secondary flex-1">
                  Cancel
                </button>
                <button type="submit" className="btn-primary flex-1">
                  Create Experiment
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
