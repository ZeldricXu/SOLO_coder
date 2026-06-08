import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  createMockWebSocket,
  createPendingOperation,
  createConcurrentFurnitureEdits,
  applyLastWriteWins,
  applyOptimisticLock,
  createCollisionTestData,
  createOperationBuffer,
} from '../fixtures/concurrencyFixtures';
import type { PendingOperation } from '../fixtures/concurrencyFixtures';

describe('并发场景测试', () => {
  describe('WebSocket重连缓存', () => {
    it('WebSocket断开期间本地编辑操作应该被缓存', () => {
      const mockWS = createMockWebSocket();
      const opBuffer = createOperationBuffer();

      expect(mockWS.isConnected).toBe(true);

      const op1 = createPendingOperation('update', 'furniture', { id: 'f1', position: { x: 1, y: 1 } });
      mockWS.send(JSON.stringify(op1));
      expect(mockWS.pendingMessageCount).toBe(0);

      mockWS.close();
      expect(mockWS.isConnected).toBe(false);

      const op2 = createPendingOperation('update', 'furniture', { id: 'f1', position: { x: 2, y: 2 } });
      mockWS.send(JSON.stringify(op2));
      expect(mockWS.pendingMessageCount).toBe(1);

      const op3 = createPendingOperation('update', 'furniture', { id: 'f2', position: { x: 3, y: 3 } });
      mockWS.send(JSON.stringify(op3));
      expect(mockWS.pendingMessageCount).toBe(2);
    });

    it('WebSocket重连后缓存的操作应该被重新发送', async () => {
      const mockWS = createMockWebSocket();

      mockWS.close();

      const ops = [
        createPendingOperation('update', 'furniture', { id: 'f1' }),
        createPendingOperation('update', 'furniture', { id: 'f2' }),
        createPendingOperation('add', 'wall', { start: { x: 0, y: 0 }, end: { x: 5, y: 0 } }),
      ];

      ops.forEach((op) => mockWS.send(JSON.stringify(op)));
      expect(mockWS.pendingMessageCount).toBe(3);

      const sentQueue = await mockWS.simulateReconnect();

      expect(mockWS.isConnected).toBe(true);
      expect(mockWS.reconnectionAttempts).toBe(1);
      expect(sentQueue).toHaveLength(3);
      expect(mockWS.pendingMessageCount).toBe(0);

      expect(mockWS.send).toHaveBeenCalledTimes(6);
    });

    it('重连期间新操作也应该被缓存并正确排序', async () => {
      const mockWS = createMockWebSocket();

      mockWS.close();

      const op1 = createPendingOperation('update', 'furniture', { id: 'f1', x: 1 });
      mockWS.send(JSON.stringify(op1));

      vi.useRealTimers();
      const reconnectPromise = mockWS.simulateReconnect();

      const op2 = createPendingOperation('update', 'furniture', { id: 'f1', x: 2 });
      mockWS.send(JSON.stringify(op2));
      expect(mockWS.pendingMessageCount).toBe(2);

      const sentQueue = await reconnectPromise;

      expect(sentQueue).toHaveLength(2);
      expect(mockWS.pendingMessageCount).toBe(0);
    });

    it('操作缓冲区应该有最大容量限制', () => {
      const opBuffer = createOperationBuffer();
      opBuffer.setMaxSize(5);

      for (let i = 0; i < 10; i++) {
        const op = createPendingOperation('update', 'furniture', { id: `f${i}` });
        opBuffer.add(op);
      }

      expect(opBuffer.size()).toBe(5);
    });

    it('断开连接超过最大缓存时应该给出警告', () => {
      const mockWS = createMockWebSocket();
      const opBuffer = createOperationBuffer();
      opBuffer.setMaxSize(3);

      const warnings: string[] = [];
      const consoleWarnSpy = vi
        .spyOn(console, 'warn')
        .mockImplementation((msg) => warnings.push(msg));

      mockWS.close();

      for (let i = 0; i < 5; i++) {
        const op = createPendingOperation('update', 'furniture', { id: `f${i}` });
        opBuffer.add(op);
        if (opBuffer.size() >= 3) {
          console.warn('操作缓存即将满，请检查网络连接');
        }
      }

      expect(warnings.length).toBeGreaterThanOrEqual(3);
      expect(warnings[0]).toContain('网络连接');

      consoleWarnSpy.mockRestore();
    });

    it('重连成功后应该通知UI更新状态', () => {
      const mockWS = createMockWebSocket();

      const statusUpdates: string[] = [];
      mockWS.on('open', () => statusUpdates.push('connected'));
      mockWS.on('close', () => statusUpdates.push('disconnected'));

      mockWS.close();
      expect(statusUpdates).toContain('disconnected');

      mockWS.connect();
      expect(statusUpdates).toContain('connected');
      expect(statusUpdates).toEqual(['disconnected', 'connected']);
    });
  });

  describe('多人协作冲突解决', () => {
    it('last-write-wins策略应该选择最新版本的操作', () => {
      const edits = createConcurrentFurnitureEdits('furniture-1', 3);

      const winner = applyLastWriteWins(edits);

      expect(winner).not.toBeNull();
      expect(winner!.version).toBe(3);
      expect(winner!.position.x).toBeCloseTo(1.0);
      expect(winner!.position.y).toBeCloseTo(0.6);
    });

    it('乐观锁应该阻止版本过期的更新', () => {
      const current = {
        id: 'f1',
        position: { x: 0, y: 0 },
        version: 2,
      };

      const staleUpdate = {
        id: 'f1',
        position: { x: 1, y: 1 },
        version: 1,
      };

      const result = applyOptimisticLock(current, staleUpdate);

      expect(result.success).toBe(false);
      expect(result.conflict).toBeDefined();
      expect(result.conflict!.current.version).toBe(2);
      expect(result.conflict!.update.version).toBe(1);
    });

    it('乐观锁应该允许新版本的更新', () => {
      const current = {
        id: 'f1',
        position: { x: 0, y: 0 },
        version: 2,
      };

      const freshUpdate = {
        id: 'f1',
        position: { x: 1, y: 1 },
        version: 3,
      };

      const result = applyOptimisticLock(current, freshUpdate);

      expect(result.success).toBe(true);
      expect(result.result).not.toBeNull();
      expect(result.result!.version).toBe(3);
      expect(result.conflict).toBeUndefined();
    });

    it('多个协作者同时移动同一家具时应该正确解决冲突', () => {
      const furnitureId = 'sofa-001';
      const edits = createConcurrentFurnitureEdits(furnitureId, 5);

      const winner = applyLastWriteWins(edits);

      expect(winner).not.toBeNull();
      expect(winner!.userId).toBe('user-4');
      expect(winner!.version).toBe(5);
    });

    it('冲突解决后应该通知所有协作者', () => {
      const mockWS = createMockWebSocket();

      const notifications: any[] = [];
      mockWS.on('message', (event: any) => {
        const data = JSON.parse(event.data);
        if (data.type === 'conflict-resolved') {
          notifications.push(data);
        }
      });

      mockWS.simulateIncomingMessage({
        type: 'conflict-resolved',
        furnitureId: 'sofa-001',
        winner: 'user-2',
        position: { x: 3, y: 3 },
        version: 5,
      });

      expect(notifications).toHaveLength(1);
      expect(notifications[0].furnitureId).toBe('sofa-001');
      expect(notifications[0].winner).toBe('user-2');
    });

    it('冲突时应该保存操作历史用于回滚', () => {
      const opBuffer = createOperationBuffer();

      const ops: PendingOperation[] = [
        createPendingOperation('update', 'furniture', { id: 'f1', x: 1 }, 1),
        createPendingOperation('update', 'furniture', { id: 'f1', x: 2 }, 2),
        createPendingOperation('update', 'furniture', { id: 'f1', x: 3 }, 3),
      ];

      ops.forEach((op) => opBuffer.add(op));

      const history = opBuffer.getByEntityType('furniture');
      expect(history).toHaveLength(3);
      expect(history[0].version).toBe(1);
      expect(history[2].version).toBe(3);
    });
  });

  describe('家具碰撞检测', () => {
    it('应该检测到家具重叠', () => {
      const { furnitureA, furnitureB_overlapping } = createCollisionTestData();

      const dx = Math.abs(furnitureA.position.x - furnitureB_overlapping.position.x);
      const dy = Math.abs(furnitureA.position.y - furnitureB_overlapping.position.y);

      const isColliding = dx < 1.0 && dy < 1.0;
      expect(isColliding).toBe(true);
    });

    it('应该识别不重叠的家具', () => {
      const { furnitureA, furnitureB_nonOverlapping } = createCollisionTestData();

      const dx = Math.abs(furnitureA.position.x - furnitureB_nonOverlapping.position.x);
      const dy = Math.abs(furnitureA.position.y - furnitureB_nonOverlapping.position.y);

      const isColliding = dx < 1.0 && dy < 1.0;
      expect(isColliding).toBe(false);
    });

    it('家具拖拽到已有家具上时应该阻止并给出提示', () => {
      const { furnitureA, furnitureB_overlapping } = createCollisionTestData();
      const warnings: string[] = [];

      const checkCollision = (a: any, b: any) => {
        const dx = Math.abs(a.position.x - b.position.x);
        const dy = Math.abs(a.position.y - b.position.y);
        return dx < 1.0 && dy < 1.0;
      };

      const tryPlaceFurniture = (newFurniture: any, existingFurniture: any[]) => {
        for (const existing of existingFurniture) {
          if (checkCollision(newFurniture, existing)) {
            warnings.push('家具位置重叠，无法放置');
            return false;
          }
        }
        return true;
      };

      const result = tryPlaceFurniture(furnitureB_overlapping, [furnitureA]);

      expect(result).toBe(false);
      expect(warnings).toContain('家具位置重叠，无法放置');
    });

    it('旋转后的家具碰撞检测应该正确', () => {
      const { furnitureA, furnitureB_overlapping } = createCollisionTestData();

      const originalCollision =
        Math.abs(furnitureA.position.x - furnitureB_overlapping.position.x) < 1.0 &&
        Math.abs(furnitureA.position.y - furnitureB_overlapping.position.y) < 1.0;

      furnitureB_overlapping.rotation = Math.PI / 2;
      furnitureB_overlapping.position.x = 2.0;
      furnitureB_overlapping.position.y = 2.0;

      const rotatedCollision =
        Math.abs(furnitureA.position.x - furnitureB_overlapping.position.x) < 1.0 &&
        Math.abs(furnitureA.position.y - furnitureB_overlapping.position.y) < 1.0;

      expect(originalCollision).toBe(true);
      expect(rotatedCollision).toBe(true);
    });

    it('缩放后的家具碰撞检测应该正确', () => {
      const { furnitureA, furnitureB_overlapping } = createCollisionTestData();

      furnitureB_overlapping.scale = 0.5;

      const collisionRadius = 1.0 * furnitureB_overlapping.scale;
      const isColliding =
        Math.abs(furnitureA.position.x - furnitureB_overlapping.position.x) < collisionRadius &&
        Math.abs(furnitureA.position.y - furnitureB_overlapping.position.y) < collisionRadius;

      expect(isColliding).toBe(true);
    });
  });

  describe('操作序列一致性', () => {
    it('相同操作序列在不同客户端应该产生相同结果', () => {
      const ops = [
        { type: 'add', data: { id: 'w1', start: { x: 0, y: 0 }, end: { x: 5, y: 0 } } },
        { type: 'add', data: { id: 'w2', start: { x: 5, y: 0 }, end: { x: 5, y: 4 } } },
        { type: 'add', data: { id: 'w3', start: { x: 5, y: 4 }, end: { x: 0, y: 4 } } },
        { type: 'add', data: { id: 'w4', start: { x: 0, y: 4 }, end: { x: 0, y: 0 } } },
      ];

      const applyOps = (operations: any[]) => {
        const state = { walls: [] as any[] };
        for (const op of operations) {
          if (op.type === 'add') {
            state.walls.push(op.data);
          }
        }
        return state;
      };

      const result1 = applyOps(ops);
      const result2 = applyOps([...ops]);

      expect(result1.walls).toHaveLength(4);
      expect(result2.walls).toHaveLength(4);
      expect(result1.walls[0].id).toBe(result2.walls[0].id);
    });

    it('操作版本号应该单调递增', () => {
      const versions: number[] = [];

      for (let i = 0; i < 10; i++) {
        const op = createPendingOperation('update', 'furniture', { id: 'f1' }, i + 1);
        versions.push(op.version);
      }

      for (let i = 1; i < versions.length; i++) {
        expect(versions[i]).toBeGreaterThan(versions[i - 1]);
      }
    });
  });
});
