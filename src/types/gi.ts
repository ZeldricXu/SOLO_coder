export interface GIQualityPreset {
  name: 'off' | 'low' | 'medium' | 'high';
  label: string;
  hemisphereLight: boolean;
  ssao: boolean;
  ssaoRadius: number;
  ssaoIntensity: number;
  ssaoSamples: number;
  ambientBounces: number;
}

export interface GIRenderSettings {
  enabled: boolean;
  quality: 'off' | 'low' | 'medium' | 'high';
  hemisphereLight: {
    enabled: boolean;
    skyColor: { r: number; g: number; b: number };
    groundColor: { r: number; g: number; b: number };
    intensity: number;
  };
  ssao: {
    enabled: boolean;
    radius: number;
    intensity: number;
    luminanceInfluence: number;
    worldDistanceThreshold: number;
    worldDistanceFalloff: number;
    worldProximityThreshold: number;
    worldProximityFalloff: number;
  };
  screenSpaceReflections?: boolean;
  bounceLight: boolean;
  exposure: number;
}

export const GI_QUALITY_PRESETS: Record<GIQualityPreset['name'], GIQualityPreset> = {
  off: {
    name: 'off',
    label: '关闭',
    hemisphereLight: false,
    ssao: false,
    ssaoRadius: 0,
    ssaoIntensity: 0,
    ssaoSamples: 0,
    ambientBounces: 0,
  },
  low: {
    name: 'low',
    label: '低',
    hemisphereLight: true,
    ssao: true,
    ssaoRadius: 0.3,
    ssaoIntensity: 0.3,
    ssaoSamples: 8,
    ambientBounces: 0,
  },
  medium: {
    name: 'medium',
    label: '中',
    hemisphereLight: true,
    ssao: true,
    ssaoRadius: 0.5,
    ssaoIntensity: 0.5,
    ssaoSamples: 16,
    ambientBounces: 1,
  },
  high: {
    name: 'high',
    label: '高',
    hemisphereLight: true,
    ssao: true,
    ssaoRadius: 0.8,
    ssaoIntensity: 0.7,
    ssaoSamples: 32,
    ambientBounces: 2,
  },
};

export const createDefaultGISettings = (): GIRenderSettings => ({
  enabled: true,
  quality: 'medium',
  hemisphereLight: {
    enabled: true,
    skyColor: { r: 0.65, g: 0.75, b: 0.9 },
    groundColor: { r: 0.45, g: 0.4, b: 0.35 },
    intensity: 0.6,
  },
  ssao: {
    enabled: true,
    radius: 0.5,
    intensity: 0.5,
    luminanceInfluence: 0.5,
    worldDistanceThreshold: 10,
    worldDistanceFalloff: 1,
    worldProximityThreshold: 0.5,
    worldProximityFalloff: 0.1,
  },
  bounceLight: true,
  exposure: 1.0,
});
