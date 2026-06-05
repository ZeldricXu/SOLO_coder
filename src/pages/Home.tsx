import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, FolderOpen, Download, Upload, Trash2, Clock, FileText, Settings, LayoutGrid, List } from 'lucide-react';
import { getAllDrafts, deleteDraft, formatBytes, getStorageUsage, type DraftMeta } from '@/utils/storage/indexedDB';
import { importFromFile } from '@/utils/io/importExport';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';

const Home: React.FC = () => {
  const navigate = useNavigate();
  const { setFloorPlan } = useFloorPlanStore();
  const [drafts, setDrafts] = useState<DraftMeta[]>([]);
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [storageInfo, setStorageInfo] = useState<{ used: number; quota: number | null }>({ used: 0, quota: null });
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    loadDrafts();
  }, []);

  const loadDrafts = async () => {
    setIsLoading(true);
    try {
      const [draftList, storage] = await Promise.all([getAllDrafts(), getStorageUsage()]);
      setDrafts(draftList);
      setStorageInfo(storage);
    } catch (error) {
      console.error('加载草稿失败:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleNewProject = () => {
    navigate('/editor');
  };

  const handleOpenDraft = async (id: string) => {
    navigate(`/editor/${id}`);
  };

  const handleDeleteDraft = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    if (confirm('确定要删除这个项目吗？此操作不可恢复。')) {
      await deleteDraft(id);
      await loadDrafts();
    }
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    try {
      const floorPlan = await importFromFile(file);
      setFloorPlan(floorPlan);
      navigate('/editor');
    } catch (error) {
      alert(error instanceof Error ? error.message : '导入失败');
    } finally {
      e.target.value = '';
    }
  };

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleString('zh-CN', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div className="min-h-screen bg-canvas-bg text-white">
      <header className="border-b border-neutral-800 bg-neutral-900/50 backdrop-blur-sm sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-accent-primary to-accent-secondary flex items-center justify-center">
              <LayoutGrid size={20} className="text-white" />
            </div>
            <div>
              <h1 className="text-xl font-bold font-display">ArchPlan Studio</h1>
              <p className="text-xs text-neutral-500">网页端建筑平面图编辑器</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button className="px-4 py-2 text-sm text-neutral-400 hover:text-white transition-colors">
              帮助中心
            </button>
            <button className="p-2 rounded-lg bg-neutral-800 text-neutral-400 hover:text-white transition-colors">
              <Settings size={18} />
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-8">
        <div className="mb-8">
          <h2 className="text-2xl font-bold mb-2">开始设计</h2>
          <p className="text-neutral-400">创建新的户型图或打开最近的项目</p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-12">
          <button
            onClick={handleNewProject}
            className="group p-6 rounded-xl border-2 border-dashed border-neutral-700 hover:border-accent-primary bg-neutral-800/50 hover:bg-neutral-800 transition-all text-left"
          >
            <div className="w-12 h-12 rounded-lg bg-accent-primary/10 group-hover:bg-accent-primary/20 flex items-center justify-center mb-4 transition-colors">
              <Plus size={24} className="text-accent-primary" />
            </div>
            <h3 className="text-lg font-semibold mb-1">新建项目</h3>
            <p className="text-sm text-neutral-400">从零开始创建一个新的户型图设计</p>
          </button>

          <label className="group p-6 rounded-xl border-2 border-dashed border-neutral-700 hover:border-accent-secondary bg-neutral-800/50 hover:bg-neutral-800 transition-all cursor-pointer">
            <div className="w-12 h-12 rounded-lg bg-accent-secondary/10 group-hover:bg-accent-secondary/20 flex items-center justify-center mb-4 transition-colors">
              <Upload size={24} className="text-accent-secondary" />
            </div>
            <h3 className="text-lg font-semibold mb-1">导入文件</h3>
            <p className="text-sm text-neutral-400">导入 JSON 或 DXF 格式的户型图</p>
            <input type="file" accept=".json,.dxf" onChange={handleImportFile} className="hidden" />
          </label>

          <div className="p-6 rounded-xl bg-gradient-to-br from-neutral-800 to-neutral-900 border border-neutral-700">
            <div className="flex items-center gap-2 mb-3">
              <FolderOpen size={20} className="text-neutral-400" />
              <span className="text-sm text-neutral-400">存储空间</span>
            </div>
            <div className="mb-3">
              <div className="flex justify-between text-sm mb-1">
                <span className="text-neutral-300">已使用</span>
                <span className="text-white font-mono">{formatBytes(storageInfo.used)}</span>
              </div>
              <div className="w-full h-2 bg-neutral-700 rounded-full overflow-hidden">
                <div
                  className="h-full bg-gradient-to-r from-accent-primary to-accent-secondary transition-all"
                  style={{
                    width: storageInfo.quota
                      ? `${Math.min((storageInfo.used / storageInfo.quota) * 100, 100)}%`
                      : '30%',
                  }}
                />
              </div>
              {storageInfo.quota && (
                <p className="text-xs text-neutral-500 mt-1">
                  总计 {formatBytes(storageInfo.quota)}
                </p>
              )}
            </div>
            <p className="text-xs text-neutral-500">{drafts.length} 个项目</p>
          </div>
        </div>

        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-4">
            <h3 className="text-lg font-semibold">最近项目</h3>
            <span className="text-sm text-neutral-500">({drafts.length})</span>
          </div>
          <div className="flex items-center gap-1 bg-neutral-800 rounded-lg p-1">
            <button
              onClick={() => setViewMode('grid')}
              className={`p-1.5 rounded transition-colors ${
                viewMode === 'grid' ? 'bg-neutral-700 text-white' : 'text-neutral-400 hover:text-white'
              }`}
            >
              <LayoutGrid size={16} />
            </button>
            <button
              onClick={() => setViewMode('list')}
              className={`p-1.5 rounded transition-colors ${
                viewMode === 'list' ? 'bg-neutral-700 text-white' : 'text-neutral-400 hover:text-white'
              }`}
            >
              <List size={16} />
            </button>
          </div>
        </div>

        {isLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="w-8 h-8 border-2 border-neutral-700 border-t-accent-primary rounded-full animate-spin" />
          </div>
        ) : drafts.length > 0 ? (
          viewMode === 'grid' ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
              {drafts.map((draft) => (
                <div
                  key={draft.id}
                  onClick={() => handleOpenDraft(draft.id)}
                  className="group bg-neutral-800 rounded-xl overflow-hidden border border-neutral-700 hover:border-accent-primary transition-all cursor-pointer"
                >
                  <div className="aspect-[4/3] bg-neutral-900 relative overflow-hidden">
                    {draft.thumbnail ? (
                      <img
                        src={draft.thumbnail}
                        alt={draft.name}
                        className="w-full h-full object-cover"
                      />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center">
                        <FileText size={32} className="text-neutral-600" />
                      </div>
                    )}
                    <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent opacity-0 group-hover:opacity-100 transition-opacity flex items-end justify-between p-3">
                      <button className="px-3 py-1.5 bg-accent-primary text-white rounded text-xs font-medium">
                        打开
                      </button>
                      <button
                        onClick={(e) => handleDeleteDraft(e, draft.id)}
                        className="p-1.5 bg-red-500/80 text-white rounded hover:bg-red-500 transition-colors"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </div>
                  <div className="p-3">
                    <h4 className="font-medium text-sm truncate">{draft.name}</h4>
                    <div className="flex items-center gap-1 mt-1 text-xs text-neutral-500">
                      <Clock size={10} />
                      <span>{formatDate(draft.updatedAt)}</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="bg-neutral-800 rounded-xl overflow-hidden border border-neutral-700">
              {drafts.map((draft, index) => (
                <div
                  key={draft.id}
                  onClick={() => handleOpenDraft(draft.id)}
                  className={`flex items-center gap-4 p-4 hover:bg-neutral-700/50 transition-colors cursor-pointer ${
                    index !== drafts.length - 1 ? 'border-b border-neutral-700' : ''
                  }`}
                >
                  <div className="w-16 h-12 rounded bg-neutral-900 overflow-hidden flex-shrink-0">
                    {draft.thumbnail ? (
                      <img
                        src={draft.thumbnail}
                        alt={draft.name}
                        className="w-full h-full object-cover"
                      />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center">
                        <FileText size={16} className="text-neutral-600" />
                      </div>
                    )}
                  </div>
                  <div className="flex-1 min-w-0">
                    <h4 className="font-medium text-sm truncate">{draft.name}</h4>
                    <p className="text-xs text-neutral-500 truncate">{draft.description || '无描述'}</p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <p className="text-xs text-neutral-400">更新于</p>
                    <p className="text-xs text-neutral-500">{formatDate(draft.updatedAt)}</p>
                  </div>
                  <div className="flex items-center gap-1 flex-shrink-0">
                    <button
                      onClick={(e) => handleDeleteDraft(e, draft.id)}
                      className="p-2 text-neutral-500 hover:text-red-400 hover:bg-red-500/10 rounded transition-colors"
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )
        ) : (
          <div className="text-center py-16 bg-neutral-800/50 rounded-xl border border-neutral-700 border-dashed">
            <FolderOpen size={48} className="mx-auto mb-4 text-neutral-600" />
            <h3 className="text-lg font-medium text-neutral-400 mb-2">暂无项目</h3>
            <p className="text-sm text-neutral-500 mb-6">点击上方"新建项目"开始你的第一个设计</p>
            <button
              onClick={handleNewProject}
              className="px-6 py-2.5 bg-accent-primary text-white rounded-lg hover:bg-accent-hover transition-colors"
            >
              创建项目
            </button>
          </div>
        )}
      </main>

      <footer className="border-t border-neutral-800 mt-12">
        <div className="max-w-7xl mx-auto px-6 py-6 flex items-center justify-between text-sm text-neutral-500">
          <p>© 2025 ArchPlan Studio. 网页端建筑平面图编辑器</p>
          <div className="flex items-center gap-4">
            <a href="#" className="hover:text-neutral-300 transition-colors">使用条款</a>
            <a href="#" className="hover:text-neutral-300 transition-colors">隐私政策</a>
            <a href="#" className="hover:text-neutral-300 transition-colors">关于我们</a>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default Home;
