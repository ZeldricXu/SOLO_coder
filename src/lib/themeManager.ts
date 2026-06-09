import type { ThemeConfig, Theme } from '@/types/theme';
import rawThemeConfig from '@/config/theme.json';

const themeConfig = rawThemeConfig as ThemeConfig;

export class ThemeManager {
  private currentThemeId: string;
  private listeners: Set<(theme: Theme) => void> = new Set();
  private static instance: ThemeManager;

  static getInstance(): ThemeManager {
    if (!ThemeManager.instance) {
      ThemeManager.instance = new ThemeManager();
    }
    return ThemeManager.instance;
  }

  constructor() {
    this.currentThemeId = themeConfig.defaultTheme;
  }

  getConfig(): ThemeConfig {
    return themeConfig;
  }

  listThemes(): { id: string; name: string; description: string }[] {
    return Object.entries(themeConfig.themes).map(([id, t]) => ({
      id,
      name: t.name,
      description: t.description,
    }));
  }

  getTheme(id?: string): Theme {
    const themeId = id || this.currentThemeId;
    const theme = themeConfig.themes[themeId];
    if (!theme) {
      console.warn(`Theme not found: ${themeId}, using default`);
      return themeConfig.themes[themeConfig.defaultTheme];
    }
    return theme;
  }

  getCurrentTheme(): Theme {
    return this.getTheme(this.currentThemeId);
  }

  getCurrentThemeId(): string {
    return this.currentThemeId;
  }

  getDefaultMaterials() {
    return this.getCurrentTheme().materials.defaults;
  }

  getLightingPreset(presetId?: string) {
    const theme = this.getCurrentTheme();
    const id = presetId || theme.lighting.defaultPreset;
    return theme.lighting.presets[id] || theme.lighting.presets[theme.lighting.defaultPreset];
  }

  getRenderQuality(qualityId?: string) {
    const theme = this.getCurrentTheme();
    const id = qualityId || theme.rendering.defaultQuality;
    return theme.rendering.qualityPresets[id] || theme.rendering.qualityPresets[theme.rendering.defaultQuality];
  }

  setTheme(id: string): boolean {
    if (!themeConfig.themes[id]) return false;
    this.currentThemeId = id;
    this.notifyListeners();
    return true;
  }

  onChange(listener: (theme: Theme) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notifyListeners(): void {
    const theme = this.getCurrentTheme();
    this.listeners.forEach((l) => l(theme));
  }
}

export const themeManager = ThemeManager.getInstance();
