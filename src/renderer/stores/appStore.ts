import { create } from 'zustand';
import type { Theme, AppSettings, PanelLayout, Note } from '@shared/types';
import { useEffect } from 'react';

const themes: Theme[] = [
  {
    id: 'dark',
    name: '暗色',
    variables: {},
  },
  {
    id: 'light',
    name: '亮色',
    variables: {},
  },
  {
    id: 'high-contrast',
    name: '高对比',
    variables: {},
  },
];

interface AppState {
  settings: AppSettings | null;
  currentNote: Note | null;
  allNotes: Note[];
  searchQuery: string;
  searchResults: any[];
  isLoading: boolean;
  showCommandPalette: boolean;
  
  setSettings: (settings: AppSettings) => void;
  setCurrentNote: (note: Note | null) => void;
  setAllNotes: (notes: Note[]) => void;
  setSearchQuery: (query: string) => void;
  setSearchResults: (results: any[]) => void;
  setIsLoading: (loading: boolean) => void;
  setShowCommandPalette: (show: boolean) => void;
  
  updateLayout: (layouts: PanelLayout[]) => void;
  togglePanel: (panelId: string) => void;
  
  themes: Theme[];
  setTheme: (themeId: string) => void;
}

export const useAppStore = create<AppState>((set, get) => ({
  settings: null,
  currentNote: null,
  allNotes: [],
  searchQuery: '',
  searchResults: [],
  isLoading: false,
  showCommandPalette: false,
  
  setSettings: (settings) => set({ settings }),
  setCurrentNote: (note) => set({ currentNote: note }),
  setAllNotes: (notes) => set({ allNotes: notes }),
  setSearchQuery: (query) => set({ searchQuery: query }),
  setSearchResults: (results) => set({ searchResults: results }),
  setIsLoading: (loading) => set({ isLoading: loading }),
  setShowCommandPalette: (show) => set({ showCommandPalette: show }),
  
  updateLayout: (layouts) => {
    const { settings } = get();
    if (settings) {
      const newSettings = { ...settings, layouts };
      set({ settings: newSettings });
      window.api.settings.update(newSettings);
    }
  },
  
  togglePanel: (panelId) => {
    const { settings } = get();
    if (!settings) return;
    
    const newLayouts = settings.layouts.map(panel =>
      panel.id === panelId ? { ...panel, visible: !panel.visible } : panel
    );
    
    get().updateLayout(newLayouts);
  },
  
  themes,
  
  setTheme: (themeId) => {
    const { settings } = get();
    if (settings) {
      const newSettings = { ...settings, theme: themeId };
      set({ settings: newSettings });
      window.api.settings.update({ theme: themeId });
      
      document.documentElement.setAttribute('data-theme', themeId);
    }
  },
}));

export function useTheme() {
  const { settings, themes, setTheme } = useAppStore();
  
  useEffect(() => {
    if (settings?.theme) {
      document.documentElement.setAttribute('data-theme', settings.theme);
    }
  }, [settings?.theme]);
  
  return {
    currentTheme: settings?.theme || 'dark',
    themes,
    setTheme,
  };
}
