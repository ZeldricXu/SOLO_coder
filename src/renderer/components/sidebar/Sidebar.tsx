import React, { useState } from 'react';
import { useAppStore } from '../../stores/appStore';
import { formatRelative } from '@shared/utils/date';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { TemplateVariable } from '@shared/types';

export const Sidebar: React.FC = () => {
  const sidebarCollapsed = useAppStore((state) => state.sidebarCollapsed);
  const documents = useAppStore((state) => state.documents);
  const tags = useAppStore((state) => state.tags);
  const currentDocId = useAppStore((state) => state.currentDocId);
  const setCurrentDocument = useAppStore((state) => state.setCurrentDocument);
  const createDocument = useAppStore((state) => state.createDocument);
  const selectedTags = useAppStore((state) => state.selectedTags);
  const toggleTagFilter = useAppStore((state) => state.toggleTagFilter);
  const searchQuery = useAppStore((state) => state.searchQuery);
  const setSearchQuery = useAppStore((state) => state.setSearchQuery);
  const [showNewDocModal, setShowNewDocModal] = useState(false);
  const [newDocTitle, setNewDocTitle] = useState('');
  const [activeSection, setActiveSection] = useState<'files' | 'tags' | 'templates'>('files');
  const [showTemplateVarModal, setShowTemplateVarModal] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [templateVariables, setTemplateVariables] = useState<TemplateVariable[]>([]);
  const [templateVarInputs, setTemplateVarInputs] = useState<Record<string, string>>({});

  const filteredDocuments = documents.filter((doc) => {
    const matchesSearch = searchQuery
      ? doc.title.toLowerCase().includes(searchQuery.toLowerCase())
      : true;
    const matchesTags =
      selectedTags.length === 0 || selectedTags.some((tag) => doc.tags.includes(tag));
    return matchesSearch && matchesTags;
  });

  const handleCreateDocument = async () => {
    if (!newDocTitle.trim()) return;
    const doc = await createDocument(newDocTitle.trim());
    if (doc) {
      await setCurrentDocument(doc.id);
      setShowNewDocModal(false);
      setNewDocTitle('');
    }
  };

  const handleOpenDocument = async (docId: string) => {
    await setCurrentDocument(docId);
  };

  const handleCreateFromTemplate = async (templateId: string) => {
    try {
      const result = await window.electron.ipc.invoke<any>(
        IPC_CHANNELS.TEMPLATE.GET_VARIABLES,
        templateId
      );

      if (result?.success && result.data && result.data.length > 0) {
        const userVars = result.data.filter((v: TemplateVariable) => v.requiresInput);
        if (userVars.length > 0) {
          setSelectedTemplateId(templateId);
          setTemplateVariables(result.data);
          const initialInputs: Record<string, string> = {};
          userVars.forEach((v: TemplateVariable) => {
            initialInputs[v.name] = v.defaultValue || '';
          });
          setTemplateVarInputs(initialInputs);
          setShowTemplateVarModal(true);
          return;
        }
      }

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

  const handleCreateWithVariables = async () => {
    if (!selectedTemplateId) return;

    try {
      const result = await window.electron.ipc.invoke<any>(
        IPC_CHANNELS.TEMPLATE.RENDER,
        selectedTemplateId,
        templateVarInputs
      );

      if (result?.success && result.data) {
        const title = templateVarInputs.title || result.data.title || '未命名文档';
        const doc = await createDocument(title, result.data.content);
        if (doc) {
          await setCurrentDocument(doc.id);
        }
      }

      setShowTemplateVarModal(false);
      setSelectedTemplateId(null);
      setTemplateVariables([]);
      setTemplateVarInputs({});
    } catch (error) {
      console.error('使用变量创建文档失败:', error);
    }
  };

  const sections = [
    { id: 'files', label: '文件', icon: '📁' },
    { id: 'tags', label: '标签', icon: '🏷️' },
    { id: 'templates', label: '模板', icon: '📋' },
  ] as const;

  const templates = [
    { id: 'daily', name: '每日笔记', icon: '📅' },
    { id: 'meeting', name: '会议记录', icon: '💼' },
    { id: 'retrospective', name: '项目复盘', icon: '🔄' },
    { id: 'api', name: 'API文档', icon: '🔌' },
    { id: 'design', name: '技术方案', icon: '💡' },
    { id: 'blank', name: '空白文档', icon: '📄' },
  ];

  return (
    <aside
      className="fixed left-0 top-12 bottom-0 flex flex-col border-r transition-all duration-300 z-10"
      style={{
        width: sidebarCollapsed ? '48px' : '256px',
        backgroundColor: 'var(--sidebar-background)',
        borderColor: 'var(--border-color)',
      }}
    >
      {!sidebarCollapsed && (
        <>
          <div className="p-3 border-b" style={{ borderColor: 'var(--border-color)' }}>
            <input
              type="text"
              placeholder="搜索文档..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="input w-full text-sm"
            />
          </div>

          <div className="p-2 border-b" style={{ borderColor: 'var(--border-color)' }}>
            <button
              onClick={() => setShowNewDocModal(true)}
              className="btn btn-primary w-full flex items-center justify-center gap-2"
            >
              <span>+</span>
              新建文档
            </button>
          </div>

          <div className="flex border-b" style={{ borderColor: 'var(--border-color)' }}>
            {sections.map((section) => (
              <button
                key={section.id}
                onClick={() => setActiveSection(section.id)}
                className={`flex-1 py-2 text-xs transition-colors ${
                  activeSection === section.id
                    ? 'text-blue-500 border-b-2 border-blue-500'
                    : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'
                }`}
                title={section.label}
              >
                {section.icon}
              </button>
            ))}
          </div>

          <div className="flex-1 overflow-y-auto p-2">
            {activeSection === 'files' && (
              <div className="space-y-1">
                {filteredDocuments.length === 0 ? (
                  <div className="text-center py-8 text-gray-400 text-sm">
                    {searchQuery
                      ? '没有找到匹配的文档'
                      : '还没有文档，点击上方按钮创建'}
                  </div>
                ) : (
                  filteredDocuments.map((doc) => (
                    <div
                      key={doc.id}
                      onClick={() => handleOpenDocument(doc.id)}
                      className={`sidebar-item ${
                        currentDocId === doc.id ? 'sidebar-item-active' : ''
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <span className="text-lg">📄</span>
                        <div className="flex-1 min-w-0">
                          <div className="font-medium truncate">{doc.title}</div>
                          <div className="text-xs text-gray-400 truncate">
                            {formatRelative(doc.updatedAt)} · {doc.wordCount} 字
                          </div>
                        </div>
                      </div>
                      {doc.tags.length > 0 && (
                        <div className="flex gap-1 mt-1 flex-wrap">
                          {doc.tags.slice(0, 3).map((tag) => (
                            <span
                              key={tag}
                              className="badge badge-primary text-[10px]"
                              onClick={(e) => {
                                e.stopPropagation();
                                toggleTagFilter(tag);
                              }}
                            >
                              #{tag}
                            </span>
                          ))}
                          {doc.tags.length > 3 && (
                            <span className="text-[10px] text-gray-400">
                              +{doc.tags.length - 3}
                            </span>
                          )}
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            )}

            {activeSection === 'tags' && (
              <div className="space-y-1">
                {tags.length === 0 ? (
                  <div className="text-center py-8 text-gray-400 text-sm">
                    还没有标签
                  </div>
                ) : (
                  tags.map((tag) => (
                    <div
                      key={tag.name}
                      onClick={() => toggleTagFilter(tag.name)}
                      className={`sidebar-item flex items-center justify-between ${
                        selectedTags.includes(tag.name) ? 'sidebar-item-active' : ''
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <span className="badge badge-primary">#{tag.name}</span>
                      </div>
                      <span className="text-xs text-gray-400">{tag.documentCount}</span>
                    </div>
                  ))
                )}
              </div>
            )}

            {activeSection === 'templates' && (
              <div className="space-y-1">
                {templates.map((template) => (
                  <div
                    key={template.id}
                    onClick={() => handleCreateFromTemplate(template.id)}
                    className="sidebar-item flex items-center gap-2"
                  >
                    <span className="text-lg">{template.icon}</span>
                    <span>{template.name}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}

      {sidebarCollapsed && (
        <div className="flex flex-col items-center py-2 gap-2">
          <button
            onClick={() => setShowNewDocModal(true)}
            className="btn-icon"
            title="新建文档"
          >
            +
          </button>
          {sections.map((section) => (
            <button
              key={section.id}
              onClick={() => {
                setActiveSection(section.id);
              }}
              className={`btn-icon ${
                activeSection === section.id ? 'bg-blue-100 dark:bg-blue-900/30' : ''
              }`}
              title={section.label}
            >
              {section.icon}
            </button>
          ))}
        </div>
      )}

      {showNewDocModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="card p-6 w-96">
            <h3 className="text-lg font-semibold mb-4">新建文档</h3>
            <input
              type="text"
              placeholder="输入文档标题..."
              value={newDocTitle}
              onChange={(e) => setNewDocTitle(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleCreateDocument()}
              className="input w-full mb-4"
              autoFocus
            />
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setShowNewDocModal(false)}
                className="btn btn-secondary"
              >
                取消
              </button>
              <button onClick={handleCreateDocument} className="btn btn-primary">
                创建
              </button>
            </div>
          </div>
        </div>
      )}

      {showTemplateVarModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="card p-6 w-[440px] max-h-[80vh] overflow-y-auto">
            <h3 className="text-lg font-semibold mb-4">设置模板变量</h3>
            <div className="space-y-4 mb-6">
              {templateVariables.map((v) => (
                <div key={v.name}>
                  <label className="block text-sm font-medium mb-1.5">
                    {v.label || v.name}
                    {v.requiresInput && <span className="text-red-500 ml-1">*</span>}
                  </label>
                  {v.requiresInput ? (
                    <input
                      type="text"
                      value={templateVarInputs[v.name] || ''}
                      onChange={(e) =>
                        setTemplateVarInputs((prev) => ({
                          ...prev,
                          [v.name]: e.target.value,
                        }))
                      }
                      placeholder={v.description || ''}
                      className="input w-full"
                      autoFocus={v.name === 'title'}
                    />
                  ) : (
                    <div className="text-sm text-gray-500 dark:text-gray-400 bg-gray-100 dark:bg-gray-800 p-2 rounded">
                      自动填充: {v.description || v.name}
                    </div>
                  )}
                </div>
              ))}
            </div>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => {
                  setShowTemplateVarModal(false);
                  setSelectedTemplateId(null);
                  setTemplateVariables([]);
                  setTemplateVarInputs({});
                }}
                className="btn btn-secondary"
              >
                取消
              </button>
              <button onClick={handleCreateWithVariables} className="btn btn-primary">
                创建文档
              </button>
            </div>
          </div>
        </div>
      )}
    </aside>
  );
};
