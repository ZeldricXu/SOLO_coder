import { create } from 'zustand';
import type { Atom, Bond, ParsedMolecule, FileMetadata } from '@/modules/molecule-parser/types';
import type { Measurement, MeasurementType } from '@/modules/measurement-tools';
import type { ChainIsolationState } from '@/modules/camera-controller';

type CameraMode = 'orbit' | 'trackball' | 'fly';

interface AnnotationOpacityState {
  residueLabels: number;
  backboneRibbon: number;
  hBondIndicators: number;
  partialCharges: number;
  bFactorHeatmap: number;
  ligandHBondNetwork: number;
}

interface MolState {
  atoms: Atom[];
  bonds: Bond[];
  models: import('@/modules/molecule-parser/types').Model[];
  metadata: FileMetadata | null;
  currentModel: number;
  isLoading: boolean;
  isWebGPUAvailable: boolean;
  cameraMode: CameraMode;
  currentTool: MeasurementType | null;
  measurements: Measurement[];
  selectedAtoms: number[];
  residueLabelsVisible: boolean;
  backboneRibbonVisible: boolean;
  hBondIndicatorsVisible: boolean;
  partialChargesVisible: boolean;
  bFactorHeatmapVisible: boolean;
  ligandHBondNetworkVisible: boolean;
  annotationOpacities: AnnotationOpacityState;
  chainIsolation: ChainIsolationState;
  isPlaying: boolean;
  animationTime: number;
  animationEasing: 'linear' | 'smoothstep';
  animationSpeed: number;
  atomCount: number;
  bondCount: number;
  fps: number;
  colorMode: 'element' | 'bFactor' | 'chain' | 'residue';

  setAtoms: (atoms: Atom[]) => void;
  setBonds: (bonds: Bond[]) => void;
  setMolecule: (molecule: ParsedMolecule) => void;
  setCurrentModel: (index: number) => void;
  setIsLoading: (loading: boolean) => void;
  setWebGPUAvailable: (available: boolean) => void;
  setCameraMode: (mode: CameraMode) => void;
  setCurrentTool: (tool: MeasurementType | null) => void;
  addMeasurement: (measurement: Measurement) => void;
  removeMeasurement: (index: number) => void;
  setSelectedAtoms: (atoms: number[]) => void;
  setResidueLabelsVisible: (visible: boolean) => void;
  setBackboneRibbonVisible: (visible: boolean) => void;
  setHBondIndicatorsVisible: (visible: boolean) => void;
  setPartialChargesVisible: (visible: boolean) => void;
  setBFactorHeatmapVisible: (visible: boolean) => void;
  setLigandHBondNetworkVisible: (visible: boolean) => void;
  setAnnotationOpacity: (layer: keyof AnnotationOpacityState, opacity: number) => void;
  setChainIsolation: (state: Partial<ChainIsolationState>) => void;
  setIsPlaying: (playing: boolean) => void;
  setAnimationTime: (time: number) => void;
  setAnimationEasing: (easing: 'linear' | 'smoothstep') => void;
  setAnimationSpeed: (speed: number) => void;
  setAtomCount: (count: number) => void;
  setBondCount: (count: number) => void;
  setFps: (fps: number) => void;
  setColorMode: (mode: 'element' | 'bFactor' | 'chain' | 'residue') => void;
  reset: () => void;
}

const defaultAnnotationOpacities: AnnotationOpacityState = {
  residueLabels: 0.9,
  backboneRibbon: 0.7,
  hBondIndicators: 0.6,
  partialCharges: 0.85,
  bFactorHeatmap: 0.8,
  ligandHBondNetwork: 0.75,
};

const initialState = {
  atoms: [] as Atom[],
  bonds: [] as Bond[],
  models: [] as import('@/modules/molecule-parser/types').Model[],
  metadata: null as FileMetadata | null,
  currentModel: 0,
  isLoading: false,
  isWebGPUAvailable: false,
  cameraMode: 'orbit' as CameraMode,
  currentTool: null as MeasurementType | null,
  measurements: [] as Measurement[],
  selectedAtoms: [] as number[],
  residueLabelsVisible: false,
  backboneRibbonVisible: false,
  hBondIndicatorsVisible: false,
  partialChargesVisible: false,
  bFactorHeatmapVisible: false,
  ligandHBondNetworkVisible: false,
  annotationOpacities: defaultAnnotationOpacities,
  chainIsolation: {
    isActive: false,
    isolatedChainId: null,
    fadeOpacity: 0.15,
  },
  isPlaying: false,
  animationTime: 0,
  animationEasing: 'smoothstep' as const,
  animationSpeed: 1.0,
  atomCount: 0,
  bondCount: 0,
  fps: 0,
  colorMode: 'element' as const,
};

export const useMolStore = create<MolState>((set) => ({
  ...initialState,

  setAtoms: (atoms) => set({ atoms }),
  setBonds: (bonds) => set({ bonds }),
  setMolecule: (molecule) => set({
    atoms: molecule.atoms,
    bonds: molecule.bonds,
    models: molecule.models,
    metadata: molecule.metadata,
    currentModel: 0,
    atomCount: molecule.atoms.length,
    bondCount: molecule.bonds.length,
  }),
  setCurrentModel: (index) => set((state) => {
    if (index >= 0 && index < state.models.length) {
      const model = state.models[index];
      return {
        currentModel: index,
        atoms: model.atoms,
        bonds: model.bonds,
        atomCount: model.atoms.length,
        bondCount: model.bonds.length,
      };
    }
    return { currentModel: index };
  }),
  setIsLoading: (loading) => set({ isLoading: loading }),
  setWebGPUAvailable: (available) => set({ isWebGPUAvailable: available }),
  setCameraMode: (mode) => set({ cameraMode: mode }),
  setCurrentTool: (tool) => set({ currentTool: tool, selectedAtoms: [] }),
  addMeasurement: (measurement) => set((state) => ({
    measurements: [...state.measurements, measurement],
  })),
  removeMeasurement: (index) => set((state) => ({
    measurements: state.measurements.filter((_, i) => i !== index),
  })),
  setSelectedAtoms: (atoms) => set({ selectedAtoms: atoms }),
  setResidueLabelsVisible: (visible) => set({ residueLabelsVisible: visible }),
  setBackboneRibbonVisible: (visible) => set({ backboneRibbonVisible: visible }),
  setHBondIndicatorsVisible: (visible) => set({ hBondIndicatorsVisible: visible }),
  setPartialChargesVisible: (visible) => set({ partialChargesVisible: visible }),
  setBFactorHeatmapVisible: (visible) => set({ bFactorHeatmapVisible: visible }),
  setLigandHBondNetworkVisible: (visible) => set({ ligandHBondNetworkVisible: visible }),
  setAnnotationOpacity: (layer, opacity) => set((state) => ({
    annotationOpacities: {
      ...state.annotationOpacities,
      [layer]: Math.max(0, Math.min(1, opacity)),
    },
  })),
  setChainIsolation: (state) => set((prev) => ({
    chainIsolation: { ...prev.chainIsolation, ...state },
  })),
  setIsPlaying: (playing) => set({ isPlaying: playing }),
  setAnimationTime: (time) => set({ animationTime: time }),
  setAnimationEasing: (easing) => set({ animationEasing: easing }),
  setAnimationSpeed: (speed) => set({ animationSpeed: speed }),
  setAtomCount: (count) => set({ atomCount: count }),
  setBondCount: (count) => set({ bondCount: count }),
  setFps: (fps) => set({ fps }),
  setColorMode: (mode) => set({ colorMode: mode }),
  reset: () => set(initialState),
}));

