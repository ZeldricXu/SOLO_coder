import { create } from 'zustand';
import { PhysicsObject, Sensor, Scene, Vec3, vec3, generateId } from '@physics-sim/shared';

export interface SceneState {
  scene: Scene | null;
  objects: Map<string, PhysicsObject>;
  sensors: Map<string, Sensor>;
  gravity: Vec3;
  
  setScene: (scene: Scene) => void;
  addObject: (obj: PhysicsObject, initialVelocity?: Vec3, initialAngularVelocity?: Vec3) => void;
  removeObject: (id: string) => void;
  updateObject: (id: string, updates: Partial<PhysicsObject>) => void;
  addSensor: (sensor: Sensor) => void;
  removeSensor: (id: string) => void;
  updateSensor: (id: string, updates: Partial<Sensor>) => void;
  setGravity: (gravity: Vec3) => void;
  clearScene: () => void;
}

export const useSceneStore = create<SceneState>((set, get) => ({
  scene: null,
  objects: new Map(),
  sensors: new Map(),
  gravity: vec3(0, -9.81, 0),

  setScene: (scene: Scene) => {
    const objects = Array.isArray(scene.objects) 
      ? new Map(scene.objects.map((o: any) => [o.id, o]))
      : scene.objects;
    const sensors = Array.isArray(scene.sensors)
      ? new Map(scene.sensors.map((s: any) => [s.id, s]))
      : new Map(scene.sensors);
    
    set({
      scene,
      objects: new Map(objects),
      sensors: new Map(sensors),
      gravity: scene.simulationConfig?.mechanics?.gravity || vec3(0, -9.81, 0),
    });
  },

  addObject: (obj: PhysicsObject, initialVelocity = vec3(0, 0, 0), initialAngularVelocity = vec3(0, 0, 0)) => {
    const newObj = {
      ...obj,
      id: obj.id || generateId(),
      velocity: initialVelocity,
      angularVelocity: initialAngularVelocity,
    };
    set((state) => ({
      objects: new Map(state.objects).set(newObj.id, newObj),
    }));
  },

  removeObject: (id: string) => {
    set((state) => {
      const newObjects = new Map(state.objects);
      newObjects.delete(id);
      return { objects: newObjects };
    });
  },

  updateObject: (id: string, updates: Partial<PhysicsObject>) => {
    set((state) => {
      const obj = state.objects.get(id);
      if (!obj) return state;
      const newObjects = new Map(state.objects);
      newObjects.set(id, { ...obj, ...updates } as PhysicsObject);
      return { objects: newObjects };
    });
  },

  addSensor: (sensor: Sensor) => {
    const newSensor = {
      ...sensor,
      id: sensor.id || generateId(),
    };
    set((state) => ({
      sensors: new Map(state.sensors).set(newSensor.id, newSensor),
    }));
  },

  removeSensor: (id: string) => {
    set((state) => {
      const newSensors = new Map(state.sensors);
      newSensors.delete(id);
      return { sensors: newSensors };
    });
  },

  updateSensor: (id: string, updates: Partial<Sensor>) => {
    set((state) => {
      const sensor = state.sensors.get(id);
      if (!sensor) return state;
      const newSensors = new Map(state.sensors);
      newSensors.set(id, { ...sensor, ...updates });
      return { sensors: newSensors };
    });
  },

  setGravity: (gravity: Vec3) => {
    set({ gravity: { ...gravity } });
  },

  clearScene: () => {
    set({
      scene: null,
      objects: new Map(),
      sensors: new Map(),
      gravity: vec3(0, -9.81, 0),
    });
  },
}));

export const selectObjectIds = (state: SceneState) => Array.from(state.objects.keys());
export const selectObjectArray = (state: SceneState) => Array.from(state.objects.values());
export const selectSensorArray = (state: SceneState) => Array.from(state.sensors.values());
export const selectObjectById = (id: string) => (state: SceneState) => state.objects.get(id);
export const selectSensorById = (id: string) => (state: SceneState) => state.sensors.get(id);
