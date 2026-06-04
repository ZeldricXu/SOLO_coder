import { create } from 'zustand';
import { Vec3, vec3 } from '@physics-sim/shared';
import { SimulationEngine, EngineStepResult } from '@physics-sim/physics';
import { SimulationConfig, DEFAULT_SIMULATION_CONFIG } from '@physics-sim/shared';
import { useSceneStore } from './sceneStore';
import { useUIStore } from './uiStore';

export interface SimulationState {
  engine: SimulationEngine | null;
  config: SimulationConfig;
  isRunning: boolean;
  isPaused: boolean;
  currentTime: number;
  speed: number;
  lastStepResult: EngineStepResult | null;
  sensorData: Map<string, { time: number; value: number | Vec3 }[]>;
  
  initEngine: () => void;
  startSimulation: () => void;
  pauseSimulation: () => void;
  resumeSimulation: () => void;
  stopSimulation: () => void;
  resetSimulation: () => void;
  stepSimulation: (dt?: number) => void;
  setSpeed: (speed: number) => void;
  setConfig: (config: Partial<SimulationConfig>) => void;
  loadSceneToEngine: () => void;
}

let unsubscribeScene: (() => void) | null = null;
let unsubscribeUI: (() => void) | null = null;

type SceneSlice = { objects: Map<string, any>; sensors: Map<string, any>; gravity: Vec3 };

function getSceneSlice(state: any): SceneSlice {
  return { objects: state.objects, sensors: state.sensors, gravity: state.gravity };
}

function scenesAreEqual(a: SceneSlice, b: SceneSlice): boolean {
  return (
    a.objects.size === b.objects.size && 
    a.sensors.size === b.sensors.size &&
    a.gravity.x === b.gravity.x && a.gravity.y === b.gravity.y && a.gravity.z === b.gravity.z
  );
}

function setupStoreSubscriptions() {
  if (unsubscribeScene || unsubscribeUI) return;

  let prevSceneSlice = getSceneSlice(useSceneStore.getState());
  unsubscribeScene = useSceneStore.subscribe((state) => {
    const newSlice = getSceneSlice(state);
    if (!scenesAreEqual(newSlice, prevSceneSlice)) {
      prevSceneSlice = newSlice;
      const simState = useSimulationStore.getState();
      if (simState.engine && !simState.isRunning) {
        simState.loadSceneToEngine();
      }
    }
  });

  let prevSelectedId = useUIStore.getState().selectedObjectId;
  unsubscribeUI = useUIStore.subscribe((state) => {
    const selectedId = state.selectedObjectId;
    if (selectedId !== prevSelectedId) {
      prevSelectedId = selectedId;
      const simState = useSimulationStore.getState();
      if (selectedId && simState.engine) {
        const obj = useSceneStore.getState().objects.get(selectedId);
        if (obj) {
          console.log(`Selected object: ${(obj as any).objectType || (obj as any).type}`);
        }
      }
    }
  });
}

export const useSimulationStore = create<SimulationState>((set, get) => {
  setupStoreSubscriptions();

  return {
    engine: null,
    config: { ...DEFAULT_SIMULATION_CONFIG },
    isRunning: false,
    isPaused: false,
    currentTime: 0,
    speed: 1,
    lastStepResult: null,
    sensorData: new Map(),

    initEngine: () => {
      const engine = new SimulationEngine({
        enableMechanics: true,
        enableElectromagnetics: true,
        enableThermodynamics: true,
        couplingEnabled: true,
      });
      set({ engine });
      get().loadSceneToEngine();
    },

    loadSceneToEngine: () => {
      const { engine } = get();
      if (!engine) return;

      const sceneState = useSceneStore.getState();
      engine.reset();
      
      sceneState.objects.forEach((obj) => {
        engine.addPhysicsObject(obj, obj.velocity, obj.angularVelocity);
      });
      sceneState.sensors.forEach((sensor) => {
        engine.addSensor(sensor);
      });
    },

    startSimulation: () => {
      const { engine, loadSceneToEngine } = get();
      if (engine) {
        loadSceneToEngine();
        engine.start();
        set({ isRunning: true, isPaused: false });
      }
    },

    pauseSimulation: () => {
      const { engine } = get();
      if (engine) {
        engine.pause();
        set({ isPaused: true });
      }
    },

    resumeSimulation: () => {
      const { engine } = get();
      if (engine) {
        engine.resume();
        set({ isPaused: false });
      }
    },

    stopSimulation: () => {
      const { engine } = get();
      if (engine) {
        engine.stop();
        set({ isRunning: false, isPaused: false, currentTime: 0 });
      }
    },

    resetSimulation: () => {
      const { engine, loadSceneToEngine } = get();
      if (engine) {
        engine.reset();
        loadSceneToEngine();
        set({ 
          isRunning: false, 
          isPaused: false, 
          currentTime: 0,
          lastStepResult: null,
          sensorData: new Map(),
        });
      }
    },

    stepSimulation: (dt?: number) => {
      const { engine } = get();
      if (engine) {
        const result = engine.step(dt);
        const uiState = useUIStore.getState();
        
        set({
          currentTime: result.time,
          lastStepResult: result,
          sensorData: result.sensorData,
        });

        if (result.errors.length > 0) {
          result.errors.forEach((err) => uiState.addError(err));
        }
      }
    },

    setSpeed: (speed: number) => {
      const { engine } = get();
      if (engine) {
        engine.setSpeed(speed);
        set({ speed });
      }
    },

    setConfig: (config: Partial<SimulationConfig>) => {
      set((state) => ({
        config: { ...state.config, ...config },
      }));
    },
  };
});

export const selectIsSimulationActive = (state: SimulationState) => state.isRunning && !state.isPaused;
export const selectProgress = (state: SimulationState) => 
  state.config.endTime > 0 ? (state.currentTime / state.config.endTime) * 100 : 0;
export const selectSensorDataArray = (state: SimulationState) => Array.from(state.sensorData.entries());
