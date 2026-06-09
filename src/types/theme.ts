import type { Material } from './floorplan';
import type { RGB } from './geometry';

export interface UIThemeColors {
  background: string;
  surface: string;
  surfaceElevated: string;
  border: string;
  textPrimary: string;
  textSecondary: string;
  textMuted: string;
  accent: string;
  accentHover: string;
  warning: string;
  success: string;
  error: string;
  info: string;
  canvasBackground: string;
}

export interface UIHelperMaterials {
  wireframe: { color: string; opacity: number };
  selectionOutline: { color: string; opacity: number };
  glass: { color: string; transmission: number; opacity: number };
  preview: { color: string; opacity: number };
}

export interface ThemeMaterials {
  defaults: Material[];
  uiHelpers: UIHelperMaterials;
}

export interface LightingPreset {
  name: string;
  ambientIntensity: number;
  directionalIntensity: number;
  directionalColor: string;
  hemisphereSky: string;
  hemisphereGround: string;
  hemisphereIntensity: number;
  exposure: number;
}

export interface ThemeLighting {
  presets: Record<string, LightingPreset>;
  defaultPreset: string;
}

export interface RenderQualityPreset {
  name: string;
  shadowMapSize: number;
  pixelRatio: number;
  ssaoEnabled: boolean;
  ssaoSamples?: number;
  antialias: boolean;
}

export interface ThemeRendering {
  qualityPresets: Record<string, RenderQualityPreset>;
  defaultQuality: string;
  toneMapping: string;
  backgroundColor: string;
  fogColor: string;
  fogNear: number;
  fogFar: number;
}

export interface Theme {
  name: string;
  description: string;
  ui: UIThemeColors;
  materials: ThemeMaterials;
  lighting: ThemeLighting;
  rendering: ThemeRendering;
}

export interface ThemeConfig {
  version: string;
  defaultTheme: string;
  themes: Record<string, Theme>;
}
