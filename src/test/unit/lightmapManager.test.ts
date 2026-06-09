import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { LightmapManager } from '@/engine/lighting/LightmapManager';

const createTestScene = (): THREE.Scene => {
  const scene = new THREE.Scene();

  const wallMesh = new THREE.Mesh(
    new THREE.BoxGeometry(4, 2.8, 0.2),
    new THREE.MeshStandardMaterial()
  );
  wallMesh.userData = { type: 'wall', wallId: 'w1' };
  scene.add(wallMesh);

  const floorMesh = new THREE.Mesh(
    new THREE.PlaneGeometry(10, 10),
    new THREE.MeshStandardMaterial()
  );
  floorMesh.userData = { type: 'floor' };
  floorMesh.rotation.x = -Math.PI / 2;
  scene.add(floorMesh);

  const dynMesh = new THREE.Mesh(
    new THREE.BoxGeometry(1, 0.8, 1),
    new THREE.MeshStandardMaterial()
  );
  dynMesh.userData = { type: 'furniture', furnitureId: 'f1', fixed: false };
  dynMesh.position.set(2, 0.4, 2);
  scene.add(dynMesh);

  const fixedMesh = new THREE.Mesh(
    new THREE.BoxGeometry(0.8, 0.8, 0.8),
    new THREE.MeshStandardMaterial()
  );
  fixedMesh.userData = { type: 'furniture', furnitureId: 'f2', fixed: true };
  scene.add(fixedMesh);

  const directional = new THREE.DirectionalLight(0xffffff, 1);
  directional.position.set(5, 8, 5);
  directional.castShadow = true;
  directional.userData = { lightId: 'main' };
  scene.add(directional);

  return scene;
};

describe('LightmapManager - 基本功能', () => {
  let scene: THREE.Scene;
  let manager: LightmapManager;

  beforeEach(() => {
    scene = createTestScene();
    manager = new LightmapManager(scene);
  });

  it('应该正确识别静态/动态物体类型', () => {
    const meshes: THREE.Mesh[] = [];
    scene.traverse((o) => o instanceof THREE.Mesh && meshes.push(o));

    const kinds = meshes.map((m) => manager.getObjectKind(m));
    expect(kinds).toContain('static');
    expect(kinds).toContain('dynamic');
  });

  it('墙体/地板/天花板/固定家具应该是static类型', () => {
    const staticMeshes = manager.collectStaticMeshes();
    expect(staticMeshes.length).toBeGreaterThanOrEqual(3);
    const types = staticMeshes.map((m) => m.userData.type);
    expect(types).toContain('wall');
    expect(types).toContain('floor');
    expect(types).toContain('furniture');
    expect(types.every((t: string) => t !== 'drawing-annotation')).toBe(true);
  });

  it('动态物体应该只有非固定家具', () => {
    const dynMeshes = manager.collectDynamicMeshes();
    expect(dynMeshes.length).toBe(1);
    expect(dynMeshes[0].userData.furnitureId).toBe('f1');
  });

  it('configureShadows应该设置正确的阴影开关', () => {
    manager.configureShadows();
    const staticMeshes = manager.collectStaticMeshes();
    staticMeshes.forEach((m) => {
      expect(m.castShadow).toBe(true);
      expect(m.receiveShadow).toBe(true);
    });
  });
});

describe('LightmapManager - 渲染器依赖', () => {
  let scene: THREE.Scene;
  let manager: LightmapManager;

  beforeEach(() => {
    scene = createTestScene();
    manager = new LightmapManager(scene);
  });

  it('setRenderer应该能设置渲染器', () => {
    const mockRenderer = {} as THREE.WebGLRenderer;
    manager.setRenderer(mockRenderer);
    expect(manager.getIsBaking()).toBe(false);
  });

  it('没有渲染器时bakeLightmaps应该抛出错误', async () => {
    const mgr = new LightmapManager(new THREE.Scene());
    await expect(mgr.bakeLightmaps()).rejects.toThrow(/Renderer is required/);
  });

  it('invalidate应该能清理单mesh缓存', () => {
    const mesh = manager.collectStaticMeshes()[0];
    manager.invalidate(mesh);
    expect(manager.getCacheSize()).toBe(0);
  });

  it('dispose应该能完全清理资源', () => {
    manager.dispose();
    expect(manager.getCacheSize()).toBe(0);
  });
});
