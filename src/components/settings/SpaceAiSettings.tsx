'use client';

import React, { useState } from 'react';
import { Bot, Info, Loader2 } from 'lucide-react';
import { trpc } from '@/components/providers/TRPCProvider';

interface SpaceAiSettingsProps {
  spaceId: string;
  isAdmin: boolean;
}

export function SpaceAiSettings({ spaceId, isAdmin }: SpaceAiSettingsProps) {
  const [isUpdating, setIsUpdating] = useState(false);

  const aiQaStatusQuery = trpc.space.getAiQaStatus.useQuery(
    { spaceId },
    { enabled: isAdmin }
  );

  const setAiQaEnabledMutation = trpc.space.setAiQaEnabled.useMutation();

  const handleToggle = async (enabled: boolean) => {
    if (!isAdmin || isUpdating) return;

    setIsUpdating(true);
    try {
      await setAiQaEnabledMutation.mutateAsync({
        spaceId,
        enabled,
      });
      aiQaStatusQuery.refetch();
    } catch (error) {
      console.error('Failed to update AI QA settings:', error);
    } finally {
      setIsUpdating(false);
    }
  };

  if (!isAdmin) {
    return null;
  }

  const isEnabled = aiQaStatusQuery.data?.data?.aiQaEnabled ?? false;

  return (
    <div className="bg-white rounded-lg border border-gray-200 p-6">
      <div className="flex items-start gap-4">
        <div className="w-12 h-12 bg-blue-50 rounded-lg flex items-center justify-center flex-shrink-0">
          <Bot size={24} className="text-blue-600" />
        </div>
        <div className="flex-1">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-lg font-semibold text-gray-900">AI 问答功能</h3>
              <p className="text-sm text-gray-500 mt-1">
                启用后，用户可以使用自然语言提问，系统将基于知识库内容自动生成回答
              </p>
            </div>
            <button
              onClick={() => handleToggle(!isEnabled)}
              disabled={isUpdating || aiQaStatusQuery.isLoading}
              className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 ${
                isEnabled ? 'bg-blue-600' : 'bg-gray-200'
              } ${isUpdating || aiQaStatusQuery.isLoading ? 'opacity-50 cursor-not-allowed' : ''}`}
            >
              <span className="sr-only">Enable AI QA</span>
              <span
                className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                  isEnabled ? 'translate-x-5' : 'translate-x-0'
                }`}
              >
                {isUpdating && (
                  <Loader2 size={16} className="h-full w-full animate-spin text-gray-400" />
                )}
              </span>
            </button>
          </div>

          <div className="mt-4 p-4 bg-blue-50 rounded-lg">
            <div className="flex items-start gap-3">
              <Info size={18} className="text-blue-600 flex-shrink-0 mt-0.5" />
              <div className="text-sm text-blue-800">
                <p className="font-medium mb-2">功能说明</p>
                <ul className="space-y-1 text-blue-700">
                  <li>• 基于 RAG (检索增强生成) 技术，确保回答的准确性</li>
                  <li>• 先搜索知识库中的相关文档，再基于文档内容生成回答</li>
                  <li>• 每个回答都会标注引用来源，方便溯源验证</li>
                  <li>• 仅搜索当前空间内的文档，保证数据安全</li>
                  <li>• 需要配置 LLM API 密钥才能使用</li>
                </ul>
              </div>
            </div>
          </div>

          {isEnabled && (
            <div className="mt-4 p-4 bg-green-50 rounded-lg border border-green-200">
              <div className="flex items-center gap-2 text-green-700">
                <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
                <span className="text-sm font-medium">AI 问答功能已启用</span>
              </div>
              <p className="text-sm text-green-600 mt-2">
                用户现在可以在知识库页面使用 AI 助手提问了
              </p>
            </div>
          )}

          <div className="mt-4 text-xs text-gray-500">
            <p>配置 LLM API 密钥需要在环境变量中设置：</p>
            <ul className="mt-1 space-y-0.5 font-mono">
              <li>• LLM_PROVIDER=openai|anthropic</li>
              <li>• LLM_API_KEY=your-api-key</li>
              <li>• LLM_MODEL=gpt-3.5-turbo (可选)</li>
              <li>• LLM_BASE_URL=https://api.openai.com/v1 (可选)</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
}
