import { create } from 'zustand';
import { produce } from 'immer';
import type {
  FloorPlan,
  Wall,
  Room,
  Opening,
  FurnitureItem,
  LightSource,
  Material,
  Annotation,
  Project,
  ProjectSettings,
  ToolType,
  ViewMode,
} from '@/types/floorplan';
import { DEFAULT_MATERIALS } from '@/types/materials';
import { FLOOR_PLAN_VERSION } from '@/types/floorplan';
import { generateId } from '@/utils/geometry';

interface HistoryState {
  past: FloorPlan[];
  future: FloorPlan[];
}

interface FloorPlanState {
  floorPlan: FloorPlan;
  selectedIds: string[];
  currentTool: ToolType;
  viewMode: ViewMode;
  history: HistoryState;
  isDrawing: boolean;
  drawingPreview: Wall | null;
  hoveredId: string | null;
}

interface FloorPlanActions {
  setFloorPlan: (plan: FloorPlan) => void;
  resetFloorPlan: () => void;
  select: (id: string | string[] | null) => void;
  deselectAll: () => void;
  selectAnnotation: (id: string) => void;
  setCurrentTool: (tool: ToolType) => void;
  setViewMode: (mode: ViewMode) => void;
  addWall: (wall: Omit<Wall, 'id'>) => void;
  updateWall: (id: string, updates: Partial<Wall>) => void;
  removeWall: (id: string) => void;
  addRoom: (room: Omit<Room, 'id'>) => void;
  updateRoom: (id: string, updates: Partial<Room>) => void;
  removeRoom: (id: string) => void;
  addOpening: (opening: Omit<Opening, 'id'>) => void;
  updateOpening: (id: string, updates: Partial<Opening>) => void;
  removeOpening: (id: string) => void;
  addFurniture: (item: Omit<FurnitureItem, 'id'>) => void;
  updateFurniture: (id: string, updates: Partial<FurnitureItem>) => void;
  removeFurniture: (id: string) => void;
  addLight: (light: Omit<LightSource, 'id'>) => void;
  updateLight: (id: string, updates: Partial<LightSource>) => void;
  removeLight: (id: string) => void;
  addAnnotation: (annotation: Omit<Annotation, 'id'>) => void;
  updateAnnotation: (id: string, updates: Partial<Annotation>) => void;
  removeAnnotation: (id: string) => void;
  updateSettings: (settings: Partial<ProjectSettings>) => void;
  updateProject: (updates: Partial<Project>) => void;
  setIsDrawing: (drawing: boolean) => void;
  setDrawingPreview: (preview: Wall | null) => void;
  setHoveredId: (id: string | null) => void;
  undo: () => void;
  redo: () => void;
  saveToHistory: () => void;
}

const createDefaultProject = (): Project => ({
  id: generateId(),
  name: '未命名项目',
  version: '1.0.0',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  settings: {
    gridSize: 0.1,
    wallThickness: 0.2,
    wallHeight: 2.8,
    snapToGrid: true,
    angleConstraint: 45,
    units: 'metric',
  },
});

const createDefaultFloorPlan = (): FloorPlan => ({
  version: FLOOR_PLAN_VERSION,
  project: createDefaultProject(),
  walls: [],
  rooms: [],
  openings: [],
  furniture: [],
  lights: [],
  materials: [...DEFAULT_MATERIALS],
  annotations: [],
});

export { createDefaultFloorPlan };

export const useFloorPlanStore = create<FloorPlanState & FloorPlanActions>((set, get) => ({
  floorPlan: createDefaultFloorPlan(),
  selectedIds: [],
  currentTool: 'select',
  viewMode: '3d',
  history: { past: [], future: [] },
  isDrawing: false,
  drawingPreview: null,
  hoveredId: null,

  setFloorPlan: (plan) => {
    set({ floorPlan: plan });
    get().saveToHistory();
  },

  resetFloorPlan: () => {
    set({
      floorPlan: createDefaultFloorPlan(),
      selectedIds: [],
      history: { past: [], future: [] },
    });
  },

  select: (id) => {
    if (id === null) {
      set({ selectedIds: [] });
    } else if (Array.isArray(id)) {
      set({ selectedIds: id });
    } else {
      set((state) => ({
        selectedIds: state.selectedIds.includes(id)
          ? state.selectedIds.filter((i) => i !== id)
          : [...state.selectedIds, id],
      }));
    }
  },

  deselectAll: () => set({ selectedIds: [] }),

  selectAnnotation: (id) => {
    set({ selectedIds: [id] });
  },

  setCurrentTool: (tool) => {
    set({ currentTool: tool });
    if (tool !== 'select') {
      set({ selectedIds: [] });
    }
  },

  setViewMode: (mode) => set({ viewMode: mode }),

  addWall: (wall) => {
    const newWall: Wall = { ...wall, id: generateId() };
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.walls.push(newWall);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateWall: (id, updates) => {
    set(
      produce((state: FloorPlanState) => {
        const idx = state.floorPlan.walls.findIndex((w) => w.id === id);
        if (idx !== -1) {
          state.floorPlan.walls[idx] = { ...state.floorPlan.walls[idx], ...updates };
          state.floorPlan.project.updatedAt = new Date().toISOString();
        }
      })
    );
  },

  removeWall: (id) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.walls = state.floorPlan.walls.filter((w) => w.id !== id);
        state.floorPlan.openings = state.floorPlan.openings.filter((o) => o.wallId !== id);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  addRoom: (room) => {
    const newRoom: Room = { ...room, id: generateId() };
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.rooms.push(newRoom);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateRoom: (id, updates) => {
    set(
      produce((state: FloorPlanState) => {
        const idx = state.floorPlan.rooms.findIndex((r) => r.id === id);
        if (idx !== -1) {
          state.floorPlan.rooms[idx] = { ...state.floorPlan.rooms[idx], ...updates };
          state.floorPlan.project.updatedAt = new Date().toISOString();
        }
      })
    );
  },

  removeRoom: (id) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.rooms = state.floorPlan.rooms.filter((r) => r.id !== id);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  addOpening: (opening) => {
    const newOpening: Opening = { ...opening, id: generateId() };
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.openings.push(newOpening);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateOpening: (id, updates) => {
    set(
      produce((state: FloorPlanState) => {
        const idx = state.floorPlan.openings.findIndex((o) => o.id === id);
        if (idx !== -1) {
          state.floorPlan.openings[idx] = { ...state.floorPlan.openings[idx], ...updates };
          state.floorPlan.project.updatedAt = new Date().toISOString();
        }
      })
    );
  },

  removeOpening: (id) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.openings = state.floorPlan.openings.filter((o) => o.id !== id);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  addFurniture: (item) => {
    const newItem: FurnitureItem = { ...item, id: generateId() };
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.furniture.push(newItem);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateFurniture: (id, updates) => {
    set(
      produce((state: FloorPlanState) => {
        const idx = state.floorPlan.furniture.findIndex((f) => f.id === id);
        if (idx !== -1) {
          state.floorPlan.furniture[idx] = { ...state.floorPlan.furniture[idx], ...updates };
          state.floorPlan.project.updatedAt = new Date().toISOString();
        }
      })
    );
  },

  removeFurniture: (id) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.furniture = state.floorPlan.furniture.filter((f) => f.id !== id);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  addLight: (light) => {
    const newLight: LightSource = { ...light, id: generateId() };
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.lights.push(newLight);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateLight: (id, updates) => {
    set(
      produce((state: FloorPlanState) => {
        const idx = state.floorPlan.lights.findIndex((l) => l.id === id);
        if (idx !== -1) {
          state.floorPlan.lights[idx] = { ...state.floorPlan.lights[idx], ...updates };
          state.floorPlan.project.updatedAt = new Date().toISOString();
        }
      })
    );
  },

  removeLight: (id) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.lights = state.floorPlan.lights.filter((l) => l.id !== id);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  addAnnotation: (annotation) => {
    const newAnnotation: Annotation = {
      ...annotation,
      id: generateId(),
      status: annotation.status || 'open',
      createdAt: annotation.createdAt || Date.now(),
    };
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.annotations.push(newAnnotation);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateAnnotation: (id, updates) => {
    set(
      produce((state: FloorPlanState) => {
        const idx = state.floorPlan.annotations.findIndex((a) => a.id === id);
        if (idx !== -1) {
          state.floorPlan.annotations[idx] = { ...state.floorPlan.annotations[idx], ...updates };
          state.floorPlan.project.updatedAt = new Date().toISOString();
        }
      })
    );
  },

  removeAnnotation: (id) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.annotations = state.floorPlan.annotations.filter((a) => a.id !== id);
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
    get().saveToHistory();
  },

  updateSettings: (settings) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.project.settings = { ...state.floorPlan.project.settings, ...settings };
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
  },

  updateProject: (updates) => {
    set(
      produce((state: FloorPlanState) => {
        state.floorPlan.project = { ...state.floorPlan.project, ...updates };
        state.floorPlan.project.updatedAt = new Date().toISOString();
      })
    );
  },

  setIsDrawing: (drawing) => set({ isDrawing: drawing }),

  setDrawingPreview: (preview) => set({ drawingPreview: preview }),

  setHoveredId: (id) => set({ hoveredId: id }),

  saveToHistory: () => {
    const { floorPlan, history } = get();
    set({
      history: {
        past: [...history.past, JSON.parse(JSON.stringify(floorPlan))].slice(-50),
        future: [],
      },
    });
  },

  undo: () => {
    const { history } = get();
    if (history.past.length === 0) return;

    const newPast = [...history.past];
    const current = newPast.pop()!;
    const previous = newPast[newPast.length - 1] || createDefaultFloorPlan();

    set({
      floorPlan: previous,
      history: {
        past: newPast,
        future: [current, ...history.future].slice(-50),
      },
      selectedIds: [],
    });
  },

  redo: () => {
    const { history } = get();
    if (history.future.length === 0) return;

    const newFuture = [...history.future];
    const next = newFuture.shift()!;

    set({
      floorPlan: next,
      history: {
        past: [...history.past, get().floorPlan].slice(-50),
        future: newFuture,
      },
      selectedIds: [],
    });
  },
}));
