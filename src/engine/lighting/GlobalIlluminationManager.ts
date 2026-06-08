import * as THREE from 'three';
import type { GIRenderSettings } from '@/types/gi';
import { createDefaultGISettings, GI_QUALITY_PRESETS } from '@/types/gi';

export class GlobalIlluminationManager {
  private scene: THREE.Scene;
  private settings: GIRenderSettings;
  private hemisphereLight: THREE.HemisphereLight | null = null;
  private isEnabled: boolean = true;

  constructor(scene: THREE.Scene, initialSettings?: Partial<GIRenderSettings>) {
    this.scene = scene;
    this.settings = { ...createDefaultGISettings(), ...initialSettings };
    this.initialize();
  }

  private initialize(): void {
    if (this.settings.hemisphereLight.enabled) {
      this.createHemisphereLight();
    }
  }

  private createHemisphereLight(): void {
    this.removeHemisphereLight();

    const { skyColor, groundColor, intensity } = this.settings.hemisphereLight;
    const sky = new THREE.Color(skyColor.r, skyColor.g, skyColor.b);
    const ground = new THREE.Color(groundColor.r, groundColor.g, groundColor.b);

    this.hemisphereLight = new THREE.HemisphereLight(sky, ground, intensity);
    this.hemisphereLight.name = 'gi-hemisphere';
    this.scene.add(this.hemisphereLight);
  }

  private removeHemisphereLight(): void {
    if (this.hemisphereLight) {
      this.scene.remove(this.hemisphereLight);
      this.hemisphereLight.dispose();
      this.hemisphereLight = null;
    }
  }

  updateSettings(newSettings: Partial<GIRenderSettings>): void {
    this.settings = { ...this.settings, ...newSettings };
    this.applySettings();
  }

  applyPreset(preset: keyof typeof GI_QUALITY_PRESETS): void {
    const presetConfig = GI_QUALITY_PRESETS[preset];

    this.updateSettings({
      enabled: preset !== 'off',
      quality: preset,
      hemisphereLight: {
        ...this.settings.hemisphereLight,
        enabled: presetConfig.hemisphereLight,
      },
      ssao: {
        ...this.settings.ssao,
        enabled: presetConfig.ssao,
        radius: presetConfig.ssaoRadius,
        intensity: presetConfig.ssaoIntensity,
      },
    });
  }

  private applySettings(): void {
    if (!this.settings.enabled || !this.settings.hemisphereLight.enabled) {
      this.removeHemisphereLight();
      return;
    }

    if (!this.hemisphereLight) {
      this.createHemisphereLight();
    } else {
      const { skyColor, groundColor, intensity } = this.settings.hemisphereLight;
      this.hemisphereLight.color.setRGB(skyColor.r, skyColor.g, skyColor.b);
      this.hemisphereLight.groundColor.setRGB(groundColor.r, groundColor.g, groundColor.b);
      this.hemisphereLight.intensity = intensity;
    }
  }

  enable(): void {
    this.isEnabled = true;
    this.updateSettings({ enabled: true });
  }

  disable(): void {
    this.isEnabled = false;
    this.updateSettings({ enabled: false });
  }

  toggle(): boolean {
    if (this.isEnabled) {
      this.disable();
    } else {
      this.enable();
    }
    return this.isEnabled;
  }

  getSettings(): GIRenderSettings {
    return { ...this.settings };
  }

  getSSAOConfig() {
    const { enabled: giEnabled, ssao } = this.settings;
    const { enabled: ssaoEnabled, ...ssaoRest } = ssao;
    return {
      enabled: giEnabled && ssaoEnabled,
      ...ssaoRest,
    };
  }

  getExposure(): number {
    return this.settings.exposure;
  }

  setExposure(value: number): void {
    this.settings.exposure = Math.max(0.1, Math.min(3, value));
  }

  setHemisphereIntensity(intensity: number): void {
    this.updateSettings({
      hemisphereLight: {
        ...this.settings.hemisphereLight,
        intensity: Math.max(0, Math.min(5, intensity)),
      },
    });
  }

  setSSAOIntensity(intensity: number): void {
    this.updateSettings({
      ssao: {
        ...this.settings.ssao,
        intensity: Math.max(0, Math.min(2, intensity)),
      },
    });
  }

  setSSAORadius(radius: number): void {
    this.updateSettings({
      ssao: {
        ...this.settings.ssao,
        radius: Math.max(0.01, Math.min(5, radius)),
      },
    });
  }

  dispose(): void {
    this.removeHemisphereLight();
  }
}
