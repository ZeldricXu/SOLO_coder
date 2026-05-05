import React, { useState, useEffect, useCallback } from 'react';
import { Note, Folder, Tag, SearchResult } from '../shared/types';
import { IPCSyncConflict, IPCConflictResolution } from '../shared/ipc-channels';
import { Sidebar } from './components/Sidebar';
import { NoteList } from './components/NoteList';
import { Editor } from './components/Editor';
import { CreateNoteModal, CreateFolderModal, SettingsModal } from './components/Modals';
import { ConflictModal } from './components/ConflictModal';
import { Toast } from './components/Toast';
import './styles/index.css';

interface AppState {
  notes: Note[];
  folders: Folder[];
  tags: Tag[];
  currentFolderId: string | null;
  currentNoteId: string | null;
  searchResults: SearchResult[];
  isSearching: boolean;
  searchQuery: string;
}

export const App: React.FC = () => {
  const [state, setState] = useState<AppState>({
    notes: [],
    folders: [],
    tags: [],
    currentFolderId: null,
    currentNoteId: null,
    searchResults: [],
    isSearching: false,
    searchQuery: '',
  });

  const [showCreateNoteModal, setShowCreateNoteModal] = useState(false);
  const [showCreateFolderModal, setShowCreateFolderModal] = useState(false);
  const [showSettingsModal, setShowSettingsModal] = useState(false);
  const [conflicts, setConflicts] = useState<IPCSyncConflict[]>([]);
  const [showConflictModal, setShowConflictModal] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' | 'info' } | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncStatus, setSyncStatus] = useState<{ 
    isSyncing: boolean; 
    conflictCount: number 
  }>({ isSyncing: false, conflictCount: 0 });

  const loadData = useCallback(async () => {
    if (!window.electronAPI) return;

    try {
      setLoading(true);
      const [notesRes, foldersRes, tagsRes, syncStatusRes] = await Promise.all([
        window.electronAPI.note.list(state.currentFolderId),
        window.electronAPI.folder.list(),
        window.electronAPI.tag.list(),
        window.electronAPI.sync.getStatus(),
      ]);

      if (notesRes.success && foldersRes.success && tagsRes.success) {
        setState(prev => ({
          ...prev,
          notes: notesRes.data || [],
          folders: foldersRes.data || [],
          tags: tagsRes.data || [],
        }));
      }

      if (syncStatusRes.success && syncStatusRes.data) {
        setSyncStatus({
          isSyncing: syncStatusRes.data.isSyncing,
          conflictCount: syncStatusRes.data.conflictCount,
        });

        if (syncStatusRes.data.conflictCount > 0) {
          loadConflicts();
        }
      }
    } catch (error) {
      showToast('加载数据失败', 'error');
      console.error('Load data error:', error);
    } finally {
      setLoading(false);
    }
  }, [state.currentFolderId]);

  const loadConflicts = async () => {
    if (!window.electronAPI) return;
    
    try {
      const result = await window.electronAPI.sync.getConflicts();
      if (result.success && result.data) {
        setConflicts(result.data);
        if (result.data.length > 0) {
          setShowConflictModal(true);
        }
      }
    } catch (error) {
      console.error('Load conflicts error:', error);
    }
  };

  useEffect(() => {
    loadData();
  }, [loadData]);

  const showToast = (message: string, type: 'success' | 'error' | 'info') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  };

  const handleCreateNote = async (title: string, folderId?: string) => {
    if (!window.electronAPI) return;

    try {
      const result = await window.electronAPI.note.create({
        title: title || '未命名笔记',
        content: '',
        content_type: 'markdown',
        folder_id: folderId || state.currentFolderId || undefined,
        tags: [],
      });

      if (result.success && result.data) {
        setState(prev => ({
          ...prev,
          notes: [result.data!, ...prev.notes],
          currentNoteId: result.data!.note_id,
        }));
        showToast('笔记创建成功', 'success');
      } else {
        showToast(result.error || '创建笔记失败', 'error');
      }
    } catch (error) {
      showToast('创建笔记失败', 'error');
    }
  };

  const handleCreateFolder = async (name: string, parentId?: string) => {
    if (!window.electronAPI) return;

    try {
      const result = await window.electronAPI.folder.create({
        name,
        parent_id: parentId,
        order_index: state.folders.length,
      });

      if (result.success && result.data) {
        setState(prev => ({
          ...prev,
          folders: [...prev.folders, result.data!],
        }));
        showToast('文件夹创建成功', 'success');
      } else {
        showToast(result.error || '创建文件夹失败', 'error');
      }
    } catch (error) {
      showToast('创建文件夹失败', 'error');
    }
  };

  const handleSelectFolder = async (folderId: string | null) => {
    setState(prev => ({
      ...prev,
      currentFolderId: folderId,
      currentNoteId: null,
      isSearching: false,
      searchQuery: '',
      searchResults: [],
    }));
  };

  const handleSelectNote = (noteId: string) => {
    setState(prev => ({
      ...prev,
      currentNoteId: noteId,
    }));
  };

  const handleDeleteNote = async (noteId: string) => {
    if (!window.electronAPI) return;

    try {
      const result = await window.electronAPI.note.delete(noteId);
      if (result.success) {
        setState(prev => ({
          ...prev,
          notes: prev.notes.filter(n => n.note_id !== noteId),
          currentNoteId: prev.currentNoteId === noteId ? null : prev.currentNoteId,
        }));
        showToast('笔记已删除', 'success');
      } else {
        showToast(result.error || '删除失败', 'error');
      }
    } catch (error) {
      showToast('删除失败', 'error');
    }
  };

  const handleSearch = async (query: string) => {
    if (!window.electronAPI) return;

    setState(prev => ({
      ...prev,
      searchQuery: query,
      isSearching: !!query,
    }));

    if (!query.trim()) {
      setState(prev => ({
        ...prev,
        searchResults: [],
        isSearching: false,
      }));
      return;
    }

    try {
      const result = await window.electronAPI.search.query({
        keyword: query,
        limit: 50,
      });

      if (result.success) {
        setState(prev => ({
          ...prev,
          searchResults: result.data || [],
        }));
      }
    } catch (error) {
      console.error('Search error:', error);
    }
  };

  const handleResolveConflict = async (noteId: string, resolution: IPCConflictResolution) => {
    if (!window.electronAPI) return;

    try {
      const result = await window.electronAPI.sync.resolveConflict(noteId, resolution);
      if (result.success) {
        const updatedConflicts = conflicts.filter(c => c.note_id !== noteId);
        setConflicts(updatedConflicts);
        
        const resolutionText = {
          'keep_local': '已保留本地版本',
          'use_remote': '已使用云端版本',
          'merge': '已合并内容',
        }[resolution];
        
        showToast(`冲突解决: ${resolutionText}`, 'success');
        
        await loadData();
      } else {
        showToast(result.error || '解决冲突失败', 'error');
      }
    } catch (error) {
      showToast('解决冲突失败', 'error');
    }
  };

  const handleNoteUpdated = async () => {
    await loadData();
  };

  const handleCloseConflictModal = () => {
    setShowConflictModal(false);
  };

  const currentNote = state.notes.find(n => n.note_id === state.currentNoteId) || null;
  const displayNotes = state.isSearching 
    ? state.searchResults.map(r => ({
        ...r,
        folder_id: null,
        created_at: r.updated_at,
        word_count: 0,
        ai_summary: null,
        sync_status: 'synced' as const,
        version: 1,
        content_type: 'markdown' as const,
        content: r.preview,
      }))
    : state.notes;

  const getStatusDotClass = () => {
    if (syncStatus.isSyncing) return 'status-dot syncing';
    if (syncStatus.conflictCount > 0) return 'status-dot error';
    return 'status-dot synced';
  };

  const getStatusText = () => {
    if (syncStatus.isSyncing) return '同步中...';
    if (syncStatus.conflictCount > 0) return `${syncStatus.conflictCount} 个冲突待解决`;
    return '已同步';
  };

  return (
    <div className="app-container">
      <div className="main-layout">
        <Sidebar
          folders={state.folders}
          currentFolderId={state.currentFolderId}
          onSelectFolder={handleSelectFolder}
          onCreateNote={() => setShowCreateNoteModal(true)}
          onCreateFolder={() => setShowCreateFolderModal(true)}
          onOpenSettings={() => setShowSettingsModal(true)}
        />

        <NoteList
          notes={displayNotes}
          currentNoteId={state.currentNoteId}
          isSearching={state.isSearching}
          searchQuery={state.searchQuery}
          onSelectNote={handleSelectNote}
          onDeleteNote={handleDeleteNote}
          onSearch={handleSearch}
          onCreateNote={() => setShowCreateNoteModal(true)}
        />

        {currentNote ? (
          <Editor
            note={currentNote}
            onNoteUpdated={handleNoteUpdated}
            tags={state.tags}
          />
        ) : (
          <div className="editor-container">
            <div className="empty-state">
              <div className="empty-state-icon">📝</div>
              <div className="empty-state-text">选择一个笔记开始编辑</div>
              <div className="empty-state-hint">或点击新建按钮创建新笔记</div>
            </div>
          </div>
        )}
      </div>

      <div className="status-bar">
        <div className="status-bar-left">
          <span>{state.notes.length} 个笔记</span>
          {state.currentNoteId && currentNote && (
            <span>{currentNote.word_count} 字</span>
          )}
        </div>
        <div className="status-bar-right">
          {syncStatus.conflictCount > 0 && (
            <button
              style={{
                background: 'none',
                border: '1px solid #ff9500',
                color: '#ff9500',
                padding: '2px 8px',
                borderRadius: '4px',
                fontSize: '11px',
                cursor: 'pointer',
                marginRight: '12px',
              }}
              onClick={loadConflicts}
            >
              解决 {syncStatus.conflictCount} 个冲突
            </button>
          )}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <div className={getStatusDotClass()} />
            <span>{getStatusText()}</span>
          </div>
        </div>
      </div>

      {showCreateNoteModal && (
        <CreateNoteModal
          folders={state.folders}
          currentFolderId={state.currentFolderId}
          onClose={() => setShowCreateNoteModal(false)}
          onCreate={handleCreateNote}
        />
      )}

      {showCreateFolderModal && (
        <CreateFolderModal
          folders={state.folders}
          onClose={() => setShowCreateFolderModal(false)}
          onCreate={handleCreateFolder}
        />
      )}

      {showSettingsModal && (
        <SettingsModal
          onClose={() => setShowSettingsModal(false)}
        />
      )}

      {showConflictModal && conflicts.length > 0 && (
        <ConflictModal
          conflicts={conflicts}
          onResolve={handleResolveConflict}
          onClose={handleCloseConflictModal}
        />
      )}

      {toast && (
        <Toast message={toast.message} type={toast.type} />
      )}
    </div>
  );
};
