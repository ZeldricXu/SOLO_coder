import { useState, useEffect, useCallback } from 'react';
import type { Theme } from '@/types/theme';
import { themeManager } from '@/lib/themeManager';
import { uiEventBus } from '@/lib/uiEventBus';

export function useTheme() {
  const [currentThemeId, setCurrentThemeId] = useState<string>(() => {
    const saved = localStorage.getItem('archplan:theme');
    if (saved && themeManager.getTheme(saved)) {
      themeManager.setTheme(saved);
      return saved;
    }
    return themeManager.getCurrentThemeId();
  });

  const [theme, setThemeState] = useState<Theme>(() => themeManager.getCurrentTheme());

  useEffect(() => {
    return themeManager.onChange((t) => {
      setThemeState(t);
    });
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    const colors = theme.ui;

    root.style.setProperty('--color-bg', colors.background);
    root.style.setProperty('--color-surface', colors.surface);
    root.style.setProperty('--color-surface-elevated', colors.surfaceElevated);
    root.style.setProperty('--color-border', colors.border);
    root.style.setProperty('--color-text-primary', colors.textPrimary);
    root.style.setProperty('--color-text-secondary', colors.textSecondary);
    root.style.setProperty('--color-text-muted', colors.textMuted);
    root.style.setProperty('--color-accent', colors.accent);
    root.style.setProperty('--color-accent-hover', colors.accentHover);
    root.style.setProperty('--color-warning', colors.warning);
    root.style.setProperty('--color-success', colors.success);
    root.style.setProperty('--color-error', colors.error);
    root.style.setProperty('--color-info', colors.info);
    root.style.setProperty('--color-canvas-bg', colors.canvasBackground);

    root.classList.remove('light', 'dark');
    root.classList.add(theme.name.includes('深色') || theme.name.includes('dark') ? 'dark' : 'light');
  }, [theme]);

  const setTheme = useCallback((id: string) => {
    if (themeManager.setTheme(id)) {
      setCurrentThemeId(id);
      localStorage.setItem('archplan:theme', id);
      uiEventBus.emit({ name: 'theme:change', payload: id });
    }
  }, []);

  const toggleTheme = useCallback(() => {
    const list = themeManager.listThemes();
    const idx = list.findIndex((t) => t.id === currentThemeId);
    const next = list[(idx + 1) % list.length];
    setTheme(next.id);
  }, [currentThemeId, setTheme]);

  const listThemes = useCallback(() => themeManager.listThemes(), []);
  const getLightingPreset = useCallback((id?: string) => themeManager.getLightingPreset(id), []);
  const getRenderQuality = useCallback((id?: string) => themeManager.getRenderQuality(id), []);
  const getDefaultMaterials = useCallback(() => themeManager.getDefaultMaterials(), []);

  return {
    theme,
    themeId: currentThemeId,
    setTheme,
    toggleTheme,
    listThemes,
    getLightingPreset,
    getRenderQuality,
    getDefaultMaterials,
  };
}
