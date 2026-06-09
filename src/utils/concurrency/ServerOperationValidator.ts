import type { PlaneState, PlaneAction } from '@/types/state';
import type { FloorPlan } from '@/types/floorplan';
import { planeReducer, floorPlanToPlaneState } from '@/store/planeReducer';

export type OperationValidationStatus = 'valid' | 'invalid-target' | 'invalid-data' | 'permission-denied' | 'version-mismatch';

export interface OperationValidationResult {
  valid: boolean;
  status: OperationValidationStatus;
  message: string;
  action?: PlaneAction;
}

export interface ValidatedOperation {
  action: PlaneAction;
  timestamp: number;
  userId: string;
  sessionId: string;
  version: number;
}

export class ServerOperationValidator {
  private state: PlaneState;
  private version: number = 0;
  private operationLog: ValidatedOperation[] = [];

  constructor(initialPlan?: FloorPlan) {
    this.state = initialPlan
      ? floorPlanToPlaneState(initialPlan)
      : this.createEmptyState();
  }

  private createEmptyState(): PlaneState {
    return {
      version: '1.0',
      project: {
        id: 'empty',
        name: '未命名',
        version: '1.0.0',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        settings: {
          gridSize: 0.1,
          snapToGrid: true,
          wallHeight: 2.8,
          wallThickness: 0.2,
          units: 'metric',
          angleConstraint: 15,
        },
      },
      walls: [],
      rooms: [],
      openings: [],
      furniture: [],
      lights: [],
      annotations: [],
      lastUpdatedAt: Date.now(),
    };
  }

  getState(): PlaneState {
    return this.state;
  }

  getVersion(): number {
    return this.version;
  }

  getLog(): ValidatedOperation[] {
    return [...this.operationLog];
  }

  validateAndApply(
    action: PlaneAction,
    userId: string = 'server',
    sessionId: string = 'main'
  ): OperationValidationResult {
    const validation = this.validate(action);

    if (!validation.valid) {
      return validation;
    }

    this.state = planeReducer(this.state, action);
    this.version++;

    this.operationLog.push({
      action,
      timestamp: Date.now(),
      userId,
      sessionId,
      version: this.version,
    });

    return {
      valid: true,
      status: 'valid',
      message: '操作已应用',
      action,
    };
  }

  validate(action: PlaneAction): OperationValidationResult {
    try {
      switch (action.type) {
        case 'WALL_UPDATE':
          return this.checkEntityExists('walls', action.payload.id, '墙体');

        case 'WALL_REMOVE':
          return this.checkEntityExists('walls', action.payload, '墙体');

        case 'ROOM_UPDATE':
          return this.checkEntityExists('rooms', action.payload.id, '房间');

        case 'ROOM_REMOVE':
          return this.checkEntityExists('rooms', action.payload, '房间');

        case 'OPENING_UPDATE':
          return this.checkOpeningUpdate(action.payload.id, action.payload.updates);

        case 'OPENING_REMOVE':
          return this.checkEntityExists('openings', action.payload, '门窗');

        case 'FURNITURE_UPDATE':
          return this.checkFurnitureUpdate(action.payload.id, action.payload.updates);

        case 'FURNITURE_REMOVE':
          return this.checkEntityExists('furniture', action.payload, '家具');

        case 'LIGHT_UPDATE':
          return this.checkEntityExists('lights', action.payload.id, '光源');

        case 'LIGHT_REMOVE':
          return this.checkEntityExists('lights', action.payload, '光源');

        case 'ANNOTATION_UPDATE':
          return this.checkEntityExists('annotations', action.payload.id, '批注');

        case 'ANNOTATION_REMOVE':
          return this.checkEntityExists('annotations', action.payload, '批注');

        case 'WALL_ADD':
        case 'ROOM_ADD':
        case 'OPENING_ADD':
        case 'FURNITURE_ADD':
        case 'LIGHT_ADD':
        case 'ANNOTATION_ADD':
        case 'SETTINGS_UPDATE':
        case 'PROJECT_UPDATE':
        case 'PLAN_REPLACE':
        case 'PLAN_RESET':
          return { valid: true, status: 'valid', message: '操作有效' };

        default:
          return {
            valid: false,
            status: 'invalid-data',
            message: `未知操作类型: ${(action as any).type}`,
          };
      }
    } catch (e) {
      return {
        valid: false,
        status: 'invalid-data',
        message: `验证异常: ${(e as Error).message}`,
      };
    }
  }

  private checkEntityExists(
    collection: keyof Pick<PlaneState, 'walls' | 'rooms' | 'openings' | 'furniture' | 'lights' | 'annotations'>,
    id: string,
    label: string
  ): OperationValidationResult {
    const exists = (this.state[collection] as { id: string }[]).some((e) => e.id === id);
    if (!exists) {
      return {
        valid: false,
        status: 'invalid-target',
        message: `${label}不存在: ${id}`,
      };
    }
    return { valid: true, status: 'valid', message: '存在' };
  }

  private checkOpeningUpdate(id: string, updates: any): OperationValidationResult {
    const existCheck = this.checkEntityExists('openings', id, '门窗');
    if (!existCheck.valid) return existCheck;

    if (updates.wallId !== undefined) {
      const wallCheck = this.checkEntityExists('walls', updates.wallId, '墙体');
      if (!wallCheck.valid) return wallCheck;
    }

    return { valid: true, status: 'valid', message: '门窗更新有效' };
  }

  private checkFurnitureUpdate(id: string, updates: any): OperationValidationResult {
    const existCheck = this.checkEntityExists('furniture', id, '家具');
    if (!existCheck.valid) return existCheck;

    if (updates.position !== undefined) {
      const pos = updates.position;
      if (pos.x === undefined || pos.y === undefined || pos.z === undefined) {
        return {
          valid: false,
          status: 'invalid-data',
          message: '家具位置必须包含 x, y, z 坐标',
        };
      }
    }

    if (updates.rotation !== undefined && typeof updates.rotation !== 'number') {
      return {
        valid: false,
        status: 'invalid-data',
        message: '家具旋转角度必须是数字',
      };
    }

    return { valid: true, status: 'valid', message: '家具更新有效' };
  }

  validateBatch(actions: PlaneAction[], userId: string = 'server'): {
    results: OperationValidationResult[];
    appliedCount: number;
    newVersion: number;
  } {
    const results: OperationValidationResult[] = [];
    let appliedCount = 0;

    for (const action of actions) {
      const result = this.validateAndApply(action, userId);
      results.push(result);
      if (result.valid) appliedCount++;
    }

    return {
      results,
      appliedCount,
      newVersion: this.version,
    };
  }

  canApply(action: PlaneAction): boolean {
    return this.validate(action).valid;
  }

  reset(plan?: FloorPlan): void {
    this.state = plan
      ? floorPlanToPlaneState(plan)
      : this.createEmptyState();
    this.version = 0;
    this.operationLog = [];
  }

  dispose(): void {
    this.operationLog = [];
  }
}
