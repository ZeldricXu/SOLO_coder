'use client';

import { useState, useEffect } from 'react';
import { Plus, Database, Clock, Upload, Download, BarChart3, Search, Tag } from 'lucide-react';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatBytes, formatDate, getStatusColor, cn } from '@/lib/utils';
import type { FeatureSet } from '@mlops/shared';

export default function FeaturesPage() {
  const [featureSets, setFeatureSets] = useState<FeatureSet[]>([]);
  const [selectedFeatureSet, setSelectedFeatureSet] = useState<FeatureSet | null>(null);
  const [loading, setLoading] = useState(true);
  const [showDistribution, setShowDistribution] = useState(false);
  const [distributionData, setDistributionData] = useState<any>(null);
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      const res = await api.features.list();
      setFeatureSets(res.data.data);
      if (res.data.data.length > 0) {
        setSelectedFeatureSet(res.data.data[0]);
      }
    } catch (error) {
      console.error('Failed to fetch feature sets:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateFeatureSet = async () => {
    const name = prompt('Enter feature set name:');
    if (!name) return;
    try {
      await api.features.create({
        name,
        projectId: 'proj-1',
        ownerId: 'admin',
        team: 'data-engineering',
        mode: 'both',
        entities: [{ name: 'user', joinKey: 'user_id' }],
        features: [
          { name: 'total_purchases', valueType: 'int', isNullable: false },
          { name: 'avg_order_value', valueType: 'float', isNullable: true },
          { name: 'is_active', valueType: 'bool', isNullable: false },
        ],
        ttlSeconds: 86400 * 30,
      });
      toast.success('Feature set created');
      fetchData();
    } catch (error) {
      toast.error('Failed to create feature set');
    }
  };

  const handleIngestData = async () => {
    if (!selectedFeatureSet) return;
    try {
      const sampleData = Array.from({ length: 100 }, (_, i) => ({
        user_id: `user-${i}`,
        total_purchases: Math.floor(Math.random() * 100),
        avg_order_value: Math.random() * 500,
        is_active: Math.random() > 0.3,
      }));

      await api.features.ingest({
        featureSetId: selectedFeatureSet.id,
        data: sampleData,
        entityKeyField: 'user_id',
        mode: 'upsert',
      });
      toast.success('Data ingested successfully');
    } catch (error) {
      toast.error('Failed to ingest data');
    }
  };

  const handleGetFeatures = async () => {
    if (!selectedFeatureSet) return;
    try {
      const res = await api.features.getFeatures({
        featureSetId: selectedFeatureSet.id,
        entityKeys: ['user-1', 'user-2', 'user-3'],
      });
      console.log('Features:', res.data);
      toast.success('Retrieved ' + Object.keys(res.data.values).length + ' entity records');
    } catch (error) {
      toast.error('Failed to get features');
    }
  };

  const handleShowDistribution = async (featureName: string) => {
    if (!selectedFeatureSet) return;
    try {
      const res = await api.features.getDistribution(selectedFeatureSet.id, featureName);
      setDistributionData(res.data);
      setShowDistribution(true);
    } catch (error) {
      toast.error('Failed to load distribution');
    }
  };

  const filteredSets = featureSets.filter((fs) =>
    fs.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  if (loading) {
    return <div className="flex justify-center p-12"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" /></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Feature Store</h1>
          <p className="text-gray-500 mt-1">Manage online and offline features for ML models</p>
        </div>
        <div className="flex gap-3">
          <button onClick={handleIngestData} className="btn-secondary flex items-center gap-2" disabled={!selectedFeatureSet}>
            <Upload className="w-4 h-4" /> Ingest Data
          </button>
          <button onClick={handleGetFeatures} className="btn-secondary flex items-center gap-2" disabled={!selectedFeatureSet}>
            <Download className="w-4 h-4" /> Get Features
          </button>
          <button onClick={handleCreateFeatureSet} className="btn-primary flex items-center gap-2">
            <Plus className="w-4 h-4" /> Create Feature Set
          </button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
              <Database className="w-5 h-5 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{featureSets.length}</p>
              <p className="text-sm text-gray-500">Feature Sets</p>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center">
              <Tag className="w-5 h-5 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">
                {featureSets.reduce((acc, fs) => acc + fs.features.length, 0)}
              </p>
              <p className="text-sm text-gray-500">Total Features</p>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
              <Database className="w-5 h-5 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">
                {featureSets.filter((fs) => fs.mode === 'online' || fs.mode === 'both').length}
              </p>
              <p className="text-sm text-gray-500">Online Enabled</p>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-orange-100 rounded-lg flex items-center justify-center">
              <Clock className="w-5 h-5 text-orange-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">30d</p>
              <p className="text-sm text-gray-500">Avg TTL</p>
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
                placeholder="Search feature sets..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="input pl-10"
              />
            </div>
          </div>
          <div className="space-y-2">
            {filteredSets.map((fs) => (
              <div
                key={fs.id}
                onClick={() => setSelectedFeatureSet(fs)}
                className={cn(
                  'card p-4 cursor-pointer transition-all hover:shadow-md',
                  selectedFeatureSet?.id === fs.id && 'ring-2 ring-primary-500'
                )}
              >
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="font-medium">{fs.name}</h3>
                    <p className="text-xs text-gray-500 mt-1">{fs.features.length} features</p>
                  </div>
                  <span className={cn('badge text-xs', getStatusColor(fs.mode === 'both' ? 'active' : fs.mode))}>
                    {fs.mode}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>

        {selectedFeatureSet && (
          <div className="lg:col-span-2 space-y-6">
            <div className="card">
              <div className="flex items-start justify-between">
                <div>
                  <h2 className="text-lg font-semibold">{selectedFeatureSet.name}</h2>
                  <p className="text-sm text-gray-500 mt-1">{selectedFeatureSet.description}</p>
                </div>
                <div className="flex items-center gap-2">
                  <span className={cn('badge', getStatusColor(selectedFeatureSet.status))}>
                    {selectedFeatureSet.status}
                  </span>
                  <span className="badge bg-blue-100 text-blue-700">v{selectedFeatureSet.latestVersion?.version || '1'}</span>
                </div>
              </div>

              <div className="mt-4 grid grid-cols-3 gap-4">
                <div className="p-3 bg-gray-50 rounded-lg">
                  <p className="text-xs text-gray-500">TTL</p>
                  <p className="font-medium mt-1">{selectedFeatureSet.ttlSeconds ? `${selectedFeatureSet.ttlSeconds / 86400} days` : 'Forever'}</p>
                </div>
                <div className="p-3 bg-gray-50 rounded-lg">
                  <p className="text-xs text-gray-500">Entities</p>
                  <p className="font-medium mt-1">{selectedFeatureSet.entities.length}</p>
                </div>
                <div className="p-3 bg-gray-50 rounded-lg">
                  <p className="text-xs text-gray-500">Updated</p>
                  <p className="font-medium mt-1">{formatDate(selectedFeatureSet.updatedAt)}</p>
                </div>
              </div>

              <div className="mt-6">
                <h3 className="font-medium mb-3">Features</h3>
                <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
                  <table className="w-full">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="table-header text-xs">Name</th>
                        <th className="table-header text-xs">Type</th>
                        <th className="table-header text-xs">Nullable</th>
                        <th className="table-header text-xs">Stats</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {selectedFeatureSet.features.map((feature, idx) => (
                        <tr key={idx} className="hover:bg-gray-50">
                          <td className="table-cell text-sm font-mono">{feature.name}</td>
                          <td className="table-cell text-sm">
                            <span className="badge bg-purple-100 text-purple-700">{feature.valueType}</span>
                          </td>
                          <td className="table-cell text-sm text-gray-500">
                            {feature.isNullable ? 'Yes' : 'No'}
                          </td>
                          <td className="table-cell">
                            <button
                              onClick={() => handleShowDistribution(feature.name)}
                              className="p-1.5 hover:bg-gray-100 rounded"
                              title="View distribution"
                            >
                              <BarChart3 className="w-4 h-4 text-gray-500" />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              <div className="mt-6">
                <h3 className="font-medium mb-3">Entities</h3>
                <div className="flex gap-3 flex-wrap">
                  {selectedFeatureSet.entities.map((entity, idx) => (
                    <div key={idx} className="px-3 py-2 bg-gray-50 rounded-lg">
                      <p className="text-sm font-medium">{entity.name}</p>
                      <p className="text-xs text-gray-500">Join: {entity.joinKey}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {selectedFeatureSet.versions.length > 0 && (
              <div className="card">
                <h3 className="font-medium mb-3">Versions</h3>
                <div className="space-y-2">
                  {selectedFeatureSet.versions.map((version) => (
                    <div key={version.id} className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
                      <div className="flex items-center gap-3">
                        <span className="font-mono font-medium">v{version.version}</span>
                        <span className="text-sm text-gray-500">{formatDate(version.createdAt)}</span>
                      </div>
                      <div className="flex items-center gap-4 text-sm text-gray-500">
                        {version.rowCount && <span>{formatNumber(Number(version.rowCount))} rows</span>}
                        {version.sizeBytes && <span>{formatBytes(Number(version.sizeBytes))}</span>}
                        <span className={cn('badge', getStatusColor(version.status))}>{version.status}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {showDistribution && distributionData && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-2xl">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-bold">
                Feature Distribution: {distributionData.featureName}
              </h2>
              <button onClick={() => setShowDistribution(false)} className="btn-secondary">Close</button>
            </div>

            <div className="grid grid-cols-4 gap-4 mb-6">
              {[
                { label: 'Mean', value: distributionData.statistics.mean },
                { label: 'Std Dev', value: distributionData.statistics.std },
                { label: 'Min', value: distributionData.statistics.min },
                { label: 'Max', value: distributionData.statistics.max },
              ].map((item) => (
                <div key={item.label} className="p-3 bg-gray-50 rounded-lg text-center">
                  <p className="text-xs text-gray-500">{item.label}</p>
                  <p className="font-mono font-semibold mt-1">{item.value?.toFixed(4) || '-'}</p>
                </div>
              ))}
            </div>

            {distributionData.distribution && (
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={distributionData.distribution.bins.map((bin: number, i: number) => ({
                      bin: bin.toFixed(2),
                      count: distributionData.distribution.counts[i],
                    }))}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="bin" tick={{ fontSize: 10 }} stroke="#9ca3af" />
                    <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" />
                    <Tooltip contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }} />
                    <Bar dataKey="count" fill="#3b82f6" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function formatNumber(n: number): string {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
  return String(n);
}
