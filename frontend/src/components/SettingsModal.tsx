"use client";

import { useState, useEffect } from "react";
import { 
  X, 
  Plus, 
  Edit2, 
  Trash2, 
  Check, 
  AlertCircle,
  Key,
  Globe,
  Brain,
  RefreshCw
} from "lucide-react";
import { apiConfigApi, type APIConfigResponse } from "@/lib/api";
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";

function cn(...inputs: any[]) {
  return twMerge(clsx(inputs));
}

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfigChange?: () => void;
}

interface FormData {
  name: string;
  api_key: string;
  base_url: string;
  model: string;
  embedding_model: string;
}

const defaultFormData: FormData = {
  name: "",
  api_key: "",
  base_url: "https://api.openai.com/v1",
  model: "gpt-3.5-turbo",
  embedding_model: "text-embedding-3-small",
};

export function SettingsModal({ isOpen, onClose, onConfigChange }: SettingsModalProps) {
  const [configs, setConfigs] = useState<APIConfigResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingConfig, setEditingConfig] = useState<APIConfigResponse | null>(null);
  const [formData, setFormData] = useState<FormData>(defaultFormData);
  const [formLoading, setFormLoading] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      loadConfigs();
    }
  }, [isOpen]);

  const loadConfigs = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiConfigApi.list();
      setConfigs(data);
    } catch (err: any) {
      const errorMsg = err.response?.data?.detail || "加载配置失败";
      if (errorMsg.includes("请先配置API设置")) {
        setConfigs([]);
      } else {
        setError(errorMsg);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleOpenAdd = () => {
    setEditingConfig(null);
    setFormData(defaultFormData);
    setFormError(null);
    setShowForm(true);
  };

  const handleOpenEdit = (config: APIConfigResponse) => {
    setEditingConfig(config);
    setFormData({
      name: config.name,
      api_key: "",
      base_url: config.base_url,
      model: config.model,
      embedding_model: config.embedding_model,
    });
    setFormError(null);
    setShowForm(true);
  };

  const handleCloseForm = () => {
    setShowForm(false);
    setEditingConfig(null);
    setFormData(defaultFormData);
    setFormError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormLoading(true);
    setFormError(null);

    try {
      if (editingConfig) {
        const updateData: any = {
          name: formData.name,
          base_url: formData.base_url,
          model: formData.model,
          embedding_model: formData.embedding_model,
        };
        if (formData.api_key.trim()) {
          updateData.api_key = formData.api_key;
        }
        await apiConfigApi.update(editingConfig.id, updateData);
      } else {
        await apiConfigApi.create({
          name: formData.name,
          api_key: formData.api_key,
          base_url: formData.base_url,
          model: formData.model,
          embedding_model: formData.embedding_model,
        });
      }

      await loadConfigs();
      handleCloseForm();
      onConfigChange?.();
    } catch (err: any) {
      setFormError(err.response?.data?.detail || "保存失败");
    } finally {
      setFormLoading(false);
    }
  };

  const handleActivate = async (configId: string) => {
    try {
      await apiConfigApi.activate(configId);
      await loadConfigs();
      onConfigChange?.();
    } catch (err: any) {
      setError(err.response?.data?.detail || "切换失败");
    }
  };

  const handleDelete = async (configId: string) => {
    if (!confirm("确定要删除这个API配置吗？")) {
      return;
    }

    try {
      await apiConfigApi.delete(configId);
      await loadConfigs();
      onConfigChange?.();
    } catch (err: any) {
      setError(err.response?.data?.detail || "删除失败");
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="absolute inset-0 bg-black/50"
        onClick={onClose}
      />
      
      <div className="relative w-full max-w-2xl max-h-[90vh] bg-white dark:bg-slate-800 rounded-2xl shadow-2xl flex flex-col m-4">
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-700">
          <h2 className="text-xl font-semibold text-gray-900 dark:text-white">
            API 设置
          </h2>
          <button
            onClick={onClose}
            className="p-2 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
          >
            <X className="w-5 h-5 text-gray-500" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-6">
          {error && (
            <div className="mb-4 p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg flex items-center gap-3">
              <AlertCircle className="w-5 h-5 text-red-500 flex-shrink-0" />
              <span className="text-sm text-red-600 dark:text-red-400">{error}</span>
            </div>
          )}

          {!showForm ? (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  管理您的 API 配置，支持多个提供商（OpenAI、Anthropic、Azure OpenAI 等）
                </p>
                <button
                  onClick={handleOpenAdd}
                  className="flex items-center gap-2 px-4 py-2 bg-primary-600 hover:bg-primary-700 text-white text-sm font-medium rounded-lg transition-colors"
                >
                  <Plus className="w-4 h-4" />
                  添加配置
                </button>
              </div>

              {loading ? (
                <div className="flex flex-col items-center justify-center py-12">
                  <RefreshCw className="w-8 h-8 text-gray-400 animate-spin mb-3" />
                  <p className="text-gray-500 dark:text-gray-400">加载中...</p>
                </div>
              ) : configs.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-center">
                  <Key className="w-12 h-12 text-gray-300 dark:text-gray-600 mb-3" />
                  <h3 className="text-gray-900 dark:text-white font-medium mb-1">
                    暂无 API 配置
                  </h3>
                  <p className="text-sm text-gray-500 dark:text-gray-400 mb-4">
                    添加您的第一个 API 配置以开始使用
                  </p>
                  <button
                    onClick={handleOpenAdd}
                    className="flex items-center gap-2 px-4 py-2 bg-primary-600 hover:bg-primary-700 text-white text-sm font-medium rounded-lg transition-colors"
                  >
                    <Plus className="w-4 h-4" />
                    添加第一个配置
                  </button>
                </div>
              ) : (
                <div className="space-y-3">
                  {configs.map((config) => (
                    <div
                      key={config.id}
                      className={cn(
                        "p-4 border rounded-xl transition-all",
                        config.is_active
                          ? "border-primary-500 bg-primary-50 dark:bg-primary-900/10"
                          : "border-gray-200 dark:border-gray-700 hover:border-gray-300 dark:hover:border-gray-600"
                      )}
                    >
                      <div className="flex items-start justify-between">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <h4 className="font-medium text-gray-900 dark:text-white">
                              {config.name}
                            </h4>
                            {config.is_active && (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-400 text-xs font-medium rounded-full">
                                <Check className="w-3 h-3" />
                                当前使用
                              </span>
                            )}
                          </div>
                          <div className="mt-2 space-y-1 text-xs text-gray-500 dark:text-gray-400">
                            <div className="flex items-center gap-2">
                              <Globe className="w-3 h-3" />
                              <span className="truncate">{config.base_url}</span>
                            </div>
                            <div className="flex items-center gap-4">
                              <div className="flex items-center gap-2">
                                <Brain className="w-3 h-3" />
                                <span>模型: {config.model}</span>
                              </div>
                              <span>Embedding: {config.embedding_model}</span>
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-2 ml-4">
                          {!config.is_active && (
                            <button
                              onClick={() => handleActivate(config.id)}
                              className="px-3 py-1.5 text-xs font-medium text-primary-600 dark:text-primary-400 hover:bg-primary-50 dark:hover:bg-primary-900/20 rounded-lg transition-colors"
                            >
                              切换
                            </button>
                          )}
                          <button
                            onClick={() => handleOpenEdit(config)}
                            className="p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                            title="编辑"
                          >
                            <Edit2 className="w-4 h-4 text-gray-500" />
                          </button>
                          {configs.length > 1 && (
                            <button
                              onClick={() => handleDelete(config.id)}
                              className="p-1.5 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-lg transition-colors"
                              title="删除"
                            >
                              <Trash2 className="w-4 h-4 text-red-500" />
                            </button>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="flex items-center gap-2 mb-6">
                <button
                  type="button"
                  onClick={handleCloseForm}
                  className="p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                >
                  <X className="w-4 h-4 text-gray-500" />
                </button>
                <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
                  {editingConfig ? "编辑 API 配置" : "添加 API 配置"}
                </h3>
              </div>

              {formError && (
                <div className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
                  <span className="text-sm text-red-600 dark:text-red-400">{formError}</span>
                </div>
              )}

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  配置名称 *
                </label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  placeholder="例如：OpenAI 官方、Azure OpenAI、自定义代理"
                  className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-slate-700 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  required
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  API Key * {editingConfig && "(留空则保持不变)"}
                </label>
                <input
                  type="password"
                  value={formData.api_key}
                  onChange={(e) => setFormData({ ...formData, api_key: e.target.value })}
                  placeholder="sk-..."
                  className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-slate-700 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  required={!editingConfig}
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Base URL *
                </label>
                <input
                  type="url"
                  value={formData.base_url}
                  onChange={(e) => setFormData({ ...formData, base_url: e.target.value })}
                  placeholder="https://api.openai.com/v1"
                  className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-slate-700 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  required
                />
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                  使用 OpenAI 兼容的 API 端点，如：OpenAI、Azure OpenAI、Anthropic (兼容模式) 等
                </p>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                    对话模型
                  </label>
                  <input
                    type="text"
                    value={formData.model}
                    onChange={(e) => setFormData({ ...formData, model: e.target.value })}
                    placeholder="gpt-3.5-turbo"
                    className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-slate-700 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                    Embedding 模型
                  </label>
                  <input
                    type="text"
                    value={formData.embedding_model}
                    onChange={(e) => setFormData({ ...formData, embedding_model: e.target.value })}
                    placeholder="text-embedding-3-small"
                    className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-slate-700 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                </div>
              </div>

              <div className="flex justify-end gap-3 pt-4 border-t border-gray-200 dark:border-gray-700">
                <button
                  type="button"
                  onClick={handleCloseForm}
                  className="px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                >
                  取消
                </button>
                <button
                  type="submit"
                  disabled={formLoading}
                  className="px-4 py-2 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 disabled:opacity-50 rounded-lg transition-colors flex items-center gap-2"
                >
                  {formLoading ? (
                    <RefreshCw className="w-4 h-4 animate-spin" />
                  ) : null}
                  {editingConfig ? "保存" : "添加"}
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
