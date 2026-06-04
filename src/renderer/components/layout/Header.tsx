import React from 'react';
import { useTheme } from '../../contexts/ThemeContext';
import { useAppStore } from '../../stores/appStore';

export const Header: React.FC = () => {
  const { toggleTheme, resolvedTheme } = useTheme();
  const toggleSidebar = useAppStore((state) => state.toggleSidebar);
  const sidebarCollapsed = useAppStore((state) => state.sidebarCollapsed);
  const activeTab = useAppStore((state) => state.activeTab);
  const setActiveTab = useAppStore((state) => state.setActiveTab);
  const currentDocument = useAppStore((state) => state.currentDocument);
  const stats = useAppStore((state) => state.stats);

  const tabs: Array<{ id: 'editor' | 'graph' | 'search' | 'git' | 'export'; label: string; icon: string }> = [
    { id: 'editor', label: '编辑器', icon: '📝' },
    { id: 'graph', label: '知识图谱', icon: '🕸️' },
    { id: 'search', label: '搜索', icon: '🔍' },
    { id: 'git', label: 'Git', icon: '🔀' },
    { id: 'export', label: '导出', icon: '📦' },
  ];

  return (
    <header
      className="h-12 flex items-center justify-between px-4 border-b"
      style={{
        backgroundColor: 'var(--card-background)',
        borderColor: 'var(--border-color)',
      }}
    >
      <div className="flex items-center gap-4">
        <button
          onClick={toggleSidebar}
          className="btn-icon"
          title={sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'}
        >
          {sidebarCollapsed ? '☰' : '✕'}
        </button>
        <div className="flex items-center gap-2">
          <span className="text-xl">📚</span>
          <span className="font-semibold">KnowledgeForge</span>
        </div>
        <div className="flex gap-1 ml-4">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`tab ${activeTab === tab.id ? 'tab-active' : ''}`}
            >
              <span className="mr-1">{tab.icon}</span>
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex items-center gap-4">
        {currentDocument && (
          <div className="text-sm text-gray-500 dark:text-gray-400">
            正在编辑: <span className="font-medium">{currentDocument.title}</span>
          </div>
        )}

        <div className="flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
          <span title="文档数量">📄 {stats.totalDocuments}</span>
          <span>|</span>
          <span title="标签数量">🏷️ {stats.totalTags}</span>
          <span>|</span>
          <span title="总字数">✍️ {stats.totalWords.toLocaleString()}</span>
        </div>

        <button onClick={toggleTheme} className="btn-icon" title="切换主题">
          {resolvedTheme === 'dark' ? '☀️' : '🌙'}
        </button>

        <button className="btn-icon" title="设置">
          ⚙️
        </button>
      </div>
    </header>
  );
};
