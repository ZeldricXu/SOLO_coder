import { describe, it, expect, beforeEach } from 'vitest';
import type { PlaneAction } from '@/types/state';
import { ServerOperationValidator } from '@/utils/concurrency/ServerOperationValidator';
import { createDefaultFloorPlan } from '@/store/useFloorPlanStore';
import { generateId } from '@/utils/geometry';
import type { Wall, FurnitureItem } from '@/types/floorplan';

const createInitialPlan = () => {
  const plan = createDefaultFloorPlan();
  const wall: Wall = {
    id: 'w-1',
    type: 'straight',
    start: { x: 0, y: 0 },
    end: { x: 4, y: 0 },
    thickness: 0.2,
    height: 2.8,
    materialId: 'mat-wall-white',
  };
  plan.walls.push(wall);

  const furniture: FurnitureItem = {
    id: 'f-1',
    modelId: 'sofa',
    name: '沙发',
    category: 'living',
    position: { x: 1, y: 0, z: 1 },
    rotation: 0,
    scale: 1,
    materialOverrides: {},
  };
  plan.furniture.push(furniture);

  plan.openings.push({
    id: 'o-1',
    type: 'door',
    wallId: 'w-1',
    positionX: 1.5,
    width: 0.9,
    height: 2.1,
    sillHeight: 0,
  });

  return plan;
};

describe('ServerOperationValidator - 基本校验', () => {
  let validator: ServerOperationValidator;

  beforeEach(() => {
    validator = new ServerOperationValidator(createInitialPlan());
  });

  it('应该允许添加新墙体', () => {
    const action: PlaneAction = {
      type: 'WALL_ADD',
      payload: {
        type: 'straight',
        start: { x: 4, y: 0 },
        end: { x: 4, y: 3 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      },
    };
    const result = validator.validate(action);
    expect(result.valid).toBe(true);
  });

  it('删除存在的墙体应该有效', () => {
    const action: PlaneAction = { type: 'WALL_REMOVE', payload: 'w-1' };
    const result = validator.validate(action);
    expect(result.valid).toBe(true);
  });

  it('删除不存在的家具应该返回 invalid-target', () => {
    const action: PlaneAction = { type: 'FURNITURE_REMOVE', payload: 'f-non-existent' };
    const result = validator.validate(action);
    expect(result.valid).toBe(false);
    expect(result.status).toBe('invalid-target');
    expect(result.message).toContain('不存在');
  });

  it('更新不存在的墙体应该无效', () => {
    const action: PlaneAction = {
      type: 'WALL_UPDATE',
      payload: { id: 'not-exist', updates: { height: 3.0 } },
    };
    const result = validator.validate(action);
    expect(result.valid).toBe(false);
    expect(result.status).toBe('invalid-target');
  });
});

describe('ServerOperationValidator - 关联实体校验', () => {
  let validator: ServerOperationValidator;

  beforeEach(() => {
    validator = new ServerOperationValidator(createInitialPlan());
  });

  it('更新opening的wallId到不存在的墙应该无效', () => {
    const action: PlaneAction = {
      type: 'OPENING_UPDATE',
      payload: { id: 'o-1', updates: { wallId: 'not-exist' } },
    };
    const result = validator.validate(action);
    expect(result.valid).toBe(false);
    expect(result.status).toBe('invalid-target');
  });

  it('更新furniture位置缺少z应该无效', () => {
    const action: PlaneAction = {
      type: 'FURNITURE_UPDATE',
      payload: { id: 'f-1', updates: { position: { x: 1, y: 0 } as any } },
    };
    const result = validator.validate(action);
    expect(result.valid).toBe(false);
    expect(result.status).toBe('invalid-data');
  });

  it('更新furniture rotation为非数字应该无效', () => {
    const action: PlaneAction = {
      type: 'FURNITURE_UPDATE',
      payload: { id: 'f-1', updates: { rotation: 'bad' as any } },
    };
    const result = validator.validate(action);
    expect(result.valid).toBe(false);
    expect(result.status).toBe('invalid-data');
  });
});

describe('ServerOperationValidator - 应用操作和版本控制', () => {
  let validator: ServerOperationValidator;

  beforeEach(() => {
    validator = new ServerOperationValidator(createInitialPlan());
  });

  it('applyValid操作应该递增版本号', () => {
    const startVersion = validator.getVersion();
    const action: PlaneAction = {
      type: 'FURNITURE_UPDATE',
      payload: { id: 'f-1', updates: { position: { x: 2, y: 0, z: 2 } } },
    };
    const result = validator.validateAndApply(action, 'user-1');
    expect(result.valid).toBe(true);
    expect(validator.getVersion()).toBe(startVersion + 1);
  });

  it('非法操作不应该改变状态或版本', () => {
    const startVersion = validator.getVersion();
    const startState = validator.getState();

    const action: PlaneAction = { type: 'FURNITURE_REMOVE', payload: 'not-exist' };
    const result = validator.validateAndApply(action, 'user-1');

    expect(result.valid).toBe(false);
    expect(validator.getVersion()).toBe(startVersion);
    expect(validator.getState().furniture.length).toBe(startState.furniture.length);
  });

  it('删除墙体应该同步删除关联的opening', () => {
    expect(validator.getState().openings.length).toBe(1);
    const action: PlaneAction = { type: 'WALL_REMOVE', payload: 'w-1' };
    const result = validator.validateAndApply(action, 'user-1');
    expect(result.valid).toBe(true);
    expect(validator.getState().openings.length).toBe(0);
  });

  it('validateBatch应该正确统计appliedCount', () => {
    const actions: PlaneAction[] = [
      {
        type: 'FURNITURE_UPDATE',
        payload: { id: 'f-1', updates: { position: { x: 2, y: 0, z: 2 } } },
      },
      { type: 'FURNITURE_REMOVE', payload: 'not-exist' },
      {
        type: 'WALL_ADD',
        payload: {
          type: 'straight',
          start: { x: 0, y: 3 },
          end: { x: 4, y: 3 },
          thickness: 0.2,
          height: 2.8,
          materialId: 'mat-wall-white',
        },
      },
    ];
    const batchResult = validator.validateBatch(actions, 'user-1');
    expect(batchResult.appliedCount).toBe(2);
    expect(batchResult.results[0].valid).toBe(true);
    expect(batchResult.results[1].valid).toBe(false);
    expect(batchResult.results[2].valid).toBe(true);
    expect(batchResult.results.length).toBe(3);
  });

  it('操作日志应该记录所有成功的操作', () => {
    const action: PlaneAction = {
      type: 'FURNITURE_UPDATE',
      payload: { id: 'f-1', updates: { position: { x: 2, y: 0, z: 2 } } },
    };
    validator.validateAndApply(action, 'user-123');
    const log = validator.getLog();
    expect(log.length).toBe(1);
    expect(log[0].userId).toBe('user-123');
    expect(log[0].version).toBeGreaterThan(0);
  });
});

describe('ServerOperationValidator - 特殊情况', () => {
  let validator: ServerOperationValidator;

  beforeEach(() => {
    validator = new ServerOperationValidator(createInitialPlan());
  });

  it('canApply应该和validate结果一致', () => {
    const goodAction: PlaneAction = {
      type: 'WALL_UPDATE',
      payload: { id: 'w-1', updates: { height: 3.0 } },
    };
    const badAction: PlaneAction = { type: 'WALL_REMOVE', payload: 'not-exist' };

    expect(validator.canApply(goodAction)).toBe(true);
    expect(validator.canApply(badAction)).toBe(false);
  });

  it('PLAN_REPLACE应该直接有效', () => {
    const newPlan = createDefaultFloorPlan();
    newPlan.project.name = '新项目';
    const action: PlaneAction = { type: 'PLAN_REPLACE', payload: newPlan };
    expect(validator.canApply(action)).toBe(true);
  });

  it('PLAN_RESET应该有效且能清空数据', () => {
    const action: PlaneAction = { type: 'PLAN_RESET' };
    const result = validator.validateAndApply(action, 'admin');
    expect(result.valid).toBe(true);
    expect(validator.getState().walls.length).toBe(0);
    expect(validator.getState().furniture.length).toBe(0);
  });

  it('未知操作类型应该返回invalid-data', () => {
    const action = { type: 'UNKNOWN_ACTION' } as any;
    const result = validator.validate(action);
    expect(result.valid).toBe(false);
    expect(result.status).toBe('invalid-data');
    expect(result.message).toContain('未知操作类型');
  });
});
