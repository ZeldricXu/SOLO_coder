import { Vec3, vec3, PhysicsObject, Sensor, Field, ScalarField, VectorField } from '@physics-sim/shared';
import { 
  SimulationConfig, 
  SimulationState, 
  SimulationStepResult,
  DEFAULT_SIMULATION_CONFIG
} from '@physics-sim/shared';
import { MechanicsSolver, MechanicsStepResult, MechanicsSolverConfig } from './mechanicsSolver';
import { ElectromagneticsSolver, FieldSolverResult } from './electromagneticsSolver';
import { ThermodynamicsSolver, ThermalStepResult } from './thermodynamicsSolver';
import { RigidBodyState } from './types';
import { generateId } from '@physics-sim/shared';

export interface EngineConfig {
  simulationConfig: Partial<SimulationConfig>;
  enableMechanics: boolean;
  enableElectromagnetics: boolean;
  enableThermodynamics: boolean;
  couplingEnabled: boolean;
}

export interface EngineStepResult {
  time: number;
  mechanics?: MechanicsStepResult;
  electromagnetics?: FieldSolverResult;
  thermodynamics?: ThermalStepResult;
  sensorData: Map<string, { time: number; value: number | Vec3 }[]>;
  totalSolveTime: number;
  errors: string[];
}

export class SimulationEngine {
  private config: EngineConfig;
  private simulationConfig: SimulationConfig;
  private mechanicsSolver?: MechanicsSolver;
  private electromagneticsSolver?: ElectromagneticsSolver;
  private thermodynamicsSolver?: ThermodynamicsSolver;
  private state: SimulationState;
  private sensorData: Map<string, { time: number; value: number | Vec3 }[]>;
  private errors: string[];
  private isRunning: boolean;
  private isPaused: boolean;
  private speed: number;

  constructor(config: Partial<EngineConfig> = {}) {
    this.config = {
      enableMechanics: true,
      enableElectromagnetics: false,
      enableThermodynamics: false,
      couplingEnabled: true,
      simulationConfig: {},
      ...config,
    };
    
    this.simulationConfig = {
      ...DEFAULT_SIMULATION_CONFIG,
      ...this.config.simulationConfig,
    };
    
    this.sensorData = new Map();
    this.errors = [];
    this.isRunning = false;
    this.isPaused = false;
    this.speed = this.simulationConfig.speed;
    
    this.state = this.createInitialState();
    this.initializeSolvers();
  }

  private createInitialState(): SimulationState {
    return {
      time: this.simulationConfig.startTime,
      timeStep: this.simulationConfig.mechanics.timeStep,
      isRunning: false,
      isPaused: false,
      speed: this.speed,
      objects: new Map(),
      sensors: new Map(),
      fields: new Map(),
      iteration: 0,
    };
  }

  private initializeSolvers(): void {
    if (this.config.enableMechanics) {
      const mechConfig = this.simulationConfig.mechanics;
      this.mechanicsSolver = new MechanicsSolver({
        gravity: mechConfig.gravity,
        dt: mechConfig.timeStep,
        substeps: mechConfig.collisionIterations,
        solverIterations: mechConfig.constraintIterations,
        baumgarte: mechConfig.baumgarteStabilization,
        usePBD: true,
        useVerlet: true,
        adaptiveStepSize: mechConfig.useAdaptiveStep,
        tolerance: mechConfig.tolerance,
        minDt: mechConfig.minTimeStep,
        maxDt: mechConfig.maxTimeStep,
      });
    }
    
    if (this.config.enableElectromagnetics) {
      const emConfig = this.simulationConfig.electromagnetics;
      this.electromagneticsSolver = new ElectromagneticsSolver({
        resolution: emConfig.gridResolution,
        maxIterations: emConfig.maxIterations,
        tolerance: emConfig.tolerance,
        relaxationFactor: emConfig.relaxationFactor,
      });
    }
    
    if (this.config.enableThermodynamics) {
      const thermoConfig = this.simulationConfig.thermodynamics;
      this.thermodynamicsSolver = new ThermodynamicsSolver({
        dt: thermoConfig.timeStep,
        maxIterations: thermoConfig.maxIterations,
        tolerance: thermoConfig.tolerance,
      });
    }
  }

  addPhysicsObject(obj: PhysicsObject, initialVelocity: Vec3 = vec3(0, 0, 0), initialAngularVelocity: Vec3 = vec3(0, 0, 0)): void {
    this.state.objects.set(obj.id, obj);
    
    if (this.mechanicsSolver) {
      this.mechanicsSolver.addPhysicsObject(obj, initialVelocity, initialAngularVelocity);
    }
  }

  removePhysicsObject(id: string): boolean {
    const removed = this.state.objects.delete(id);
    if (removed && this.mechanicsSolver) {
      this.mechanicsSolver.removeBody(id);
    }
    return removed;
  }

  addSensor(sensor: Sensor): void {
    this.state.sensors.set(sensor.id, sensor);
    if (!this.sensorData.has(sensor.id)) {
      this.sensorData.set(sensor.id, []);
    }
  }

  removeSensor(id: string): boolean {
    const removed = this.state.sensors.delete(id);
    if (removed) {
      this.sensorData.delete(id);
    }
    return removed;
  }

  step(dt?: number): EngineStepResult {
    const startTime = performance.now();
    this.errors = [];
    
    const actualDt = dt || this.state.timeStep;
    const scaledDt = actualDt * this.speed;
    
    let mechanicsResult: MechanicsStepResult | undefined;
    let electromagneticsResult: FieldSolverResult | undefined;
    let thermodynamicsResult: ThermalStepResult | undefined;
    
    try {
      if (this.mechanicsSolver && this.simulationConfig.mechanics.enabled) {
        mechanicsResult = this.mechanicsSolver.step(scaledDt);
        this.updatePhysicsObjectsFromBodies(mechanicsResult.bodies);
      }
      
      if (this.electromagneticsSolver && this.simulationConfig.electromagnetics.enabled) {
        electromagneticsResult = this.electromagneticsSolver.solveElectrostatic(this.state.time);
        this.state.fields.set(electromagneticsResult.field.id, electromagneticsResult.field);
        if (electromagneticsResult.potential) {
          this.state.fields.set(electromagneticsResult.potential.id, electromagneticsResult.potential);
        }
        
        if (this.config.couplingEnabled && this.mechanicsSolver) {
          this.applyElectromagneticForces(electromagneticsResult.field);
        }
      }
      
      if (this.thermodynamicsSolver && this.simulationConfig.thermodynamics.enabled) {
        thermodynamicsResult = this.thermodynamicsSolver.step(scaledDt);
        this.state.fields.set(thermodynamicsResult.temperature.id, thermodynamicsResult.temperature);
        
        if (this.config.couplingEnabled && this.mechanicsSolver) {
          this.applyThermalExpansion(thermodynamicsResult.temperature);
        }
      }
      
      this.collectSensorData();
      
      this.state.time += scaledDt;
      this.state.iteration++;
      this.state.timeStep = actualDt;
      
    } catch (error) {
      this.errors.push(`Simulation error: ${error}`);
    }
    
    const endTime = performance.now();
    
    return {
      time: this.state.time,
      mechanics: mechanicsResult,
      electromagnetics: electromagneticsResult,
      thermodynamics: thermodynamicsResult,
      sensorData: new Map(this.sensorData),
      totalSolveTime: endTime - startTime,
      errors: [...this.errors],
    };
  }

  private updatePhysicsObjectsFromBodies(bodies: Map<string, RigidBodyState>): void {
    bodies.forEach((body, id) => {
      const obj = this.state.objects.get(id);
      if (obj) {
        obj.position = { ...body.position };
        obj.rotation = { ...body.rotation };
        if (obj.type !== 'joint' && !obj.isStatic) {
          (obj as any).mechanics = (obj as any).mechanics || {};
          (obj as any).mechanics.velocity = { ...body.velocity };
          (obj as any).mechanics.angularVelocity = { ...body.angularVelocity };
        }
      }
    });
  }

  private applyElectromagneticForces(field: VectorField): void {
    if (!this.mechanicsSolver || !this.electromagneticsSolver) return;
    
    const EM_CHARGE_PROPERTY = 'charge';
    
    this.state.objects.forEach((obj, id) => {
      const charge = (obj as any)[EM_CHARGE_PROPERTY] as number | undefined;
      if (charge !== undefined && charge !== 0) {
        const body = this.mechanicsSolver?.getBody(id);
        if (body && !body.isStatic) {
          const eField = this.electromagneticsSolver!.getFieldAtPosition(field, obj.position);
          const force = {
            x: charge * eField.x,
            y: charge * eField.y,
            z: charge * eField.z,
          };
          this.mechanicsSolver?.applyForce(id, force, obj.position);
        }
      }
    });
  }

  private applyThermalExpansion(temperature: ScalarField): void {
    if (!this.mechanicsSolver || !this.thermodynamicsSolver) return;
    
    const THERMAL_EXPANSION_COEFFICIENT = 1e-5;
    
    this.state.objects.forEach((obj, id) => {
      const body = this.mechanicsSolver?.getBody(id);
      if (body && !body.isStatic) {
        const temp = this.thermodynamicsSolver!.getTemperatureAtPosition(obj.position);
        const deltaT = temp - 300;
        const expansionFactor = 1 + THERMAL_EXPANSION_COEFFICIENT * deltaT;
        
        if ('size' in obj && obj.size) {
          obj.size = {
            x: (obj.size as Vec3).x * expansionFactor,
            y: (obj.size as Vec3).y * expansionFactor,
            z: (obj.size as Vec3).z * expansionFactor,
          };
        }
      }
    });
  }

  private collectSensorData(): void {
    this.state.sensors.forEach((sensor, id) => {
      const value = this.readSensor(sensor);
      if (value !== null) {
        const data = this.sensorData.get(id);
        if (data) {
          data.push({ time: this.state.time, value });
          if (data.length > 10000) {
            data.shift();
          }
        }
      }
    });
  }

  private readSensor(sensor: Sensor): number | Vec3 | null {
    const position = sensor.position;
    
    switch (sensor.type) {
      case 'displacement':
        return { ...position };
      
      case 'velocity':
        if (sensor.targetObjectId) {
          const body = this.mechanicsSolver?.getBody(sensor.targetObjectId);
          if (body) return { ...body.velocity };
        }
        return vec3(0, 0, 0);
      
      case 'acceleration':
        if (sensor.targetObjectId) {
          const body = this.mechanicsSolver?.getBody(sensor.targetObjectId);
          if (body) {
            return {
              x: body.force.x / body.mass,
              y: body.force.y / body.mass,
              z: body.force.z / body.mass,
            };
          }
        }
        return vec3(0, 0, 0);
      
      case 'force':
        if (sensor.targetObjectId) {
          const body = this.mechanicsSolver?.getBody(sensor.targetObjectId);
          if (body) return { ...body.force };
        }
        return vec3(0, 0, 0);
      
      case 'temperature':
        if (this.thermodynamicsSolver) {
          return this.thermodynamicsSolver.getTemperatureAtPosition(position);
        }
        return 300;
      
      case 'electricField':
        if (this.electromagneticsSolver) {
          for (const field of this.state.fields.values()) {
            if (field.type === 'electric' && 'dataX' in field) {
              return this.electromagneticsSolver.getFieldAtPosition(field, position);
            }
          }
        }
        return vec3(0, 0, 0);
      
      case 'magneticField':
        if (this.electromagneticsSolver) {
          for (const field of this.state.fields.values()) {
            if (field.type === 'magnetic' && 'dataX' in field) {
              return this.electromagneticsSolver.getFieldAtPosition(field, position);
            }
          }
        }
        return vec3(0, 0, 0);
      
      default:
        return null;
    }
  }

  start(): void {
    this.isRunning = true;
    this.isPaused = false;
    this.state.isRunning = true;
    this.state.isPaused = false;
  }

  pause(): void {
    this.isPaused = true;
    this.state.isPaused = true;
  }

  resume(): void {
    this.isPaused = false;
    this.state.isPaused = false;
  }

  stop(): void {
    this.isRunning = false;
    this.isPaused = false;
    this.state.isRunning = false;
    this.state.isPaused = false;
  }

  reset(): void {
    this.stop();
    this.state = this.createInitialState();
    this.sensorData.clear();
    this.errors = [];
    
    if (this.mechanicsSolver) {
      this.mechanicsSolver.reset();
    }
    if (this.electromagneticsSolver) {
      this.electromagneticsSolver.reset();
    }
    if (this.thermodynamicsSolver) {
      this.thermodynamicsSolver.reset();
    }
  }

  setSpeed(speed: number): void {
    this.speed = Math.max(0.1, Math.min(10, speed));
    this.state.speed = this.speed;
  }

  getSpeed(): number {
    return this.speed;
  }

  getState(): SimulationState {
    return {
      ...this.state,
      objects: new Map(this.state.objects),
      sensors: new Map(this.state.sensors),
      fields: new Map(this.state.fields),
    };
  }

  getSensorData(): Map<string, { time: number; value: number | Vec3 }[]> {
    return new Map(this.sensorData);
  }

  getSensorDataById(sensorId: string): { time: number; value: number | Vec3 }[] {
    return [...(this.sensorData.get(sensorId) || [])];
  }

  getMechanicsSolver(): MechanicsSolver | undefined {
    return this.mechanicsSolver;
  }

  getElectromagneticsSolver(): ElectromagneticsSolver | undefined {
    return this.electromagneticsSolver;
  }

  getThermodynamicsSolver(): ThermodynamicsSolver | undefined {
    return this.thermodynamicsSolver;
  }

  setSimulationConfig(config: Partial<SimulationConfig>): void {
    this.simulationConfig = { ...this.simulationConfig, ...config };
    this.speed = this.simulationConfig.speed;
    this.state.speed = this.speed;
  }

  getSimulationConfig(): SimulationConfig {
    return { ...this.simulationConfig };
  }

  setEngineConfig(config: Partial<EngineConfig>): void {
    const needsReinitialization = 
      config.enableMechanics !== this.config.enableMechanics ||
      config.enableElectromagnetics !== this.config.enableElectromagnetics ||
      config.enableThermodynamics !== this.config.enableThermodynamics;
    
    this.config = { ...this.config, ...config };
    
    if (needsReinitialization) {
      this.initializeSolvers();
    }
  }

  getEngineConfig(): EngineConfig {
    return { ...this.config };
  }

  getErrors(): string[] {
    return [...this.errors];
  }

  isEngineRunning(): boolean {
    return this.isRunning && !this.isPaused;
  }

  isEnginePaused(): boolean {
    return this.isPaused;
  }

  getComputationalComplexity(): number {
    let complexity = 0;
    
    if (this.mechanicsSolver && this.simulationConfig.mechanics.enabled) {
      const bodyCount = this.state.objects.size;
      complexity += bodyCount * bodyCount * 0.1;
    }
    
    if (this.electromagneticsSolver && this.simulationConfig.electromagnetics.enabled) {
      const res = this.simulationConfig.electromagnetics.gridResolution;
      complexity += res.x * res.y * res.z;
    }
    
    if (this.thermodynamicsSolver && this.simulationConfig.thermodynamics.enabled) {
      const res = this.thermodynamicsSolver.getConfig().resolution;
      complexity += res.x * res.y * res.z;
    }
    
    return complexity;
  }

  shouldOffloadToBackend(): boolean {
    if (!this.simulationConfig.useBackendComputation) return false;
    return this.getComputationalComplexity() > this.simulationConfig.computationThreshold;
  }
}

export const SimulationEngineOps = {
  SimulationEngine,
  DEFAULT_SIMULATION_CONFIG,
};
