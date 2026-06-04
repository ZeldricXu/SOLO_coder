import { create } from 'zustand';
import { Vec3, vec3 } from '@physics-sim/shared';

export type PanelType = 'objects' | 'sensors' | 'settings' | 'analysis' | 'reports' | null;

export interface UIState {
  selectedObjectId: string | null;
  selectedSensorId: string | null;
  activePanel: PanelType;
  showGrid: boolean;
  showAxes: boolean;
  showForces: boolean;
  showTrajectories: boolean;
  showStreamlines: boolean;
  showParticles: boolean;
  showCrossSection: boolean;
  cameraPosition: Vec3;
  cameraTarget: Vec3;
  crossSectionPosition: Vec3;
  crossSectionNormal: Vec3;
  isLoading: boolean;
  errors: string[];
  
  selectObject: (id: string | null) => void;
  selectSensor: (id: string | null) => void;
  setActivePanel: (panel: PanelType) => void;
  toggleGrid: () => void;
  toggleAxes: () => void;
  toggleForces: () => void;
  toggleTrajectories: () => void;
  toggleStreamlines: () => void;
  toggleParticles: () => void;
  toggleCrossSection: () => void;
  setCameraPosition: (pos: Vec3) => void;
  setCameraTarget: (target: Vec3) => void;
  setCrossSectionPosition: (pos: Vec3) => void;
  setCrossSectionNormal: (normal: Vec3) => void;
  setIsLoading: (loading: boolean) => void;
  addError: (error: string) => void;
  clearErrors: () => void;
}

export const useUIStore = create<UIState>((set) => ({
  selectedObjectId: null,
  selectedSensorId: null,
  activePanel: 'objects',
  showGrid: true,
  showAxes: true,
  showForces: false,
  showTrajectories: false,
  showStreamlines: false,
  showParticles: false,
  showCrossSection: false,
  cameraPosition: vec3(10, 10, 10),
  cameraTarget: vec3(0, 0, 0),
  crossSectionPosition: vec3(0, 0, 0),
  crossSectionNormal: vec3(0, 1, 0),
  isLoading: false,
  errors: [],

  selectObject: (id: string | null) => {
    set({ selectedObjectId: id, selectedSensorId: null });
  },

  selectSensor: (id: string | null) => {
    set({ selectedSensorId: id, selectedObjectId: null });
  },

  setActivePanel: (panel: PanelType) => {
    set({ activePanel: panel });
  },

  toggleGrid: () => {
    set((state) => ({ showGrid: !state.showGrid }));
  },

  toggleAxes: () => {
    set((state) => ({ showAxes: !state.showAxes }));
  },

  toggleForces: () => {
    set((state) => ({ showForces: !state.showForces }));
  },

  toggleTrajectories: () => {
    set((state) => ({ showTrajectories: !state.showTrajectories }));
  },

  toggleStreamlines: () => {
    set((state) => ({ showStreamlines: !state.showStreamlines }));
  },

  toggleParticles: () => {
    set((state) => ({ showParticles: !state.showParticles }));
  },

  toggleCrossSection: () => {
    set((state) => ({ showCrossSection: !state.showCrossSection }));
  },

  setCameraPosition: (pos: Vec3) => {
    set({ cameraPosition: { ...pos } });
  },

  setCameraTarget: (target: Vec3) => {
    set({ cameraTarget: { ...target } });
  },

  setCrossSectionPosition: (pos: Vec3) => {
    set({ crossSectionPosition: { ...pos } });
  },

  setCrossSectionNormal: (normal: Vec3) => {
    set({ crossSectionNormal: { ...normal } });
  },

  setIsLoading: (loading: boolean) => {
    set({ isLoading: loading });
  },

  addError: (error: string) => {
    set((state) => ({ errors: [...state.errors, error] }));
  },

  clearErrors: () => {
    set({ errors: [] });
  },
}));

export const selectIsObjectSelected = (id: string) => (state: UIState) => state.selectedObjectId === id;
export const selectIsSensorSelected = (id: string) => (state: UIState) => state.selectedSensorId === id;
export const selectHasSelection = (state: UIState) => state.selectedObjectId !== null || state.selectedSensorId !== null;
