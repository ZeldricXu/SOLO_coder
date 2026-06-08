import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { GlobalIlluminationManager } from '@/engine/lighting/GlobalIlluminationManager';
import { GI_QUALITY_PRESETS, createDefaultGISettings } from '@/types/gi';

describe('全局光照管理器 - 正常路径测试', () => {
  let scene: THREE.Scene;
  let giManager: GlobalIlluminationManager;

  beforeEach(() => {
    scene = new THREE.Scene();
    giManager = new GlobalIlluminationManager(scene);
  });

  it('应该在初始化时根据默认设置创建半球光', () => {
    const hemisphereLight = scene.children.find(
      (c) => c instanceof THREE.HemisphereLight
    ) as THREE.HemisphereLight;

    expect(hemisphereLight).toBeDefined();
    expect(hemisphereLight.name).toBe('gi-hemisphere');
    expect(hemisphereLight.intensity).toBeCloseTo(0.6, 1);
  });

  it('应该正确应用中等质量预设', () => {
    giManager.applyPreset('medium');
    const settings = giManager.getSettings();

    expect(settings.enabled).toBe(true);
    expect(settings.quality).toBe('medium');
    expect(settings.hemisphereLight.enabled).toBe(true);
    expect(settings.ssao.enabled).toBe(true);
    expect(settings.ssao.radius).toBeCloseTo(GI_QUALITY_PRESETS.medium.ssaoRadius);
    expect(settings.ssao.intensity).toBeCloseTo(GI_QUALITY_PRESETS.medium.ssaoIntensity);
  });

  it('关闭预设时应该移除半球光', () => {
    giManager.applyPreset('off');
    const settings = giManager.getSettings();
    const hemisphereLight = scene.children.find(
      (c) => c instanceof THREE.HemisphereLight
    );

    expect(settings.enabled).toBe(false);
    expect(settings.quality).toBe('off');
    expect(hemisphereLight).toBeUndefined();
  });

  it('SSAO配置应该正确反映当前设置', () => {
    giManager.applyPreset('high');
    const ssaoConfig = giManager.getSSAOConfig();

    expect(ssaoConfig.enabled).toBe(true);
    expect(ssaoConfig.radius).toBeCloseTo(0.8, 1);
    expect(ssaoConfig.intensity).toBeCloseTo(0.7, 1);
  });

  it('应该正确调整曝光值', () => {
    giManager.setExposure(1.5);
    expect(giManager.getExposure()).toBeCloseTo(1.5, 2);

    giManager.setExposure(5);
    expect(giManager.getExposure()).toBeCloseTo(3, 2);

    giManager.setExposure(-1);
    expect(giManager.getExposure()).toBeCloseTo(0.1, 2);
  });

  it('应该正确调整半球光强度', () => {
    giManager.setHemisphereIntensity(1.2);
    const hemisphereLight = scene.children.find(
      (c) => c instanceof THREE.HemisphereLight
    ) as THREE.HemisphereLight;

    expect(hemisphereLight.intensity).toBeCloseTo(1.2, 2);
  });

  it('toggle方法应该返回新状态并正确切换', () => {
    expect(giManager.toggle()).toBe(false);
    expect(giManager.toggle()).toBe(true);
  });

  it('应该正确调整SSAO强度和半径', () => {
    giManager.setSSAOIntensity(1.2);
    giManager.setSSAORadius(0.8);

    const ssaoConfig = giManager.getSSAOConfig();
    expect(ssaoConfig.intensity).toBeCloseTo(1.2, 2);
    expect(ssaoConfig.radius).toBeCloseTo(0.8, 2);
  });
});

describe('全局光照管理器 - 配置边界测试', () => {
  let scene: THREE.Scene;
  let giManager: GlobalIlluminationManager;

  beforeEach(() => {
    scene = new THREE.Scene();
    giManager = new GlobalIlluminationManager(scene);
  });

  it('SSAO强度应该在0-2范围内', () => {
    giManager.setSSAOIntensity(5);
    expect(giManager.getSSAOConfig().intensity).toBeLessThanOrEqual(2);

    giManager.setSSAOIntensity(-5);
    expect(giManager.getSSAOConfig().intensity).toBeGreaterThanOrEqual(0);
  });

  it('SSAO半径应该在合理范围内', () => {
    giManager.setSSAORadius(100);
    expect(giManager.getSSAOConfig().radius).toBeLessThanOrEqual(5);

    giManager.setSSAORadius(-1);
    expect(giManager.getSSAOConfig().radius).toBeGreaterThanOrEqual(0.01);
  });

  it('默认GI设置应该有合理值', () => {
    const defaults = createDefaultGISettings();
    expect(defaults.enabled).toBe(true);
    expect(defaults.quality).toBe('medium');
    expect(defaults.hemisphereLight.intensity).toBeGreaterThan(0);
    expect(defaults.ssao.intensity).toBeGreaterThan(0);
    expect(defaults.exposure).toBeCloseTo(1.0, 1);
  });

  it('dispose后应该清理资源', () => {
    giManager.dispose();
    const hemisphereLight = scene.children.find(
      (c) => c instanceof THREE.HemisphereLight
    );
    expect(hemisphereLight).toBeUndefined();
  });
});
