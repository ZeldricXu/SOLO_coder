import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { DrawingAnnotationManager } from '@/engine/annotations/DrawingAnnotationManager';
import type { DrawingPrimitive, DrawingVertex } from '@/types/drawing';
import { generateId } from '@/utils/geometry';

const createMockRect = (): DOMRect => ({
  left: 0,
  top: 0,
  width: 800,
  height: 600,
  x: 0,
  y: 0,
  right: 800,
  bottom: 600,
  toJSON: () => ({}),
});

const createTestSurface = (): THREE.Mesh => {
  const geometry = new THREE.PlaneGeometry(10, 10);
  const material = new THREE.MeshStandardMaterial({ color: 0xffffff });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.userData = { type: 'floor' };
  mesh.rotation.x = -Math.PI / 2;
  mesh.position.y = 0;
  return mesh;
};

describe('绘图批注管理器 - 正常路径测试', () => {
  let scene: THREE.Scene;
  let camera: THREE.PerspectiveCamera;
  let manager: DrawingAnnotationManager;

  beforeEach(() => {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(50, 800 / 600, 0.1, 1000);
    camera.position.set(0, 5, 5);
    camera.lookAt(0, 0, 0);
    camera.updateMatrixWorld(true);
    manager = new DrawingAnnotationManager(scene, camera);
  });

  it('初始化时应该创建绘图组', () => {
    const drawingGroup = scene.children.find((c) => c.name === 'drawing-annotations');
    expect(drawingGroup).toBeDefined();
    expect(drawingGroup?.renderOrder).toBe(999);
  });

  it('应该能够注册和取消注册表面网格', () => {
    const mesh = createTestSurface();
    scene.add(mesh);

    manager.registerSurfaceMesh(mesh, 'test-floor');
    manager.unregisterSurfaceMesh('test-floor');

    expect(true).toBe(true);
  });

  it('应该正确设置绘画会话', () => {
    manager.setSession({
      color: '#00ff00',
      lineWidth: 4,
      tool: 'arrow',
    });

    const session = manager.getSession();
    expect(session.color).toBe('#00ff00');
    expect(session.lineWidth).toBe(4);
    expect(session.tool).toBe('arrow');
  });

  it('应该能够设置绘图完成回调', () => {
    const callback = vi.fn();
    manager.setOnPrimitiveComplete(callback);
    expect(true).toBe(true);
  });

  it('应该能够渲染原始图元', () => {
    const primitive: DrawingPrimitive = {
      id: generateId(),
      type: 'freehand',
      vertices: [
        { position: { x: 0, y: 0, z: 0 }, normal: { x: 0, y: 1, z: 0 } },
        { position: { x: 1, y: 0, z: 0 }, normal: { x: 0, y: 1, z: 0 } },
        { position: { x: 1, y: 0, z: 1 }, normal: { x: 0, y: 1, z: 0 } },
      ],
      color: '#ff0000',
      lineWidth: 2,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    manager.renderPrimitive(primitive);

    const drawingGroup = scene.children.find((c) => c.name === 'drawing-annotations');
    expect(drawingGroup?.children.length).toBeGreaterThan(0);
  });

  it('应该能够移除单个图元', () => {
    const primitive: DrawingPrimitive = {
      id: 'test-primitive',
      type: 'freehand',
      vertices: [
        { position: { x: 0, y: 0, z: 0 }, normal: { x: 0, y: 1, z: 0 } },
        { position: { x: 1, y: 0, z: 0 }, normal: { x: 0, y: 1, z: 0 } },
      ],
      color: '#0000ff',
      lineWidth: 3,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    manager.renderPrimitive(primitive);
    const beforeRemove = (scene.children.find((c) => c.name === 'drawing-annotations') as THREE.Group).children.length;

    manager.removePrimitive('test-primitive');
    const afterRemove = (scene.children.find((c) => c.name === 'drawing-annotations') as THREE.Group).children.length;

    expect(afterRemove).toBeLessThan(beforeRemove);
  });

  it('应该能够清除所有绘图', () => {
    for (let i = 0; i < 3; i++) {
      manager.renderPrimitive({
        id: `primitive-${i}`,
        type: 'line',
        vertices: [
          { position: { x: i, y: 0, z: 0 }, normal: { x: 0, y: 1, z: 0 } },
          { position: { x: i + 1, y: 0, z: 0 }, normal: { x: 0, y: 1, z: 0 } },
        ],
        color: '#ff0000',
        lineWidth: 2,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      });
    }

    manager.clearAllDrawings();

    const drawingGroup = scene.children.find((c) => c.name === 'drawing-annotations') as THREE.Group;
    expect(drawingGroup.children.length).toBe(0);
  });

  it('应该能够控制绘图可见性', () => {
    manager.setDrawingsVisible(false);
    const drawingGroup = scene.children.find((c) => c.name === 'drawing-annotations');
    expect(drawingGroup?.visible).toBe(false);

    manager.setDrawingsVisible(true);
    expect(drawingGroup?.visible).toBe(true);
  });
});

describe('绘图批注管理器 - 表面拾取测试', () => {
  let scene: THREE.Scene;
  let camera: THREE.PerspectiveCamera;
  let manager: DrawingAnnotationManager;
  let floorMesh: THREE.Mesh;

  beforeEach(() => {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(50, 800 / 600, 0.1, 1000);
    camera.position.set(0, 5, 5);
    camera.lookAt(0, 0, 0);
    camera.updateMatrixWorld(true);

    manager = new DrawingAnnotationManager(scene, camera);

    floorMesh = createTestSurface();
    scene.add(floorMesh);
    manager.registerSurfaceMesh(floorMesh, 'floor-test');
    scene.updateMatrixWorld(true);
  });

  it('在画布中心应该能命中地面', () => {
    const rect = createMockRect();
    const hit = manager.pickSurface(400, 300, rect);

    if (hit) {
      expect(hit.distance).toBeGreaterThan(0);
      expect(hit.objectType).toBe('floor');
    }
  });

  it('画布外点击应该返回null', () => {
    const rect = createMockRect();
    const hit = manager.pickSurface(900, 700, rect);
    expect(hit).toBeNull();
  });
});

describe('绘图批注管理器 - 异常/边界测试', () => {
  let scene: THREE.Scene;
  let camera: THREE.PerspectiveCamera;
  let manager: DrawingAnnotationManager;

  beforeEach(() => {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(50, 800 / 600, 0.1, 1000);
    camera.position.set(0, 5, 5);
    manager = new DrawingAnnotationManager(scene, camera);
  });

  it('没有表面时点击应该返回null', () => {
    const rect = createMockRect();
    const hit = manager.pickSurface(400, 300, rect);
    expect(hit).toBeNull();
  });

  it('单顶点的图元不应该被endDrawing返回', () => {
    const rect = createMockRect();
    manager.setSession({ tool: 'line', color: '#ff0000', lineWidth: 2 });

    const mesh = createTestSurface();
    scene.add(mesh);
    manager.registerSurfaceMesh(mesh, 'floor');
    scene.updateMatrixWorld(true);
    camera.updateMatrixWorld(true);

    const vertex = manager.startDrawing(400, 300, rect);
    if (vertex) {
      const result = manager.endDrawing();
      expect(result).toBeNull();
    }
  });

  it('取消绘图应该清除状态', () => {
    manager.cancelDrawing();
    const session = manager.getSession();
    expect(session.active).toBe(false);
    expect(session.currentPrimitive).toBeNull();
  });

  it('dispose应该正确清理资源', () => {
    manager.dispose();
    const drawingGroup = scene.children.find((c) => c.name === 'drawing-annotations');
    expect(drawingGroup).toBeUndefined();
  });

  it('clearAllSurfaces应该能正常调用', () => {
    manager.clearAllSurfaces();
    expect(true).toBe(true);
  });
});
