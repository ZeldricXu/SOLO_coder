'use client';

import { useState, useEffect } from 'react';
import { Plus, Upload, Download, Play, Pause, Trash2, Tag, Clock, User, Search, MoreVertical } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatBytes, formatDate, getStatusColor, cn } from '@/lib/utils';
import type { Model, ModelVersion } from '@mlops/shared';

export default function ModelsPage() {
  const [models, setModels] = useState<Model[]>([]);
  const [selectedModel, setSelectedModel] = useState<Model | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [uploadForm, setUploadForm] = useState({
    name: '',
    description: '',
    ownerId: 'admin',
    team: 'data-science',
    version: '',
    semanticVersion: '1.0.0',
    format: 'onnx' as const,
  });
  const [selectedFile, setSelectedFile] = useState<File | null>(null);

  useEffect(() => {
    fetchModels();
  }, []);

  const fetchModels = async () => {
    try {
      const res = await api.models.list();
      setModels(res.data.data);
    } catch (error) {
      console.error('Failed to fetch models:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateModel = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedFile) {
      toast.error('Please select a model file');
      return;
    }

    try {
      const modelRes = await api.models.create({
        name: uploadForm.name,
        description: uploadForm.description,
        ownerId: uploadForm.ownerId,
        team: uploadForm.team,
      });

      const formData = new FormData();
      formData.append('file', selectedFile);
      formData.append('version', uploadForm.version);
      formData.append('semanticVersion', uploadForm.semanticVersion);
      formData.append('format', uploadForm.format);
      formData.append('dataSchema', JSON.stringify({
        inputs: [{ name: 'input', type: 'float32', shape: [-1, 10] }],
        outputs: [{ name: 'output', type: 'float32', shape: [-1, 1] }],
      }));

      await api.models.createVersion(modelRes.data.id, formData);
      toast.success('Model uploaded successfully');
      setShowUploadModal(false);
      fetchModels();
      setUploadForm({ name: '', description: '', ownerId: 'admin', team: 'data-science', version: '', semanticVersion: '1.0.0', format: 'onnx' });
      setSelectedFile(null);
    } catch (error) {
      toast.error('Failed to upload model');
    }
  };

  const handleLoadModel = async (model: Model) => {
    try {
      await api.models.load(model.id, model.latestVersion?.id);
      toast.success('Model loaded into memory');
    } catch (error) {
      toast.error('Failed to load model');
    }
  };

  const handleDeleteModel = async (modelId: string) => {
    if (!confirm('Are you sure you want to delete this model?')) return;
    try {
      await api.models.delete(modelId);
      toast.success('Model deleted');
      fetchModels();
    } catch (error) {
      toast.error('Failed to delete model');
    }
  };

  const filteredModels = models.filter((m) =>
    m.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    m.team.toLowerCase().includes(searchTerm.toLowerCase())
  );

  if (loading) {
    return <div className="flex justify-center p-12"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" /></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Model Registry</h1>
          <p className="text-gray-500 mt-1">Manage and version your machine learning models</p>
        </div>
        <button onClick={() => setShowUploadModal(true)} className="btn-primary flex items-center gap-2">
          <Plus className="w-4 h-4" /> Register Model
        </button>
      </div>

      <div className="flex items-center gap-4">
        <div className="relative flex-1 max-w-md">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            placeholder="Search models..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="input pl-10"
          />
        </div>
        <select className="input max-w-xs">
          <option value="">All Teams</option>
          <option value="data-science">Data Science</option>
          <option value="recommendation">Recommendation</option>
          <option value="fraud">Fraud Detection</option>
        </select>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-3">
          {filteredModels.map((model) => (
            <div
              key={model.id}
              className={cn(
                'card cursor-pointer transition-all hover:shadow-md',
                selectedModel?.id === model.id && 'ring-2 ring-primary-500'
              )}
              onClick={() => setSelectedModel(model)}
            >
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3">
                    <h3 className="font-semibold text-lg">{model.name}</h3>
                    <span className={cn('badge', getStatusColor(model.status))}>{model.status}</span>
                    {model.latestVersion && (
                      <span className="badge bg-primary-100 text-primary-700">v{model.latestVersion.version}</span>
                    )}
                  </div>
                  <p className="text-sm text-gray-500 mt-1">{model.description}</p>
                  <div className="flex items-center gap-4 mt-3 text-sm text-gray-500">
                    <span className="flex items-center gap-1"><User className="w-4 h-4" /> {model.team}</span>
                    <span className="flex items-center gap-1"><Clock className="w-4 h-4" /> {formatDate(model.updatedAt)}</span>
                    <span className="flex items-center gap-1"><Tag className="w-4 h-4" /> {model.versions.length} versions</span>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={(e) => { e.stopPropagation(); handleLoadModel(model); }}
                    className="p-2 hover:bg-green-50 rounded-lg transition-colors"
                    title="Load model"
                  >
                    <Play className="w-4 h-4 text-green-600" />
                  </button>
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDeleteModel(model.id); }}
                    className="p-2 hover:bg-red-50 rounded-lg transition-colors"
                    title="Delete model"
                  >
                    <Trash2 className="w-4 h-4 text-red-500" />
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>

        {selectedModel && (
          <div className="card h-fit sticky top-20">
            <h3 className="font-semibold text-lg">{selectedModel.name}</h3>
            <p className="text-sm text-gray-500 mt-1">{selectedModel.description}</p>

            <div className="mt-4 space-y-3">
              <div>
                <h4 className="text-sm font-medium text-gray-700 mb-2">Latest Version</h4>
                {selectedModel.latestVersion ? (
                  <div className="p-3 bg-gray-50 rounded-lg">
                    <div className="flex justify-between items-center">
                      <span className="font-medium">v{selectedModel.latestVersion.version}</span>
                      <span className={cn('badge', getStatusColor(selectedModel.latestVersion.status))}>
                        {selectedModel.latestVersion.status}
                      </span>
                    </div>
                    <div className="mt-2 text-sm text-gray-500 space-y-1">
                      <div className="flex justify-between">
                        <span>Format:</span>
                        <span className="font-mono">{selectedModel.latestVersion.format}</span>
                      </div>
                      <div className="flex justify-between">
                        <span>Size:</span>
                        <span>{formatBytes(selectedModel.latestVersion.sizeBytes)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span>Storage:</span>
                        <span>{selectedModel.latestVersion.storageBackend}</span>
                      </div>
                    </div>
                    <button
                      onClick={() => api.models.downloadVersion(selectedModel.latestVersion!.id)}
                      className="w-full mt-3 btn-secondary flex items-center justify-center gap-2"
                    >
                      <Download className="w-4 h-4" /> Download
                    </button>
                  </div>
                ) : (
                  <p className="text-sm text-gray-500">No versions yet</p>
                )}
              </div>

              <div>
                <h4 className="text-sm font-medium text-gray-700 mb-2">Performance Metrics</h4>
                {selectedModel.latestVersion?.metrics?.length ? (
                  <div className="space-y-2">
                    {selectedModel.latestVersion.metrics.slice(0, 5).map((metric, idx) => (
                      <div key={idx} className="flex justify-between text-sm">
                        <span className="text-gray-500">{metric.name}</span>
                        <span className="font-mono">{metric.value.toFixed(4)}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-gray-500">No metrics recorded</p>
                )}
              </div>

              <div>
                <h4 className="text-sm font-medium text-gray-700 mb-2">Tags</h4>
                <div className="flex flex-wrap gap-2">
                  {selectedModel.tags.map((tag, idx) => (
                    <span key={idx} className="badge bg-gray-100 text-gray-700">{tag}</span>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {showUploadModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-lg max-h-[90vh] overflow-y-auto">
            <h2 className="text-xl font-bold mb-6">Register New Model</h2>
            <form onSubmit={handleCreateModel} className="space-y-4">
              <div>
                <label className="label">Model Name</label>
                <input
                  type="text"
                  value={uploadForm.name}
                  onChange={(e) => setUploadForm({ ...uploadForm, name: e.target.value })}
                  className="input"
                  required
                />
              </div>
              <div>
                <label className="label">Description</label>
                <textarea
                  value={uploadForm.description}
                  onChange={(e) => setUploadForm({ ...uploadForm, description: e.target.value })}
                  className="input"
                  rows={3}
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="label">Team</label>
                  <input
                    type="text"
                    value={uploadForm.team}
                    onChange={(e) => setUploadForm({ ...uploadForm, team: e.target.value })}
                    className="input"
                  />
                </div>
                <div>
                  <label className="label">Version</label>
                  <input
                    type="text"
                    value={uploadForm.version}
                    onChange={(e) => setUploadForm({ ...uploadForm, version: e.target.value })}
                    placeholder="e.g. v1"
                    className="input"
                    required
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="label">Semantic Version</label>
                  <input
                    type="text"
                    value={uploadForm.semanticVersion}
                    onChange={(e) => setUploadForm({ ...uploadForm, semanticVersion: e.target.value })}
                    placeholder="1.0.0"
                    className="input"
                    required
                  />
                </div>
                <div>
                  <label className="label">Format</label>
                  <select
                    value={uploadForm.format}
                    onChange={(e) => setUploadForm({ ...uploadForm, format: e.target.value as any })}
                    className="input"
                  >
                    <option value="onnx">ONNX</option>
                    <option value="pkl">Pickle</option>
                    <option value="pt">PyTorch</option>
                    <option value="joblib">Joblib</option>
                    <option value="h5">TensorFlow</option>
                  </select>
                </div>
              </div>
              <div>
                <label className="label">Model File</label>
                <div className="border-2 border-dashed border-gray-300 rounded-lg p-6 text-center">
                  <Upload className="w-8 h-8 mx-auto text-gray-400 mb-2" />
                  <p className="text-sm text-gray-600">
                    {selectedFile ? selectedFile.name : 'Click to upload or drag and drop'}
                  </p>
                  <input
                    type="file"
                    onChange={(e) => setSelectedFile(e.target.files?.[0] || null)}
                    className="hidden"
                    id="file-upload"
                  />
                  <label htmlFor="file-upload" className="btn-secondary mt-3 inline-block cursor-pointer">
                    Select File
                  </label>
                </div>
              </div>
              <div className="flex gap-3 pt-4">
                <button type="button" onClick={() => setShowUploadModal(false)} className="btn-secondary flex-1">
                  Cancel
                </button>
                <button type="submit" className="btn-primary flex-1">
                  Register Model
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
