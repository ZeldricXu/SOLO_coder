import { create } from 'zustand';
import type { Point2D } from '@/types/geometry';

interface PanelState {
  furnitureLibrary: boolean;
  propertyPanel: boolean;
  renderDialog: boolean;
  annotationPanel: boolean;
  quotePanel: boolean;
  importDialog: boolean;
  exportDialog: boolean;
  settingsPanel: boolean;
  projectList: boolean;
}

interface MeasurementState {
  active: boolean;
  points: { x: number; y: number; z: number }[];
  currentValue: number | null;
}

interface UIState {
  panels: PanelState;
  leftPanelWidth: number;
  rightPanelWidth: number;
  topBarHeight: number;
  bottomBarHeight: number;
  showGrid: boolean;
  showDimensions: boolean;
  showAnnotations: boolean;
  showHelpers: boolean;
  zoom: number;
  panOffset: { x: number; y: number };
  measurement: MeasurementState;
  measuring: boolean;
  measurementPoints: Point2D[];
  notifications: { id: string; type: 'info' | 'success' | 'warning' | 'error'; message: string; timeout: number }[];
  mousePos: { x: number; y: number };
  mouseWorldPos: Point2D;
  worldPos: { x: number; y: number; z: number };
  isLoading: boolean;
  loadingText: string;
}

interface UIActions {
  togglePanel: (panel: keyof PanelState) => void;
  setPanel: (panel: keyof PanelState, open: boolean) => void;
  closeAllPanels: () => void;
  setLeftPanelWidth: (width: number) => void;
  setRightPanelWidth: (width: number) => void;
  setShowGrid: (show: boolean) => void;
  setShowDimensions: (show: boolean) => void;
  setShowAnnotations: (show: boolean) => void;
  setShowHelpers: (show: boolean) => void;
  setZoom: (zoom: number) => void;
  setPanOffset: (offset: { x: number; y: number }) => void;
  setMeasurementActive: (active: boolean) => void;
  addMeasurementPoint: (point: { x: number; y: number; z: number }) => void;
  clearMeasurement: () => void;
  setMeasurementValue: (value: number | null) => void;
  setMeasuring: (measuring: boolean) => void;
  addMeasurementPoint2D: (point: Point2D) => void;
  clearMeasurementPoints: () => void;
  addNotification: (notification: { type: 'info' | 'success' | 'warning' | 'error'; message: string; timeout?: number }) => void;
  removeNotification: (id: string) => void;
  setMousePos: (pos: { x: number; y: number }) => void;
  setMouseWorldPos: (pos: Point2D) => void;
  setWorldPos: (pos: { x: number; y: number; z: number }) => void;
  setLoading: (loading: boolean, text?: string) => void;
}

const initialState: UIState = {
  panels: {
    furnitureLibrary: false,
    propertyPanel: true,
    renderDialog: false,
    annotationPanel: false,
    quotePanel: false,
    importDialog: false,
    exportDialog: false,
    settingsPanel: false,
    projectList: false,
  },
  leftPanelWidth: 64,
  rightPanelWidth: 280,
  topBarHeight: 56,
  bottomBarHeight: 28,
  showGrid: true,
  showDimensions: true,
  showAnnotations: true,
  showHelpers: true,
  zoom: 1,
  panOffset: { x: 0, y: 0 },
  measurement: {
    active: false,
    points: [],
    currentValue: null,
  },
  measuring: false,
  measurementPoints: [],
  notifications: [],
  mousePos: { x: 0, y: 0 },
  mouseWorldPos: { x: 0, y: 0 },
  worldPos: { x: 0, y: 0, z: 0 },
  isLoading: false,
  loadingText: '',
};

export const useUIStore = create<UIState & UIActions>((set, get) => ({
  ...initialState,

  togglePanel: (panel) => {
    set((state) => ({
      panels: { ...state.panels, [panel]: !state.panels[panel] },
    }));
  },

  setPanel: (panel, open) => {
    set((state) => ({
      panels: { ...state.panels, [panel]: open },
    }));
  },

  closeAllPanels: () => {
    set({
      panels: {
        furnitureLibrary: false,
        propertyPanel: false,
        renderDialog: false,
        annotationPanel: false,
        quotePanel: false,
        importDialog: false,
        exportDialog: false,
        settingsPanel: false,
        projectList: false,
      },
    });
  },

  setLeftPanelWidth: (width) => set({ leftPanelWidth: Math.max(48, Math.min(320, width)) }),
  setRightPanelWidth: (width) => set({ rightPanelWidth: Math.max(200, Math.min(480, width)) }),

  setShowGrid: (show) => set({ showGrid: show }),
  setShowDimensions: (show) => set({ showDimensions: show }),
  setShowAnnotations: (show) => set({ showAnnotations: show }),
  setShowHelpers: (show) => set({ showHelpers: show }),

  setZoom: (zoom) => set({ zoom: Math.max(0.1, Math.min(10, zoom)) }),
  setPanOffset: (offset) => set({ panOffset: offset }),

  setMeasurementActive: (active) => {
    set({
      measurement: {
        ...get().measurement,
        active,
        points: active ? get().measurement.points : [],
        currentValue: active ? get().measurement.currentValue : null,
      },
    });
  },

  addMeasurementPoint: (point) => {
    set((state) => ({
      measurement: {
        ...state.measurement,
        points: [...state.measurement.points, point],
      },
    }));
  },

  clearMeasurement: () => {
    set({
      measurement: {
        active: get().measurement.active,
        points: [],
        currentValue: null,
      },
    });
  },

  setMeasurementValue: (value) => {
    set((state) => ({
      measurement: { ...state.measurement, currentValue: value },
    }));
  },

  setMeasuring: (measuring) => set({ measuring }),

  addMeasurementPoint2D: (point) => {
    set((state) => ({
      measurementPoints: [...state.measurementPoints, point],
    }));
  },

  clearMeasurementPoints: () => set({ measurementPoints: [] }),

  addNotification: (notification) => {
    const { type, message, timeout = 3000 } = notification;
    const id = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    set((state) => ({
      notifications: [...state.notifications, { id, type, message, timeout }],
    }));
    if (timeout > 0) {
      setTimeout(() => get().removeNotification(id), timeout);
    }
  },

  removeNotification: (id) => {
    set((state) => ({
      notifications: state.notifications.filter((n) => n.id !== id),
    }));
  },

  setMousePos: (pos) => set({ mousePos: pos }),
  setMouseWorldPos: (pos) => set({ mouseWorldPos: pos }),
  setWorldPos: (pos) => set({ worldPos: pos }),

  setLoading: (loading, text = '') => {
    set({ isLoading: loading, loadingText: text });
  },
}));
