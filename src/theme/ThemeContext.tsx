import React, { createContext, useContext, useEffect, useMemo, useState, useCallback } from 'react';
import type { ThemeMode, DesignTokens } from '@types';
import { lightTokens } from './tokens/light';
import { darkTokens } from './tokens/dark';

interface ThemeContextValue {
  mode: ThemeMode;
  tokens: DesignTokens;
  setMode: (mode: ThemeMode) => void;
  toggleMode: () => void;
}

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

const getInitialMode = (): ThemeMode => {
  if (typeof window === 'undefined') {
    return 'light';
  }

  const stored = localStorage.getItem('df1-57-theme');
  if (stored === 'light' || stored === 'dark') {
    return stored;
  }

  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  return prefersDark ? 'dark' : 'light';
};

export interface ThemeProviderProps {
  children: React.ReactNode;
  defaultMode?: ThemeMode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children, defaultMode }) => {
  const [mode, setModeState] = useState<ThemeMode>(defaultMode ?? getInitialMode);

  const tokens = useMemo(() => (mode === 'light' ? lightTokens : darkTokens), [mode]);

  useEffect(() => {
    if (typeof document === 'undefined') {
      return;
    }
    document.documentElement.setAttribute('data-theme', mode);
    localStorage.setItem('df1-57-theme', mode);
  }, [mode]);

  const setMode = useCallback((newMode: ThemeMode) => {
    setModeState(newMode);
  }, []);

  const toggleMode = useCallback(() => {
    setModeState((prev) => (prev === 'light' ? 'dark' : 'light'));
  }, []);

  const value = useMemo(
    () => ({
      mode,
      tokens,
      setMode,
      toggleMode,
    }),
    [mode, tokens, setMode, toggleMode],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
};

export const useTheme = (): ThemeContextValue => {
  const context = useContext(ThemeContext);
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
};

export const useTokens = (): DesignTokens => {
  return useTheme().tokens;
};
