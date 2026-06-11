import { create } from 'zustand';
import type {
  BoardState,
  Viewport,
  Layer,
  Stroke,
  Shape,
  ToolType,
  StrokeStyle,
  ShapeType,
  ShapeStyle,
  Version,
  StarConfig,
  ArrowConfig,
  RichTextConfig,
} from '../types';
import type { CanvasFacade, SyncFacade, ExportFacade } from '../wasm/facades';

interface FacadeState {
  canvasFacade: CanvasFacade | null;
  syncFacade: SyncFacade | null;
  exportFacade: ExportFacade | null;
}

interface BoardActions {
  initFacades: () => Promise<void>;
  setViewport: (viewport: Partial<Viewport>) => void;
  zoomIn: () => void;
  zoomOut: () => void;
  resetViewport: () => void;
  setActiveTool: (tool: ToolType) => void;
  setStrokeStyle: (style: Partial<StrokeStyle>) => void;
  setShapeType: (type: ShapeType) => void;
  setShapeStyle: (style: Partial<ShapeStyle>) => void;
  setStarConfig: (config: Partial<StarConfig>) => void;
  setArrowConfig: (config: Partial<ArrowConfig>) => void;
  setRichTextConfig: (config: Partial<RichTextConfig>) => void;
  addLayer: (layer: Layer) => void;
  updateLayer: (id: string, updates: Partial<Layer>) => void;
  removeLayer: (id: string) => void;
  addStroke: (stroke: Stroke) => void;
  updateStroke: (id: string, updates: Partial<Stroke>) => void;
  removeStroke: (id: string) => void;
  addShape: (shape: Shape) => void;
  updateShape: (id: string, updates: Partial<Shape>) => void;
  removeShape: (id: string) => void;
  selectObject: (id: string, additive?: boolean) => void;
  deselectObject: (id: string) => void;
  clearSelection: () => void;
  deleteSelected: () => void;
  toggleGrid: () => void;
  toggleExportPanel: () => void;
  toggleVersionTree: () => void;
  toggleComments: () => void;
  addVersion: (version: Version) => void;
  undo: () => void;
  redo: () => void;
}

const defaultViewport: Viewport = {
  x: 0,
  y: 0,
  zoom: 1,
};

const defaultStrokeStyle: StrokeStyle = {
  color: '#000000',
  width: 4,
  opacity: 1,
  cap: 'round',
  join: 'round',
};

const defaultShapeStyle: ShapeStyle = {
  fill: 'transparent',
  stroke: '#000000',
  strokeWidth: 2,
  opacity: 1,
};

const defaultStarConfig: StarConfig = {
  outerRadius: 50,
  innerRadius: 25,
  numPoints: 5,
  rotation: 0,
};

const defaultArrowConfig: ArrowConfig = {
  headStyle: 'triangle',
  tailStyle: 'none',
  headSize: 12,
  tailSize: 12,
};

const defaultRichTextConfig: RichTextConfig = {
  fontFamily: 'Arial, sans-serif',
  fontSize: 14,
  fontColor: '#000000',
  textAlign: 'left',
  backgroundColor: 'transparent',
  padding: 8,
  contentHtml: '',
};

const defaultLayer: Layer = {
  id: 'default',
  name: '默认图层',
  type: 'stroke',
  visible: true,
  locked: false,
  opacity: 1,
  order: 0,
  objectIds: [],
};

export const useBoardStore = create<BoardState & FacadeState & BoardActions>((set, get) => ({
  id: 'default-board',
  name: '未命名白板',
  viewport: defaultViewport,
  layers: [defaultLayer],
  strokes: [],
  shapes: [],
  selectedIds: [],
  tool: {
    activeTool: 'pen',
    strokeStyle: defaultStrokeStyle,
    shapeType: 'rectangle',
    shapeStyle: defaultShapeStyle,
    starConfig: defaultStarConfig,
    arrowConfig: defaultArrowConfig,
    richTextConfig: defaultRichTextConfig,
  },
  showGrid: true,
  showExportPanel: false,
  showVersionTree: false,
  showComments: false,
  history: [],
  canvasFacade: null,
  syncFacade: null,
  exportFacade: null,

  initFacades: async () => {
    const { wasm } = await import('../wasm');
    await wasm.init();

    const canvas = wasm.createCanvasFacade(window.innerWidth, window.innerHeight);
    const sync = wasm.createSyncFacade('default-board', 'local-user', 'anonymous');
    const exp = wasm.createExportFacade();

    set({
      canvasFacade: canvas,
      syncFacade: sync,
      exportFacade: exp,
    });
  },

  setViewport: (viewport) =>
    set((state) => ({
      viewport: { ...state.viewport, ...viewport },
    })),

  zoomIn: () =>
    set((state) => ({
      viewport: {
        ...state.viewport,
        zoom: Math.min(state.viewport.zoom * 1.2, 5),
      },
    })),

  zoomOut: () =>
    set((state) => ({
      viewport: {
        ...state.viewport,
        zoom: Math.max(state.viewport.zoom / 1.2, 0.1),
      },
    })),

  resetViewport: () =>
    set({
      viewport: defaultViewport,
    }),

  setActiveTool: (activeTool) =>
    set((state) => ({
      tool: { ...state.tool, activeTool },
    })),

  setStrokeStyle: (style) =>
    set((state) => ({
      tool: {
        ...state.tool,
        strokeStyle: { ...state.tool.strokeStyle, ...style },
      },
    })),

  setShapeType: (shapeType) =>
    set((state) => ({
      tool: { ...state.tool, shapeType },
    })),

  setShapeStyle: (style) =>
    set((state) => ({
      tool: {
        ...state.tool,
        shapeStyle: { ...state.tool.shapeStyle, ...style },
      },
    })),

  setStarConfig: (config) =>
    set((state) => ({
      tool: {
        ...state.tool,
        starConfig: { ...state.tool.starConfig, ...config },
      },
    })),

  setArrowConfig: (config) =>
    set((state) => ({
      tool: {
        ...state.tool,
        arrowConfig: { ...state.tool.arrowConfig, ...config },
      },
    })),

  setRichTextConfig: (config) =>
    set((state) => ({
      tool: {
        ...state.tool,
        richTextConfig: { ...state.tool.richTextConfig, ...config },
      },
    })),

  addLayer: (layer) =>
    set((state) => ({
      layers: [...state.layers, layer],
    })),

  updateLayer: (id, updates) =>
    set((state) => ({
      layers: state.layers.map((l) =>
        l.id === id ? { ...l, ...updates } : l
      ),
    })),

  removeLayer: (id) =>
    set((state) => ({
      layers: state.layers.filter((l) => l.id !== id),
    })),

  addStroke: (stroke) =>
    set((state) => ({
      strokes: [...state.strokes, stroke],
    })),

  updateStroke: (id, updates) =>
    set((state) => ({
      strokes: state.strokes.map((s) =>
        s.id === id ? { ...s, ...updates, updatedAt: Date.now() } : s
      ),
    })),

  removeStroke: (id) =>
    set((state) => ({
      strokes: state.strokes.filter((s) => s.id !== id),
      selectedIds: state.selectedIds.filter((sid) => sid !== id),
    })),

  addShape: (shape) =>
    set((state) => ({
      shapes: [...state.shapes, shape],
    })),

  updateShape: (id, updates) =>
    set((state) => ({
      shapes: state.shapes.map((s) =>
        s.id === id ? { ...s, ...updates, updatedAt: Date.now() } : s
      ),
    })),

  removeShape: (id) =>
    set((state) => ({
      shapes: state.shapes.filter((s) => s.id !== id),
      selectedIds: state.selectedIds.filter((sid) => sid !== id),
    })),

  selectObject: (id, additive = false) =>
    set((state) => ({
      selectedIds: additive ? [...state.selectedIds, id] : [id],
    })),

  deselectObject: (id) =>
    set((state) => ({
      selectedIds: state.selectedIds.filter((sid) => sid !== id),
    })),

  clearSelection: () =>
    set({ selectedIds: [] }),

  deleteSelected: () => {
    const { selectedIds, strokes, shapes } = get();
    set({
      strokes: strokes.filter((s) => !selectedIds.includes(s.id)),
      shapes: shapes.filter((s) => !selectedIds.includes(s.id)),
      selectedIds: [],
    });
  },

  toggleGrid: () =>
    set((state) => ({ showGrid: !state.showGrid })),

  toggleExportPanel: () =>
    set((state) => ({ showExportPanel: !state.showExportPanel })),

  toggleVersionTree: () =>
    set((state) => ({ showVersionTree: !state.showVersionTree })),

  toggleComments: () =>
    set((state) => ({ showComments: !state.showComments })),

  addVersion: (version) =>
    set((state) => ({
      history: [...state.history, version],
    })),

  undo: () => {
    const { syncFacade } = get();
    if (syncFacade && syncFacade.canUndo()) {
      const opJson = syncFacade.undo();
      if (opJson) {
        console.log('Undo via SyncFacade:', opJson);
        return;
      }
    }
    console.log('Undo operation');
  },

  redo: () => {
    const { syncFacade } = get();
    if (syncFacade && syncFacade.canRedo()) {
      const opJson = syncFacade.redo();
      if (opJson) {
        console.log('Redo via SyncFacade:', opJson);
        return;
      }
    }
    console.log('Redo operation');
  },
}));
