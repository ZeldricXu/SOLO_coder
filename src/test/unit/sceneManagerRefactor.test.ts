import { describe, it, expect, beforeEach } from 'vitest';
import * as THREE from 'three';
import type { Wall, Opening, Room } from '@/types/floorplan';
import { SceneManager } from '@/engine/scene/SceneManager';
import { WallGeometryBuilder } from '@/engine/scene/WallGeometryBuilder';
import { OpeningCutter } from '@/engine/scene/OpeningCutter';
import { DoorGenerator } from '@/engine/scene/DoorGenerator';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';

const createStraightWall = (overrides: Partial<Wall> = {}): Wall => ({
  id: 'wall-1',
  type: 'straight',
  start: { x: 0, y: 0 },
  end: { x: 4, y: 0 },
  thickness: 0.2,
  height: 2.8,
  materialId: 'mat-wall-white',
  ...overrides,
});

const createOpening = (wallId: string, over: Partial<Opening> = {}): Opening => ({
  id: 'op-1',
  type: 'door',
  wallId,
  positionX: 1.0,
  width: 0.9,
  height: 2.1,
  sillHeight: 0,
  ...over,
});

describe('WallGeometryBuilder', () => {
  let builder: WallGeometryBuilder;

  beforeEach(() => {
    builder = new WallGeometryBuilder();
  });

  it('应该能生成直线墙体Mesh', () => {
    const mesh = builder.buildWall(createStraightWall());
    expect(mesh).toBeInstanceOf(THREE.Mesh);
    expect(mesh.geometry).toBeTruthy();
    mesh.geometry.computeBoundingBox();
    expect(mesh.geometry.boundingBox).toBeTruthy();
  });

  it('应该能生成弧形墙体Mesh', () => {
    const mesh = builder.buildWall({
      id: 'arc',
      type: 'arc',
      start: { x: 0, y: 0 },
      end: { x: 2, y: 2 },
      center: { x: 2, y: 0 },
      thickness: 0.2,
      height: 2.8,
      materialId: 'mat-wall-white',
    });
    expect(mesh).toBeInstanceOf(THREE.Mesh);
  });

  it('应该能生成地板', () => {
    const boundary = [
      { x: 0, y: 0 },
      { x: 4, y: 0 },
      { x: 4, y: 3 },
      { x: 0, y: 3 },
    ];
    const floor = builder.buildFloor(boundary);
    expect(floor.userData.type).toBe('floor');
    expect(floor.receiveShadow).toBe(true);
  });

  it('应该能生成天花板', () => {
    const boundary = [
      { x: 0, y: 0 },
      { x: 4, y: 0 },
      { x: 4, y: 3 },
      { x: 0, y: 3 },
    ];
    const ceiling = builder.buildCeiling(boundary, 2.8);
    expect(ceiling.userData.type).toBe('ceiling');
    expect(ceiling.position.y).toBe(2.8);
  });
});

describe('OpeningCutter', () => {
  let cutter: OpeningCutter;

  beforeEach(() => {
    cutter = new OpeningCutter();
  });

  it('应该能在有效范围内创建开洞切割体', () => {
    const wall = createStraightWall();
    const opening = createOpening(wall.id, { positionX: 2 });
    const mesh = cutter.createOpeningCutterMesh(wall, opening);
    expect(mesh).toBeTruthy();
  });

  it('在范围外应该返回null', () => {
    const wall = createStraightWall();
    const opening = createOpening(wall.id, { positionX: -1 });
    const mesh = cutter.createOpeningCutterMesh(wall, opening);
    expect(mesh).toBeNull();
  });
});

describe('DoorGenerator', () => {
  let generator: DoorGenerator;

  beforeEach(() => {
    const mat = new PBRMaterialFactory();
    generator = new DoorGenerator(mat);
  });

  it('应该能生成门扇Group', () => {
    const wall = createStraightWall();
    const door = createOpening(wall.id, { type: 'door', positionX: 1 });
    const group = generator.buildDoorPanel(door, wall);
    expect(group).toBeTruthy();
    expect(group?.name).toContain('door-');
    expect(group?.userData.type).toBe('door');
  });

  it('应该能生成窗户Group', () => {
    const wall = createStraightWall();
    const win = createOpening(wall.id, { type: 'window', positionX: 2, sillHeight: 0.9, height: 1.2 });
    const group = generator.buildWindowPanel(win, wall);
    expect(group).toBeTruthy();
    expect(group?.name).toContain('window-');
  });

  it('传入door给window方法应该返回null', () => {
    const wall = createStraightWall();
    const door = createOpening(wall.id, { type: 'door' });
    expect(generator.buildWindowPanel(door, wall)).toBeNull();
  });
});

describe('SceneManager - 整合测试', () => {
  let sceneManager: SceneManager;

  beforeEach(() => {
    sceneManager = new SceneManager();
  });

  it('应该能生成带开洞的墙体组', () => {
    const wall = createStraightWall();
    const opening = createOpening(wall.id, { positionX: 1.5 });
    const group = sceneManager.buildWall(wall, [opening], []);
    expect(group).toBeInstanceOf(THREE.Group);
    expect(group.userData.wallId).toBe(wall.id);
    expect(group.children.length).toBeGreaterThan(0);
  });

  it('应该能生成完整房间元素', () => {
    const room: Room = {
      id: 'room-1',
      name: '客厅',
      boundary: [
        { x: 0, y: 0 },
        { x: 4, y: 0 },
        { x: 4, y: 3 },
        { x: 0, y: 3 },
      ],
      floorMaterialId: 'mat-floor-wood',
      ceilingMaterialId: 'mat-wall-white',
      height: 2.8,
    };
    const { floor, ceiling } = sceneManager.buildRoomElements(room, [], 2.8);
    expect(floor).toBeDefined();
    expect(ceiling).toBeDefined();
  });

  it('应该能同时生成多个门窗', () => {
    const w1 = createStraightWall({ id: 'w1' });
    const w2 = createStraightWall({ id: 'w2', start: { x: 4, y: 0 }, end: { x: 4, y: 3 } });
    const d1 = createOpening('w1', { positionX: 1.5 });
    const w = createOpening('w2', { type: 'window', positionX: 1.5, sillHeight: 0.9, height: 1.2, id: 'win-1' });
    const items = sceneManager.buildAllDoorsAndWindows([d1, w], [w1, w2]);
    expect(items.length).toBe(2);
  });

  it('clearCache应该正常工作', () => {
    sceneManager.clearCache();
    expect(sceneManager.getMaterialFactory().getMaterialCache().size).toBe(0);
  });
});
