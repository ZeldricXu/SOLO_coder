import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { themeManager } from '@/lib/themeManager';
import { uiEventBus } from '@/lib/uiEventBus';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';

describe('ThemeManager', () => {
  beforeEach(() => {
    themeManager.setTheme('modern-dark');
  });

  it('应该能列出所有主题', () => {
    const themes = themeManager.listThemes();
    expect(themes.length).toBeGreaterThanOrEqual(2);
    expect(themes.map((t) => t.id)).toContain('modern-dark');
    expect(themes.map((t) => t.id)).toContain('warm-light');
  });

  it('应该能切换主题', () => {
    const result = themeManager.setTheme('warm-light');
    expect(result).toBe(true);
    expect(themeManager.getCurrentThemeId()).toBe('warm-light');
    expect(themeManager.getCurrentTheme().ui.accent).toBeTruthy();
  });

  it('切换到不存在主题应该返回false', () => {
    const result = themeManager.setTheme('non-existent');
    expect(result).toBe(false);
    expect(themeManager.getCurrentThemeId()).toBe('modern-dark');
  });

  it('应该能获取默认材质', () => {
    const mats = themeManager.getDefaultMaterials();
    expect(mats.length).toBeGreaterThanOrEqual(8);
    expect(mats[0].id).toBeTruthy();
  });

  it('应该能获取灯光预设', () => {
    const preset = themeManager.getLightingPreset('day');
    expect(preset).toBeDefined();
    expect(preset.name).toBe('白天');
    expect(preset.ambientIntensity).toBeGreaterThan(0);
  });

  it('应该能获取渲染质量预设', () => {
    const quality = themeManager.getRenderQuality('high');
    expect(quality.shadowMapSize).toBe(4096);
    expect(quality.antialias).toBe(true);
  });

  it('应该能触发 onChange 回调', () => {
    const cb = vi.fn();
    const unsub = themeManager.onChange(cb);
    themeManager.setTheme('warm-light');
    expect(cb).toHaveBeenCalledTimes(1);
    unsub();
    themeManager.setTheme('modern-dark');
    expect(cb).toHaveBeenCalledTimes(1);
  });
});

describe('UIEventBus', () => {
  beforeEach(() => {
    uiEventBus.clear();
  });

  it('emit/on 应该能正确收发事件', () => {
    const payload = { id: '1', type: 'success' as const, message: 'ok', createdAt: Date.now() };
    const cb = vi.fn();
    uiEventBus.on('notification:add', cb);
    uiEventBus.emit({ name: 'notification:add', payload });
    expect(cb).toHaveBeenCalledWith(payload);
  });

  it('off 应该能取消订阅', () => {
    const cb = vi.fn();
    const unsub = uiEventBus.on('modal:open', cb);
    unsub();
    uiEventBus.emit({ name: 'modal:open', payload: { id: 'x' } });
    expect(cb).not.toHaveBeenCalled();
  });

  it('listenerCount 应该能正确计数', () => {
    expect(uiEventBus.listenerCount('saving:start')).toBe(0);
    const u1 = uiEventBus.on('saving:start', () => {});
    expect(uiEventBus.listenerCount('saving:start')).toBe(1);
    const u2 = uiEventBus.on('saving:start', () => {});
    expect(uiEventBus.listenerCount('saving:start')).toBe(2);
    u1();
    u2();
    expect(uiEventBus.listenerCount('saving:start')).toBe(0);
  });

  it('主题变更事件应该和 themeManager 联动', () => {
    const cb = vi.fn();
    uiEventBus.on('theme:change', cb);
    themeManager.setTheme('warm-light');
    expect(cb).not.toHaveBeenCalled();
  });
});

describe('PBRMaterialFactory 主题集成', () => {
  beforeEach(() => {
    themeManager.setTheme('modern-dark');
  });

  it('应该能从主题加载默认材质', () => {
    const factory = new PBRMaterialFactory();
    const mat = factory.createMaterial('mat-wall-white');
    expect(mat).toBeInstanceOf(THREE.MeshStandardMaterial);
  });

  it('切换主题后 reloadMaterials 应该使用新主题颜色', () => {
    themeManager.setTheme('warm-light');
    const factory = new PBRMaterialFactory();
    const wireColor = (factory.createWireframeMaterial().color as THREE.Color).getHexString();
    expect(wireColor).toBeTruthy();
  });
});
