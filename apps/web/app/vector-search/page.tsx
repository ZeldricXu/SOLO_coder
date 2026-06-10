'use client';

import { useState, useEffect } from 'react';
import { Search, Plus, Database, TrendingUp, Clock, Zap, SlidersHorizontal } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { cn } from '@/lib/utils';
import type { VectorIndex, VectorSearchResult, VectorStats } from '@mlops/shared';

export default function VectorSearchPage() {
  const [indexes, setIndexes] = useState<VectorIndex[]>([]);
  const [selectedIndex, setSelectedIndex] = useState<VectorIndex | null>(null);
  const [stats, setStats] = useState<VectorStats | null>(null);
  const [loading, setLoading] = useState(true);

  const [queryVector, setQueryVector] = useState('[0.1, 0.2, 0.3, 0.4, 0.5]');
  const [topK, setTopK] = useState(10);
  const [distanceMetric, setDistanceMetric] = useState<'cosine' | 'l2' | 'inner_product'>('cosine');
  const [searchResults, setSearchResults] = useState<VectorSearchResult[]>([]);
  const [searchTime, setSearchTime] = useState<number>(0);
  const [searching, setSearching] = useState(false);

  const [showRangeFilter, setShowRangeFilter] = useState(false);
  const [rangeMin, setRangeMin] = useState('');
  const [rangeMax, setRangeMax] = useState('');
  const [rangeFeature, setRangeFeature] = useState('');

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      const [statsRes] = await Promise.all([
        api.vectorSearch.getStats(),
      ]);
      setStats(statsRes.data);
    } catch (error) {
      console.error('Failed to fetch vector stats:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchIndexes = async (featureSetId: string) => {
    try {
      const res = await api.vectorSearch.listIndexes(featureSetId);
      setIndexes(res.data || []);
    } catch (error) {
      console.error('Failed to fetch indexes:', error);
    }
  };

  const handleSearch = async () => {
    if (!selectedIndex) {
      toast.error('Please select a vector index');
      return;
    }

    setSearching(true);
    try {
      const vector = JSON.parse(queryVector);

      const filter: any = {};
      if (showRangeFilter && rangeFeature && (rangeMin || rangeMax)) {
        filter.rangeFilters = [{
          featureName: rangeFeature,
          min: rangeMin ? parseFloat(rangeMin) : undefined,
          max: rangeMax ? parseFloat(rangeMax) : undefined,
        }];
      }

      const res = await api.vectorSearch.search({
        featureSetId: selectedIndex.featureSetId,
        featureName: selectedIndex.featureName,
        queryVector: vector,
        topK,
        filter: Object.keys(filter).length > 0 ? filter : undefined,
        includeFeatures: true,
        distanceMetric,
      });

      setSearchResults(res.data.results || []);
      setSearchTime(res.data.searchTimeMs || 0);
      toast.success(`Found ${res.data.results?.length || 0} results`);
    } catch (error) {
      toast.error('Search failed');
      console.error(error);
    } finally {
      setSearching(false);
    }
  };

  const handleCreateIndex = async () => {
    const featureSetId = prompt('Feature Set ID:');
    const featureName = prompt('Feature Name:', 'embedding');
    const dimension = parseInt(prompt('Dimension:', '128') || '128');

    if (!featureSetId || !featureName || !dimension) return;

    try {
      await api.vectorSearch.createIndex({
        featureSetId,
        featureName,
        dimension,
        distanceMetric,
        indexType: 'hnsw',
        hnswConfig: {
          m: 16,
          efConstruction: 200,
          efSearch: 50,
          maxElements: 100000,
        },
      });
      toast.success('Index created (building in background)');
      fetchIndexes(featureSetId);
    } catch (error) {
      toast.error('Failed to create index');
    }
  };

  const formatBytes = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
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
          <h1 className="text-2xl font-bold">Vector Search</h1>
          <p className="text-gray-500 mt-1">Similarity search with HNSW vector indexes</p>
        </div>
        <button
          onClick={handleCreateIndex}
          className="btn-primary flex items-center gap-2"
        >
          <Plus className="w-4 h-4" /> New Index
        </button>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center">
              <Database className="w-5 h-5 text-blue-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">{stats?.indexCount || 0}</div>
              <div className="text-sm text-gray-500">Total Indexes</div>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
              <TrendingUp className="w-5 h-5 text-green-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">{stats?.totalVectors?.toLocaleString() || 0}</div>
              <div className="text-sm text-gray-500">Total Vectors</div>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
              <Zap className="w-5 h-5 text-purple-600" />
            </div>
            <div>
              <div className="text-2xl font-bold">
                {stats?.avgSearchTimeMs ? `${stats.avgSearchTimeMs.toFixed(2)}ms` : '-'}
              </div>
              <div className="text-sm text-gray-500">Avg Search Time</div>
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
                {stats?.totalMemoryBytes ? formatBytes(stats.totalMemoryBytes) : '-'}
              </div>
              <div className="text-sm text-gray-500">Memory Usage</div>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-1">
          <div className="card">
            <h2 className="font-semibold mb-4">Search Configuration</h2>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Feature Set ID
                </label>
                <input
                  type="text"
                  className="input"
                  placeholder="Enter feature set ID"
                  onBlur={(e) => {
                    if (e.target.value) {
                      fetchIndexes(e.target.value);
                    }
                  }}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Distance Metric
                </label>
                <select
                  value={distanceMetric}
                  onChange={(e) => setDistanceMetric(e.target.value as any)}
                  className="input"
                >
                  <option value="cosine">Cosine Similarity</option>
                  <option value="l2">L2 Distance</option>
                  <option value="inner_product">Inner Product</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Top K Results
                </label>
                <input
                  type="number"
                  min={1}
                  max={1000}
                  value={topK}
                  onChange={(e) => setTopK(parseInt(e.target.value) || 10)}
                  className="input"
                />
              </div>

              <div>
                <div className="flex items-center justify-between mb-2">
                  <label className="text-sm font-medium text-gray-700">
                    Range Filter
                  </label>
                  <button
                    onClick={() => setShowRangeFilter(!showRangeFilter)}
                    className={cn(
                      'p-1.5 rounded transition-colors',
                      showRangeFilter ? 'bg-primary-100 text-primary-600' : 'hover:bg-gray-100'
                    )}
                  >
                    <SlidersHorizontal className="w-4 h-4" />
                  </button>
                </div>
                {showRangeFilter && (
                  <div className="space-y-2 p-3 bg-gray-50 rounded-lg">
                    <input
                      type="text"
                      placeholder="Feature name"
                      value={rangeFeature}
                      onChange={(e) => setRangeFeature(e.target.value)}
                      className="input text-sm"
                    />
                    <div className="flex gap-2">
                      <input
                        type="number"
                        placeholder="Min"
                        value={rangeMin}
                        onChange={(e) => setRangeMin(e.target.value)}
                        className="input text-sm flex-1"
                      />
                      <input
                        type="number"
                        placeholder="Max"
                        value={rangeMax}
                        onChange={(e) => setRangeMax(e.target.value)}
                        className="input text-sm flex-1"
                      />
                    </div>
                  </div>
                )}
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Query Vector (JSON array)
                </label>
                <textarea
                  value={queryVector}
                  onChange={(e) => setQueryVector(e.target.value)}
                  className="input font-mono text-sm h-32"
                  placeholder="[0.1, 0.2, 0.3, ...]"
                />
              </div>

              <button
                onClick={handleSearch}
                disabled={searching}
                className="btn-primary w-full flex items-center justify-center gap-2"
              >
                <Search className="w-4 h-4" />
                {searching ? 'Searching...' : 'Search'}
              </button>
            </div>
          </div>

          {indexes.length > 0 && (
            <div className="card mt-4">
              <h3 className="font-semibold mb-3">Available Indexes</h3>
              <div className="space-y-2">
                {indexes.map((idx) => (
                  <div
                    key={idx.id}
                    className={cn(
                      'p-3 rounded-lg cursor-pointer transition-colors',
                      selectedIndex?.id === idx.id
                        ? 'bg-primary-50 border border-primary-200'
                        : 'bg-gray-50 hover:bg-gray-100'
                    )}
                    onClick={() => setSelectedIndex(idx)}
                  >
                    <div className="font-medium text-sm">{idx.featureName}</div>
                    <div className="text-xs text-gray-500 flex items-center gap-2 mt-1">
                      <span>{idx.dimension}d</span>
                      <span>·</span>
                      <span>{idx.size.toLocaleString()} vectors</span>
                    </div>
                    <span className={cn(
                      'inline-block mt-2 text-xs px-2 py-0.5 rounded',
                      idx.status === 'ready' ? 'bg-green-100 text-green-700' :
                      idx.status === 'building' ? 'bg-amber-100 text-amber-700' :
                      'bg-red-100 text-red-700'
                    )}>
                      {idx.status}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className="lg:col-span-2">
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-semibold">Search Results</h2>
              {searchResults.length > 0 && (
                <span className="text-sm text-gray-500">
                  {searchResults.length} results · {searchTime}ms
                </span>
              )}
            </div>

            {searchResults.length > 0 ? (
              <div className="space-y-2">
                {searchResults.map((result, idx) => (
                  <div
                    key={result.entityKey}
                    className="p-4 bg-gray-50 rounded-lg hover:bg-gray-100 transition-colors"
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className="w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center text-sm font-bold text-primary-700">
                          {result.rank}
                        </div>
                        <div>
                          <div className="font-medium">{result.entityKey}</div>
                          <div className="text-xs text-gray-500">
                            Similarity: {(result.similarity * 100).toFixed(2)}%
                          </div>
                        </div>
                      </div>
                      <div className="text-right">
                        <div className="font-mono text-sm">
                          Distance: {result.distance.toFixed(6)}
                        </div>
                        <div className="w-32 h-2 bg-gray-200 rounded-full overflow-hidden mt-1">
                          <div
                            className={cn(
                              'h-full rounded-full transition-all',
                              result.similarity > 0.8 ? 'bg-green-500' :
                              result.similarity > 0.5 ? 'bg-amber-500' : 'bg-red-500'
                            )}
                            style={{ width: `${Math.max(result.similarity * 100, 5)}%` }}
                          />
                        </div>
                      </div>
                    </div>

                    {result.features && Object.keys(result.features).length > 0 && (
                      <div className="mt-3 pt-3 border-t border-gray-200">
                        <details className="text-sm">
                          <summary className="cursor-pointer text-gray-500 hover:text-gray-700">
                            View features
                          </summary>
                          <pre className="mt-2 p-2 bg-white rounded text-xs font-mono overflow-x-auto">
                            {JSON.stringify(result.features, null, 2)}
                          </pre>
                        </details>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center py-16 text-gray-500">
                <Search className="w-16 h-16 mb-4 text-gray-300" />
                <p className="font-medium">No search results yet</p>
                <p className="text-sm">Configure search parameters and run a query</p>
              </div>
            )}
          </div>

          {stats && (
            <div className="card mt-4">
              <h3 className="font-semibold mb-4">Statistics</h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <h4 className="text-sm font-medium text-gray-700 mb-2">Dimension Distribution</h4>
                  <div className="space-y-1">
                    {Object.entries(stats.dimensionDistribution || {}).map(([dim, count]) => (
                      <div key={dim} className="flex items-center justify-between text-sm">
                        <span className="text-gray-600">{dim}d</span>
                        <span className="font-mono">{count as number}</span>
                      </div>
                    ))}
                  </div>
                </div>
                <div>
                  <h4 className="text-sm font-medium text-gray-700 mb-2">Metric Distribution</h4>
                  <div className="space-y-1">
                    {Object.entries(stats.metricDistribution || {}).map(([metric, count]) => (
                      <div key={metric} className="flex items-center justify-between text-sm">
                        <span className="text-gray-600">{metric}</span>
                        <span className="font-mono">{count as number}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
