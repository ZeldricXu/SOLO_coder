import { create } from 'zustand';
import { v4 as uuidv4 } from 'uuid';

const generateObjectId = (objectType) => {
  const prefix = {
    wall: 'obj_wall',
    door: 'obj_door',
    window: 'obj_window',
    furniture: 'obj_furniture'
  }[objectType] || 'obj_generic';
  
  return `${prefix}_${Date.now()}`;
};

const useStore = create((set, get) => ({
  userId: `user_${uuidv4().substr(0, 8)}`,
  userName: '设计师',
  
  sceneId: null,
  currentVersion: 0,
  
  connectionStatus: 'disconnected',
  
  objects: new Map(),
  
  selectedObjectId: null,
  
  activeTool: 'select',
  
  users: [],
  
  pendingOperations: [],
  
  assetLibrary: {
    assets: [],
    categories: [],
    loading: false,
    selectedCategory: 'all'
  },
  
  setUserId: (userId) => set({ userId }),
  setUserName: (userName) => set({ userName }),
  
  setSceneId: (sceneId) => set({ sceneId }),
  setCurrentVersion: (version) => set({ currentVersion: version }),
  
  setConnectionStatus: (status) => set({ connectionStatus: status }),
  
  addObject: (objectData) => {
    set((state) => {
      const newObjects = new Map(state.objects);
      const objectId = objectData.object_id || generateObjectId(objectData.object_type);
      newObjects.set(objectId, {
        object_id: objectId,
        ...objectData,
        version: objectData.version || 1
      });
      return { objects: newObjects };
    });
  },
  
  updateObject: (objectId, updates) => {
    set((state) => {
      const newObjects = new Map(state.objects);
      const existing = newObjects.get(objectId);
      if (existing) {
        newObjects.set(objectId, {
          ...existing,
          ...updates,
          version: (updates.version !== undefined ? updates.version : existing.version + 1)
        });
      }
      return { objects: newObjects };
    });
  },
  
  deleteObject: (objectId) => {
    set((state) => {
      const newObjects = new Map(state.objects);
      newObjects.delete(objectId);
      return {
        objects: newObjects,
        selectedObjectId: state.selectedObjectId === objectId ? null : state.selectedObjectId
      };
    });
  },
  
  setObjects: (objectsArray) => {
    set((state) => {
      const newObjects = new Map();
      objectsArray.forEach(obj => {
        if (!obj.is_deleted) {
          newObjects.set(obj.object_id, obj);
        }
      });
      return { objects: newObjects };
    });
  },
  
  clearObjects: () => set({ objects: new Map() }),
  
  getObject: (objectId) => {
    const state = get();
    return state.objects.get(objectId);
  },
  
  getAllObjects: () => {
    const state = get();
    return Array.from(state.objects.values());
  },
  
  selectObject: (objectId) => set({ selectedObjectId: objectId }),
  clearSelection: () => set({ selectedObjectId: null }),
  
  setActiveTool: (tool) => set({ activeTool: tool }),
  
  setUsers: (users) => set({ users }),
  addUser: (user) => set((state) => ({
    users: [...state.users, user]
  })),
  removeUser: (userId) => set((state) => ({
    users: state.users.filter(u => u.user_id !== userId)
  })),
  
  addPendingOperation: (operation) => set((state) => ({
    pendingOperations: [...state.pendingOperations, operation]
  })),
  
  removePendingOperation: (operationId) => set((state) => ({
    pendingOperations: state.pendingOperations.filter(op => op.operation_id !== operationId)
  })),
  
  setAssetLibrary: (data) => set((state) => ({
    assetLibrary: {
      ...state.assetLibrary,
      assets: data.assets || state.assetLibrary.assets,
      categories: data.categories || state.assetLibrary.categories,
      loading: data.loading !== undefined ? data.loading : state.assetLibrary.loading
    }
  })),
  
  setSelectedCategory: (category) => set((state) => ({
    assetLibrary: { ...state.assetLibrary, selectedCategory: category }
  })),
  
  resetScene: () => set({
    sceneId: null,
    currentVersion: 0,
    objects: new Map(),
    selectedObjectId: null,
    users: [],
    pendingOperations: []
  })
}));

export default useStore;
