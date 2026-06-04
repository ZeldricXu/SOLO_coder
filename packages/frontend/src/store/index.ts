export * from './sceneStore';
export * from './simulationStore';
export * from './uiStore';

import { useSceneStore } from './sceneStore';
import { useSimulationStore } from './simulationStore';
import { useUIStore } from './uiStore';
import type { PhysicsObject, Sensor, Vec3, Scene } from '@physics-sim/shared';
import type { EngineStepResult } from '@physics-sim/physics';

interface CompatibleSimulationStore {
  engine: ReturnType<typeof useSimulationStore.getState>['engine'];
  scene: Scene | null;
  isRunning: boolean;
  isPaused: boolean;
  currentTime: number;
  speed: number;
  selectedObjectId: string | null;
  showGrid: boolean;
  showAxes: boolean;
  showForces: boolean;
  showTrajectories: boolean;
  cameraPosition: Vec3;
  cameraTarget: Vec3;
  lastStepResult: EngineStepResult | null;
  sensorData: Map<string, { time: number; value: number | Vec3 }[]>;
  errors: string[];
  
  initEngine: () => void;
  startSimulation: () => void;
  pauseSimulation: () => void;
  resumeSimulation: () => void;
  stopSimulation: () => void;
  resetSimulation: () => void;
  stepSimulation: (dt?: number) => void;
  setSpeed: (speed: number) => void;
  addPhysicsObject: (obj: PhysicsObject, initialVelocity?: Vec3, initialAngularVelocity?: Vec3) => void;
  removePhysicsObject: (id: string) => void;
  addSensor: (sensor: Sensor) => void;
  removeSensor: (id: string) => void;
  selectObject: (id: string | null) => void;
  setCameraPosition: (pos: Vec3) => void;
  setCameraTarget: (target: Vec3) => void;
  toggleGrid: () => void;
  toggleAxes: () => void;
  toggleForces: () => void;
  toggleTrajectories: () => void;
  loadScene: (scene: Scene) => void;
  clearErrors: () => void;
}

export function useLegacySimulationStore(): CompatibleSimulationStore {
  const sceneState = useSceneStore();
  const simState = useSimulationStore();
  const uiState = useUIStore();

  return {
    engine: simState.engine,
    scene: sceneState.scene,
    isRunning: simState.isRunning,
    isPaused: simState.isPaused,
    currentTime: simState.currentTime,
    speed: simState.speed,
    selectedObjectId: uiState.selectedObjectId,
    showGrid: uiState.showGrid,
    showAxes: uiState.showAxes,
    showForces: uiState.showForces,
    showTrajectories: uiState.showTrajectories,
    cameraPosition: uiState.cameraPosition,
    cameraTarget: uiState.cameraTarget,
    lastStepResult: simState.lastStepResult,
    sensorData: simState.sensorData,
    errors: uiState.errors,

    initEngine: simState.initEngine,
    startSimulation: simState.startSimulation,
    pauseSimulation: simState.pauseSimulation,
    resumeSimulation: simState.resumeSimulation,
    stopSimulation: simState.stopSimulation,
    resetSimulation: simState.resetSimulation,
    stepSimulation: simState.stepSimulation,
    setSpeed: simState.setSpeed,
    addPhysicsObject: sceneState.addObject,
    removePhysicsObject: (id: string) => {
      sceneState.removeObject(id);
      if (uiState.selectedObjectId === id) {
        uiState.selectObject(null);
      }
    },
    addSensor: sceneState.addSensor,
    removeSensor: sceneState.removeSensor,
    selectObject: uiState.selectObject,
    setCameraPosition: uiState.setCameraPosition,
    setCameraTarget: uiState.setCameraTarget,
    toggleGrid: uiState.toggleGrid,
    toggleAxes: uiState.toggleAxes,
    toggleForces: uiState.toggleForces,
    toggleTrajectories: uiState.toggleTrajectories,
    loadScene: sceneState.setScene,
    clearErrors: uiState.clearErrors,
  };
}

export function getLegacyState(): CompatibleSimulationStore {
  const sceneState = useSceneStore.getState();
  const simState = useSimulationStore.getState();
  const uiState = useUIStore.getState();

  return {
    engine: simState.engine,
    scene: sceneState.scene,
    isRunning: simState.isRunning,
    isPaused: simState.isPaused,
    currentTime: simState.currentTime,
    speed: simState.speed,
    selectedObjectId: uiState.selectedObjectId,
    showGrid: uiState.showGrid,
    showAxes: uiState.showAxes,
    showForces: uiState.showForces,
    showTrajectories: uiState.showTrajectories,
    cameraPosition: uiState.cameraPosition,
    cameraTarget: uiState.cameraTarget,
    lastStepResult: simState.lastStepResult,
    sensorData: simState.sensorData,
    errors: uiState.errors,

    initEngine: simState.initEngine,
    startSimulation: simState.startSimulation,
    pauseSimulation: simState.pauseSimulation,
    resumeSimulation: simState.resumeSimulation,
    stopSimulation: simState.stopSimulation,
    resetSimulation: simState.resetSimulation,
    stepSimulation: simState.stepSimulation,
    setSpeed: simState.setSpeed,
    addPhysicsObject: sceneState.addObject,
    removePhysicsObject: (id: string) => {
      sceneState.removeObject(id);
      if (uiState.selectedObjectId === id) {
        uiState.selectObject(null);
      }
    },
    addSensor: sceneState.addSensor,
    removeSensor: sceneState.removeSensor,
    selectObject: uiState.selectObject,
    setCameraPosition: uiState.setCameraPosition,
    setCameraTarget: uiState.setCameraTarget,
    toggleGrid: uiState.toggleGrid,
    toggleAxes: uiState.toggleAxes,
    toggleForces: uiState.toggleForces,
    toggleTrajectories: uiState.toggleTrajectories,
    loadScene: sceneState.setScene,
    clearErrors: uiState.clearErrors,
  };
}
