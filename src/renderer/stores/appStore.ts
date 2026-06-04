import { create } from 'zustand';
import type { Document, Tag, AppSettings, AppStats, EditorMode } from '@shared/types';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';

interface AppState {
  documents: Document[];
  tags: Tag[];
  currentDocument: Document | null;
  currentDocId: string | null;
  settings: AppSettings;
  stats: AppStats;
  isLoading: boolean;
  error: string | null;
  editorMode: EditorMode;
  sidebarCollapsed: boolean;
  activeTab: 'editor' | 'graph' | 'search' | 'git' | 'export';
  searchQuery: string;
  selectedTags: string[];
}

interface AppActions {
  loadDocuments: () => Promise<void>;
  loadTags: () => Promise<void>;
  loadSettings: () => Promise<void>;
  loadStats: () => Promise<void>;
  setCurrentDocument: (docId: string | null) => Promise<void>;
  saveCurrentDocument: (content: string) => Promise<void>;
  createDocument: (title: string, content?: string, tags?: string[]) => Promise<Document | null>;
  deleteDocument: (docId: string) => Promise<boolean>;
  updateSettings: (settings: Partial<AppSettings>) => Promise<void>;
  setEditorMode: (mode: EditorMode) => void;
  toggleSidebar: () => void;
  setActiveTab: (tab: AppState['activeTab']) => void;
  setSearchQuery: (query: string) => void;
  toggleTagFilter: (tag: string) => void;
  setError: (error: string | null) => void;
  setLoading: (loading: boolean) => void;
  initApp: () => Promise<void>;
}

const defaultSettings: AppSettings = {
  theme: 'system',
  editorTheme: 'system',
  language: 'zh-CN',
  defaultEditorMode: 'split',
  editorFontFamily: 'JetBrains Mono, monospace',
  fontSize: 14,
  lineHeight: 1.6,
  showLineNumbers: true,
  tabSize: 2,
  autoSave: true,
  autoSaveInterval: 30000,
  gitAutoCommit: true,
  gitAutoCommitInterval: 300000,
  searchIncludeContent: true,
  searchHighlight: true,
  repositoryPath: '',
  backupEnabled: false,
  backupInterval: 86400000,
  graphNodeSize: 20,
  graphLinkDistance: 150,
  graphChargeStrength: -300,
  searchResultLimit: 50,
  searchSortBy: 'relevance',
  recentFilesLimit: 20,
};

const defaultStats: AppStats = {
  totalDocuments: 0,
  totalWords: 0,
  totalTags: 0,
  totalLinks: 0,
  totalBacklinks: 0,
  todayEdited: 0,
  last7DaysActivity: [0, 0, 0, 0, 0, 0, 0],
  recentDocuments: [],
  topTags: [],
};

export const useAppStore = create<AppState & AppActions>((set, get) => ({
  documents: [],
  tags: [],
  currentDocument: null,
  currentDocId: null,
  settings: defaultSettings,
  stats: defaultStats,
  isLoading: false,
  error: null,
  editorMode: 'split',
  sidebarCollapsed: false,
  activeTab: 'editor',
  searchQuery: '',
  selectedTags: [],

  loadDocuments: async () => {
    try {
      set({ isLoading: true });
      const docs = await window.electron.ipc.invoke<Document[]>(IPC_CHANNELS.DOCUMENT.LIST);
      set({ documents: docs, isLoading: false });
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '加载文档失败', isLoading: false });
    }
  },

  loadTags: async () => {
    try {
      const tags = await window.electron.ipc.invoke<Tag[]>(IPC_CHANNELS.TAG.LIST);
      set({ tags });
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '加载标签失败' });
    }
  },

  loadSettings: async () => {
    try {
      const settings = await window.electron.ipc.invoke<AppSettings>(IPC_CHANNELS.SETTINGS.GET);
      if (settings) {
        set({ settings: { ...defaultSettings, ...settings } });
      }
    } catch (error) {
      console.error('加载设置失败:', error);
    }
  },

  loadStats: async () => {
    try {
      const stats = await window.electron.ipc.invoke<AppStats>(IPC_CHANNELS.DB.STATS_GET);
      if (stats) {
        set({ stats });
      }
    } catch (error) {
      console.error('加载统计信息失败:', error);
    }
  },

  setCurrentDocument: async (docId: string | null) => {
    if (!docId) {
      set({ currentDocument: null, currentDocId: null });
      return;
    }

    try {
      set({ isLoading: true });
      const doc = await window.electron.ipc.invoke<Document | null>(IPC_CHANNELS.DOCUMENT.GET, docId);
      if (doc) {
        set({ currentDocument: doc, currentDocId: docId, isLoading: false });
      } else {
        set({ error: '文档不存在', isLoading: false });
      }
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '加载文档失败', isLoading: false });
    }
  },

  saveCurrentDocument: async (content: string) => {
    const { currentDocument } = get();
    if (!currentDocument) return;

    try {
      const updated = await window.electron.ipc.invoke<Document>(
        IPC_CHANNELS.DOCUMENT.UPDATE,
        currentDocument.id,
        { content }
      );
      set({ currentDocument: updated });
      await get().loadDocuments();
      await get().loadStats();
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '保存文档失败' });
    }
  },

  createDocument: async (title: string, content: string = '', tags: string[] = []) => {
    try {
      set({ isLoading: true });
      const doc = await window.electron.ipc.invoke<Document>(
        IPC_CHANNELS.DOCUMENT.CREATE,
        { title, content, tags }
      );
      set({ isLoading: false });
      await get().loadDocuments();
      await get().loadTags();
      await get().loadStats();
      return doc;
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '创建文档失败', isLoading: false });
      return null;
    }
  },

  deleteDocument: async (docId: string) => {
    try {
      const success = await window.electron.ipc.invoke<boolean>(
        IPC_CHANNELS.DOCUMENT.DELETE,
        docId
      );
      if (success) {
        const { currentDocId } = get();
        if (currentDocId === docId) {
          set({ currentDocument: null, currentDocId: null });
        }
        await get().loadDocuments();
        await get().loadTags();
        await get().loadStats();
      }
      return success;
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '删除文档失败' });
      return false;
    }
  },

  updateSettings: async (newSettings: Partial<AppSettings>) => {
    try {
      const currentSettings = get().settings;
      const merged = { ...currentSettings, ...newSettings };
      await window.electron.ipc.invoke<void>(IPC_CHANNELS.SETTINGS.SET, merged);
      set({ settings: merged });
    } catch (error) {
      set({ error: error instanceof Error ? error.message : '更新设置失败' });
    }
  },

  setEditorMode: (mode: EditorMode) => set({ editorMode: mode }),

  toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),

  setActiveTab: (tab: AppState['activeTab']) => set({ activeTab: tab }),

  setSearchQuery: (query: string) => set({ searchQuery: query }),

  toggleTagFilter: (tag: string) =>
    set((state) => ({
      selectedTags: state.selectedTags.includes(tag)
        ? state.selectedTags.filter((t) => t !== tag)
        : [...state.selectedTags, tag],
    })),

  setError: (error: string | null) => set({ error }),

  setLoading: (loading: boolean) => set({ isLoading: loading }),

  initApp: async () => {
    try {
      set({ isLoading: true });
      await Promise.all([
        get().loadDocuments(),
        get().loadTags(),
        get().loadSettings(),
        get().loadStats(),
      ]);
      set({ isLoading: false });
    } catch (error) {
      set({
        error: error instanceof Error ? error.message : '初始化应用失败',
        isLoading: false,
      });
    }
  },
}));
