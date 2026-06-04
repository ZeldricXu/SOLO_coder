import React, { useState } from 'react';
import { useAppStore } from '../stores/appStore';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import { formatRelative } from '@shared/utils/date';

export const WelcomePage: React.FC = () => {
  const documents = useAppStore((state) => state.documents);
  const stats = useAppStore((state) => state.stats);
  const setCurrentDocument = useAppStore((state) => state.setCurrentDocument);
  const createDocument = useAppStore((state) => state.createDocument);
  const [newDocTitle, setNewDocTitle] = useState('');
  const setActiveTab = useAppStore((state) => state.setActiveTab);

  const handleCreateDocument = async () => {
    if (!newDocTitle.trim()) return;
    const doc = await createDocument(newDocTitle.trim());
    if (doc) {
      await setCurrentDocument(doc.id);
    }
  };

  const handleCreateFromTemplate = async (templateId: string) => {
    try {
      const doc = await window.electron.ipc.invoke<any>(
        IPC_CHANNELS.TEMPLATE.CREATE,
        templateId
      );
      if (doc) {
        await setCurrentDocument(doc.id);
      }
    } catch (error) {
      console.error('从模板创建文档失败:', error);
    }
  };

  const recentDocuments = [...documents]
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
    .slice(0, 5);

  const templates = [
    { id: 'daily', name: '每日笔记', icon: '📅', desc: '记录每日工作和学习' },
    { id: 'meeting', name: '会议记录', icon: '💼', desc: '整理会议要点和决议' },
    { id: 'retrospective', name: '项目复盘', icon: '🔄', desc: '总结经验教训' },
    { id: 'api', name: 'API文档', icon: '🔌', desc: '记录接口规范' },
    { id: 'design', name: '技术方案', icon: '💡', desc: '设计技术架构' },
    { id: 'blank', name: '空白文档', icon: '📄', desc: '自由创作' },
  ];

  return (
    <div className="h-full overflow-y-auto p-8">
      <div className="max-w-4xl mx-auto">
        <div className="text-center mb-12">
          <div className="text-6xl mb-4">📚</div>
          <h1 className="text-3xl font-bold mb-2">欢迎使用 KnowledgeForge</h1>
          <p className="text-gray-500 dark:text-gray-400">
            您的个人知识库，让知识管理更简单高效
          </p>
        </div>

        <div className="grid grid-cols-4 gap-4 mb-8">
          <div className="card p-4 text-center">
            <div className="text-3xl font-bold text-blue-500">{stats.totalDocuments}</div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">文档总数</div>
          </div>
          <div className="card p-4 text-center">
            <div className="text-3xl font-bold text-green-500">{stats.totalTags}</div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">标签总数</div>
          </div>
          <div className="card p-4 text-center">
            <div className="text-3xl font-bold text-purple-500">
              {stats.totalWords.toLocaleString()}
            </div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">总字数</div>
          </div>
          <div className="card p-4 text-center">
            <div className="text-3xl font-bold text-orange-500">{stats.totalLinks}</div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">双向链接</div>
          </div>
        </div>

        <div className="mb-8">
          <h2 className="text-xl font-semibold mb-4">快速开始</h2>
          <div className="card p-6">
            <div className="flex gap-4 items-center mb-4">
              <input
                type="text"
                placeholder="输入文档标题开始创作..."
                value={newDocTitle}
                onChange={(e) => setNewDocTitle(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleCreateDocument()}
                className="input flex-1 text-base"
                autoFocus
              />
              <button onClick={handleCreateDocument} className="btn btn-primary px-6">
                创建文档
              </button>
            </div>
            <div className="text-sm text-gray-500 dark:text-gray-400">
              或者从模板开始：
            </div>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-4 mb-8">
          {templates.map((template) => (
            <button
              key={template.id}
              onClick={() => handleCreateFromTemplate(template.id)}
              className="card p-4 text-left hover:border-blue-400 transition-colors group"
            >
              <div className="text-2xl mb-2">{template.icon}</div>
              <div className="font-medium group-hover:text-blue-500 transition-colors">
                {template.name}
              </div>
              <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">
                {template.desc}
              </div>
            </button>
          ))}
        </div>

        {recentDocuments.length > 0 && (
          <div>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-xl font-semibold">最近编辑</h2>
              <button
                onClick={() => setActiveTab('search')}
                className="text-sm text-blue-500 hover:text-blue-600"
              >
                查看全部 →
              </button>
            </div>
            <div className="card divide-y" style={{ borderColor: 'var(--border-color)' }}>
              {recentDocuments.map((doc) => (
                <div
                  key={doc.id}
                  onClick={() => setCurrentDocument(doc.id)}
                  className="p-4 hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer transition-colors flex items-center justify-between"
                >
                  <div className="flex items-center gap-3">
                    <span className="text-xl">📄</span>
                    <div>
                      <div className="font-medium">{doc.title}</div>
                      <div className="text-sm text-gray-500 dark:text-gray-400">
                        {formatRelative(doc.updatedAt)} · {doc.wordCount} 字
                      </div>
                    </div>
                  </div>
                  {doc.tags.length > 0 && (
                    <div className="flex gap-1">
                      {doc.tags.slice(0, 2).map((tag) => (
                        <span key={tag} className="badge badge-primary">
                          #{tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="mt-12 grid grid-cols-3 gap-4 text-center">
          <div className="p-4">
            <div className="text-2xl mb-2">🔍</div>
            <div className="font-medium">全文搜索</div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              支持中英文混合搜索
            </div>
          </div>
          <div className="p-4">
            <div className="text-2xl mb-2">🕸️</div>
            <div className="font-medium">知识图谱</div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              可视化文档关联关系
            </div>
          </div>
          <div className="p-4">
            <div className="text-2xl mb-2">🔀</div>
            <div className="font-medium">Git 版本控制</div>
            <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              自动提交，历史追溯
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
