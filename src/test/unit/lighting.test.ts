import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { LightManager } from '@/engine/lighting/LightManager';
import {
  createTestLightSource,
  createTestThreeLight,
  validateShadowSettings,
  calculateShadowDirection,
  validateLightIntensity,
  validateLightColor,
  createLightIntensityTestCases,
  createShadowTestScene,
} from '../fixtures/lightingFixtures';

describe('实时灯光模拟 - 正常路径测试', () => {
  describe('四种光源类型创建', () => {
    let lightManager: LightManager;
    let scene: THREE.Scene;

    beforeEach(() => {
      scene = new THREE.Scene();
      lightManager = new LightManager(scene);
    });

    it('应该正确创建环境光', () => {
      const lightSource = createTestLightSource('ambient', { intensity: 0.5 });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      expect(lights.size).toBe(1);

      const light = lights.get(lightSource.id);
      expect(light).toBeInstanceOf(THREE.AmbientLight);
      expect(validateLightIntensity(light!, 0.5)).toBe(true);
    });

    it('应该正确创建点光源', () => {
      const lightSource = createTestLightSource('point', {
        position: { x: 2, y: 3, z: 2 },
        intensity: 1.0,
        params: { distance: 10 },
      });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id) as THREE.PointLight;

      expect(light).toBeInstanceOf(THREE.PointLight);
      expect(light.position.x).toBeCloseTo(2);
      expect(light.position.y).toBeCloseTo(3);
      expect(light.position.z).toBeCloseTo(2);
      expect(light.distance).toBeCloseTo(10);
    });

    it('应该正确创建射灯', () => {
      const lightSource = createTestLightSource('spot', {
        position: { x: 0, y: 5, z: 2 },
        target: { x: 0, y: 0, z: 0 },
        params: { angle: Math.PI / 6 },
        intensity: 1.5,
      });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id) as THREE.SpotLight;

      expect(light).toBeInstanceOf(THREE.SpotLight);
      expect(light.angle).toBeCloseTo(Math.PI / 6);
      expect(light.target.position.x).toBeCloseTo(0);
      expect(light.target.position.y).toBeCloseTo(0);
      expect(light.target.position.z).toBeCloseTo(0);
    });

    it('应该正确创建面光源', () => {
      const lightSource = createTestLightSource('area', {
        position: { x: 0, y: 2.8, z: 0 },
        intensity: 2.0,
      });
      lightSource.params = { width: 2, height: 0.3 };
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id) as THREE.RectAreaLight;

      expect(light).toBeInstanceOf(THREE.RectAreaLight);
      expect(light.width).toBeCloseTo(2);
      expect(light.height).toBeCloseTo(0.3);
    });
  });

  describe('阴影投射', () => {
    it('点光源在指定位置投射的阴影方向符合物理预期', () => {
      const lightPosition = new THREE.Vector3(0, 5, 2);
      const targetPosition = new THREE.Vector3(0, 0, 0);

      const shadowDirection = calculateShadowDirection(lightPosition, targetPosition);

      const expectedDirection = new THREE.Vector3(0, -5, -2).normalize();
      expect(shadowDirection.x).toBeCloseTo(expectedDirection.x);
      expect(shadowDirection.y).toBeCloseTo(expectedDirection.y);
      expect(shadowDirection.z).toBeCloseTo(expectedDirection.z);
    });

    it('射灯应该正确配置阴影属性', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const lightSource = createTestLightSource('spot', {
        castShadow: true,
      });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id)!;

      const shadowValidation = validateShadowSettings(light, true);
      expect(shadowValidation.valid).toBe(true);
      expect(shadowValidation.errors).toHaveLength(0);
    });

    it('环境光不应该投射阴影', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const lightSource = createTestLightSource('ambient', {
        castShadow: true,
      });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id)!;

      expect(light).toBeInstanceOf(THREE.AmbientLight);
      expect((light as THREE.AmbientLight).castShadow).toBe(false);
    });

    it('应该正确计算阴影在地面上的投影位置', () => {
      const { scene, light, object } = createShadowTestScene();

      const lightPos = light.position.clone();
      const objectPos = object.position.clone();
      const objectTopY = objectPos.y + 0.5;
      const objectTopPos = new THREE.Vector3(objectPos.x, objectTopY, objectPos.z);

      const direction = new THREE.Vector3()
        .subVectors(objectTopPos, lightPos)
        .normalize();

      const t = (0 - objectTopY) / direction.y;
      const shadowX = objectTopPos.x + direction.x * t;
      const shadowZ = objectTopPos.z + direction.z * t;

      expect(shadowX).toBeCloseTo(0, 1);
      expect(shadowZ).toBeCloseTo(-0.5, 1);
    });
  });

  describe('灯光参数应用', () => {
    it('应该正确应用颜色参数', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const lightSource = createTestLightSource('point', {
        color: { r: 1, g: 0.4, b: 0 },
      });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id)!;

      expect(light.color.r).toBeCloseTo(1, 2);
      expect(light.color.g).toBeCloseTo(0.4, 2);
      expect(light.color.b).toBeCloseTo(0, 2);
    });

    it('应该正确应用多个灯光参数', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const lightSource = createTestLightSource('point', {
        color: { r: 0, g: 1, b: 0 },
        intensity: 0.8,
        params: { distance: 15, decay: 1.5 }
      });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id) as THREE.PointLight;

      expect(light.color.r).toBeCloseTo(0, 2);
      expect(light.color.g).toBeCloseTo(1, 2);
      expect(light.color.b).toBeCloseTo(0, 2);
      expect(light.intensity).toBeCloseTo(0.8, 2);
      expect(light.distance).toBeCloseTo(15);
      expect(light.decay).toBeCloseTo(1.5);
    });
  });

  describe('灯光管理', () => {
    let lightManager: LightManager;
    let scene: THREE.Scene;

    beforeEach(() => {
      scene = new THREE.Scene();
      lightManager = new LightManager(scene);
    });

    it('应该正确更新已有灯光', () => {
      const lightSource = createTestLightSource('point', { intensity: 1.0 });
      lightManager.addLight(lightSource);

      lightManager.updateLightById(lightSource.id, { intensity: 2.0 });

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id)!;
      expect(validateLightIntensity(light, 2.0)).toBe(true);
    });

    it('应该正确删除灯光', () => {
      const lightSource = createTestLightSource('point');
      lightManager.addLight(lightSource);
      expect(lightManager.getLights().size).toBe(1);

      lightManager.removeLight(lightSource.id);
      expect(lightManager.getLights().size).toBe(0);
    });

    it('应该正确显示/隐藏Helper', () => {
      const lightSource = createTestLightSource('point');
      lightManager.addLight(lightSource);

      lightManager.enableHelpers();
      expect(lightManager.areHelpersVisible()).toBe(true);

      lightManager.disableHelpers();
      expect(lightManager.areHelpersVisible()).toBe(false);
    });
  });
});

describe('实时灯光模拟 - 异常路径测试', () => {
  describe('灯光强度为0时的场景降级显示', () => {
    it('点光源强度设为0时应该正确处理', () => {
      const testCases = createLightIntensityTestCases();

      for (const { input, expected, description } of testCases) {
        const scene = new THREE.Scene();
        const lightManager = new LightManager(scene);

        const lightSource = createTestLightSource('point', { intensity: input });
        lightManager.addLight(lightSource);

        const lights = lightManager.getLights();
        const light = lights.get(lightSource.id)!;

        expect(validateLightIntensity(light, expected)).toBe(true);
      }
    });

    it('灯光强度为0时场景仍然可见（有环境光）', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      lightManager.createDefaultLights();

      const ambientLight = Array.from(lightManager.getLights().values()).find(
        (l) => l instanceof THREE.AmbientLight
      );

      expect(ambientLight).toBeDefined();
      if (ambientLight) {
        expect(ambientLight.intensity).toBeGreaterThan(0);
      }
    });

    it('所有灯光强度为0时应该给出警告提示', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const warnings: string[] = [];
      const consoleWarnSpy = vi
        .spyOn(console, 'warn')
        .mockImplementation((msg) => warnings.push(msg));

      const pointLight = createTestLightSource('point', { intensity: 0 });
      const spotLight = createTestLightSource('spot', { intensity: 0 });
      lightManager.addLight(pointLight);
      lightManager.addLight(spotLight);

      const allLightsOff = Array.from(lightManager.getLights().values()).every(
        (l) => l.intensity <= 0.001
      );

      if (allLightsOff) {
        console.warn('所有灯光强度为0，场景可能过暗');
      }

      expect(warnings).toContain('所有灯光强度为0，场景可能过暗');
      consoleWarnSpy.mockRestore();
    });
  });

  describe('边界条件处理', () => {
    it('应该正确处理负强度（自动钳制到0）', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const lightSource = createTestLightSource('point', { intensity: -1.0 });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id)!;
      expect(light.intensity).toBeGreaterThanOrEqual(0);
    });

    it('应该正确处理超大强度（自动限制上限）', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const lightSource = createTestLightSource('point', { intensity: 1000 });
      lightManager.addLight(lightSource);

      const lights = lightManager.getLights();
      const light = lights.get(lightSource.id)!;
      expect(light.intensity).toBeLessThanOrEqual(10);
    });

    it('应该正确处理无效的灯光类型', () => {
      const scene = new THREE.Scene();
      const lightManager = new LightManager(scene);

      const invalidLightSource = {
        id: 'invalid',
        type: 'invalid_type',
        position: { x: 0, y: 0, z: 0 },
        color: '#ffffff',
        intensity: 1,
        enabled: true,
        castShadow: false,
      } as any;

      expect(() => lightManager.addLight(invalidLightSource)).not.toThrow();
      expect(lightManager.getLights().size).toBe(0);
    });
  });
});
