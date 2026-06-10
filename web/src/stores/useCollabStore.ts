import { create } from 'zustand';
import type { CollaborationState, CRDTOperation } from '../types';

interface CollabActions {
  connect: (roomId: string) => void;
  disconnect: () => void;
  setConnected: (connected: boolean) => void;
  setRoomId: (roomId: string) => void;
  addOperation: (operation: CRDTOperation) => void;
  addOperations: (operations: CRDTOperation[]) => void;
  setOperations: (operations: CRDTOperation[]) => void;
  addPendingOperation: (operation: CRDTOperation) => void;
  removePendingOperation: (operationId: string) => void;
  flushPendingOperations: () => CRDTOperation[];
  updateLastSyncTime: () => void;
  clearOperations: () => void;
}

export const useCollabStore = create<CollaborationState & CollabActions>((set, get) => ({
  isConnected: false,
  roomId: '',
  operations: [],
  lastSyncTime: undefined,
  pendingOperations: [],

  connect: (roomId) =>
    set({
      roomId,
      isConnected: true,
    }),

  disconnect: () =>
    set({
      isConnected: false,
      pendingOperations: [],
    }),

  setConnected: (isConnected) =>
    set({ isConnected }),

  setRoomId: (roomId) =>
    set({ roomId }),

  addOperation: (operation) =>
    set((state) => ({
      operations: [...state.operations, operation],
    })),

  addOperations: (operations) =>
    set((state) => ({
      operations: [...state.operations, ...operations],
    })),

  setOperations: (operations) =>
    set({ operations }),

  addPendingOperation: (operation) =>
    set((state) => ({
      pendingOperations: [...state.pendingOperations, operation],
    })),

  removePendingOperation: (operationId) =>
    set((state) => ({
      pendingOperations: state.pendingOperations.filter(
        (op) => op.id !== operationId
      ),
    })),

  flushPendingOperations: () => {
    const { pendingOperations } = get();
    set({ pendingOperations: [] });
    return pendingOperations;
  },

  updateLastSyncTime: () =>
    set({ lastSyncTime: Date.now() }),

  clearOperations: () =>
    set({
      operations: [],
      pendingOperations: [],
    }),
}));
