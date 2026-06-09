import { describe, it, expect, beforeEach } from 'vitest';
import type { PlaneAction, PlaneState } from '@/types/state';
import {
  planeReducer,
  floorPlanToPlaneState,
  planeStateToFloorPlan,
} from '@/store/planeReducer';
import { createDefaultFloorPlan } from '@/store/useFloorPlanStore';
import type { Wall, Opening, FurnitureItem, Annotation } from '@/types/floorplan';

const createInitialState = (): PlaneState =>
  floorPlanToPlaneState(createDefaultFloorPlan());

describe('PlaneReducer - 基础动作测试', () => {
  let state: PlaneState;

  beforeEach(() => {
    state = createInitialState();
  });

  it('WALL_ADD 应该能添加墙体并更新时间戳', () => {
    const beforeTs = state.lastUpdatedAt;
    const action: PlaneAction = {
      type: 'WALL_ADD',
      payload: {
        type: 'straight',
        start: { x: 0, y: 0 },
        end: { x: 4, y: 0 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      },
    };
    const next = planeReducer(state, action);
    expect(next.walls.length).toBe(1);
    expect(next.walls[0].id).toBeTruthy();
    expect(next.lastUpdatedAt).toBeGreaterThanOrEqual(beforeTs);
    expect(next.lastActionType).toBe('WALL_ADD');
  });

  it('WALL_UPDATE 应该能更新墙体字段', () => {
    let s = planeReducer(state, {
      type: 'WALL_ADD',
      payload: {
        type: 'straight',
        start: { x: 0, y: 0 },
        end: { x: 4, y: 0 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      },
    });
    const id = s.walls[0].id;
    s = planeReducer(s, {
      type: 'WALL_UPDATE',
      payload: { id, updates: { height: 3.2 } },
    });
    expect(s.walls[0].height).toBe(3.2);
  });

  it('WALL_REMOVE 应该删除墙体及关联门洞', () => {
    let s = planeReducer(state, {
      type: 'WALL_ADD',
      payload: {
        type: 'straight',
        start: { x: 0, y: 0 },
        end: { x: 4, y: 0 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      },
    });
    const wallId = s.walls[0].id;
    s = planeReducer(s, {
      type: 'OPENING_ADD',
      payload: {
        type: 'door',
        wallId,
        positionX: 1.5,
        width: 0.9,
        height: 2.1,
      },
    });
    expect(s.openings.length).toBe(1);
    s = planeReducer(s, { type: 'WALL_REMOVE', payload: wallId });
    expect(s.walls.length).toBe(0);
    expect(s.openings.length).toBe(0);
  });

  it('应该不会修改原 state（immutable）', () => {
    const origState = state;
    const next = planeReducer(state, {
      type: 'WALL_ADD',
      payload: {
        type: 'straight',
        start: { x: 0, y: 0 },
        end: { x: 4, y: 0 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      },
    });
    expect(origState).not.toBe(next);
    expect(origState.walls.length).toBe(0);
  });
});

describe('PlaneReducer - 其他实体操作', () => {
  let state: PlaneState;

  beforeEach(() => {
    state = createInitialState();
  });

  it('FURNITURE_ADD / REMOVE 正确', () => {
    const furniture: Omit<FurnitureItem, 'id'> = {
      modelId: 'sofa',
      name: '沙发',
      category: 'living',
      position: { x: 1, y: 0, z: 1 },
      rotation: 0,
      scale: 1,
    };
    let s = planeReducer(state, { type: 'FURNITURE_ADD', payload: furniture });
    expect(s.furniture.length).toBe(1);
    const id = s.furniture[0].id;
    s = planeReducer(s, { type: 'FURNITURE_REMOVE', payload: id });
    expect(s.furniture.length).toBe(0);
  });

  it('ANNOTATION_ADD 自动填充 status 和 createdAt', () => {
    const annotation: Omit<Annotation, 'id'> = {
      position: { x: 0, y: 0, z: 0 },
      author: 'tester',
      content: 'hello',
      createdAt: 0,
      status: 'open',
    };
    const s = planeReducer(state, { type: 'ANNOTATION_ADD', payload: annotation });
    expect(s.annotations.length).toBe(1);
    expect(s.annotations[0].status).toBe('open');
    expect(s.annotations[0].id).toBeTruthy();
  });

  it('SETTINGS_UPDATE 更新项目设置', () => {
    const s = planeReducer(state, {
      type: 'SETTINGS_UPDATE',
      payload: { wallHeight: 3.0, gridSize: 0.05 },
    });
    expect(s.project.settings.wallHeight).toBe(3.0);
    expect(s.project.settings.gridSize).toBe(0.05);
  });

  it('PLAN_RESET 清除所有数据', () => {
    let s = planeReducer(state, {
      type: 'WALL_ADD',
      payload: {
        type: 'straight',
        start: { x: 0, y: 0 },
        end: { x: 4, y: 0 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      },
    });
    s = planeReducer(s, { type: 'PLAN_RESET' });
    expect(s.walls.length).toBe(0);
    expect(s.rooms.length).toBe(0);
  });
});

describe('PlaneState 转换', () => {
  it('floorPlanToPlaneState 再转回 FloorPlan 应该保持一致', () => {
    const plan = createDefaultFloorPlan();
    const state = floorPlanToPlaneState(plan);
    const restored = planeStateToFloorPlan(state, plan.materials);
    expect(restored.walls.length).toBe(plan.walls.length);
    expect(restored.project.id).toBe(plan.project.id);
    expect(restored.materials.length).toBe(plan.materials.length);
  });

  it('PLAN_REPLACE 完全替换', () => {
    const plan = createDefaultFloorPlan();
    plan.project.name = '替换项目';
    const s = planeReducer(createInitialState(), { type: 'PLAN_REPLACE', payload: plan });
    expect(s.project.name).toBe('替换项目');
  });
});
