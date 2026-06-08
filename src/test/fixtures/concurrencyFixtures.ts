import { vi } from 'vitest';
import { generateId } from '@/utils/geometry';
import type { FurnitureItem, FloorPlan } from '@/types/floorplan';
import { createTestFurniture } from './sceneFixtures';

export interface PendingOperation {
  id: string;
  type: 'add' | 'update' | 'delete';
  entityType: 'wall' | 'furniture' | 'opening' | 'light';
  data: any;
  timestamp: number;
  version: number;
}

export interface CollisionEvent {
  furnitureA: FurnitureItem;
  furnitureB: FurnitureItem;
  timestamp: number;
  prevented: boolean;
}

export const createPendingOperation = (
  type: PendingOperation['type'],
  entityType: PendingOperation['entityType'],
  data: any,
  version: number = 1
): PendingOperation => ({
  id: generateId(),
  type,
  entityType,
  data,
  timestamp: Date.now(),
  version,
});

export const createMockWebSocket = () => {
  const listeners = new Map<string, Set<Function>>();
  let isConnected = true;
  let messageQueue: any[] = [];
  let reconnectionAttempts = 0;

  const mockWS = {
    send: vi.fn((data: any) => {
      if (!isConnected) {
        messageQueue.push(data);
        return false;
      }
      return true;
    }),
    close: vi.fn(() => {
      isConnected = false;
      mockWS.emit('close');
    }),
    connect: vi.fn(() => {
      isConnected = true;
      const queue = [...messageQueue];
      messageQueue = [];
      queue.forEach((msg) => mockWS.send(msg));
      mockWS.emit('open');
      return queue;
    }),
    simulateReconnect: vi.fn(() => {
      isConnected = false;
      mockWS.emit('close');
      reconnectionAttempts++;
      return Promise.resolve().then(() => {
        const queue = mockWS.connect();
        return queue;
      });
    }),
    simulateIncomingMessage: vi.fn((data: any) => {
      mockWS.emit('message', { data: JSON.stringify(data) });
    }),
    on: vi.fn((event: string, callback: Function) => {
      if (!listeners.has(event)) {
        listeners.set(event, new Set());
      }
      listeners.get(event)!.add(callback);
    }),
    off: vi.fn((event: string, callback: Function) => {
      listeners.get(event)?.delete(callback);
    }),
    emit: vi.fn((event: string, ...args: any[]) => {
      listeners.get(event)?.forEach((cb) => cb(...args));
    }),
    get isConnected() {
      return isConnected;
    },
    get reconnectionAttempts() {
      return reconnectionAttempts;
    },
    get messageQueue() {
      return [...messageQueue];
    },
    get pendingMessageCount() {
      return messageQueue.length;
    },
  };

  return mockWS;
};

export const createConcurrentFurnitureEdits = (
  furnitureId: string,
  userCount: number
): { userId: string; position: { x: number; y: number }; version: number }[] => {
  const edits: { userId: string; position: { x: number; y: number }; version: number }[] = [];
  
  for (let i = 0; i < userCount; i++) {
    edits.push({
      userId: `user-${i}`,
      position: { x: i * 0.5, y: i * 0.3 },
      version: i + 1,
    });
  }
  
  return edits;
};

export const applyLastWriteWins = <T extends { version: number }>(
  operations: T[]
): T | null => {
  if (operations.length === 0) return null;
  return operations.reduce((latest, current) =>
    current.version > latest.version ? current : latest
  );
};

export const applyOptimisticLock = <T extends { version: number }>(
  current: T,
  update: T
): { success: boolean; result: T | null; conflict?: { current: T; update: T } } => {
  if (update.version <= current.version) {
    return {
      success: false,
      result: null,
      conflict: { current, update },
    };
  }
  
  return {
    success: true,
    result: { ...update, version: update.version },
  };
};

export const createCollisionTestData = (): {
  furnitureA: FurnitureItem;
  furnitureB_overlapping: FurnitureItem;
  furnitureB_nonOverlapping: FurnitureItem;
} => {
  const furnitureA = createTestFurniture('sofa-3seat', { x: 2, y: 2 });
  
  const furnitureB_overlapping = createTestFurniture('armchair', { x: 2.2, y: 2.2 });
  
  const furnitureB_nonOverlapping = createTestFurniture('armchair', { x: 5, y: 5 });
  
  return { furnitureA, furnitureB_overlapping, furnitureB_nonOverlapping };
};

export const createOperationBuffer = () => {
  const operations: PendingOperation[] = [];
  let maxSize = 100;

  return {
    add: (op: PendingOperation) => {
      if (operations.length >= maxSize) {
        operations.shift();
      }
      operations.push(op);
      return operations.length;
    },
    remove: (opId: string) => {
      const index = operations.findIndex((op) => op.id === opId);
      if (index > -1) {
        return operations.splice(index, 1)[0];
      }
      return null;
    },
    getAll: () => [...operations],
    getByType: (type: PendingOperation['type']) =>
      operations.filter((op) => op.type === type),
    getByEntityType: (entityType: PendingOperation['entityType']) =>
      operations.filter((op) => op.entityType === entityType),
    clear: () => {
      operations.length = 0;
    },
    size: () => operations.length,
    setMaxSize: (size: number) => {
      maxSize = size;
      while (operations.length > maxSize) {
        operations.shift();
      }
    },
  };
};

export const concurrencyTestFixtures = {
  createPendingOperation,
  createMockWebSocket,
  createConcurrentFurnitureEdits,
  applyLastWriteWins,
  applyOptimisticLock,
  createCollisionTestData,
  createOperationBuffer,
};

export default concurrencyTestFixtures;
