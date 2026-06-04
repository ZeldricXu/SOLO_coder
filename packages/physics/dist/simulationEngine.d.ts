import { Vec3, PhysicsObject, Sensor } from '@physics-sim/shared';
import { SimulationConfig, SimulationState } from '@physics-sim/shared';
import { MechanicsSolver, MechanicsStepResult } from './mechanicsSolver';
import { ElectromagneticsSolver, FieldSolverResult } from './electromagneticsSolver';
import { ThermodynamicsSolver, ThermalStepResult } from './thermodynamicsSolver';
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
    sensorData: Map<string, {
        time: number;
        value: number | Vec3;
    }[]>;
    totalSolveTime: number;
    errors: string[];
}
export declare class SimulationEngine {
    private config;
    private simulationConfig;
    private mechanicsSolver?;
    private electromagneticsSolver?;
    private thermodynamicsSolver?;
    private state;
    private sensorData;
    private errors;
    private isRunning;
    private isPaused;
    private speed;
    constructor(config?: Partial<EngineConfig>);
    private createInitialState;
    private initializeSolvers;
    addPhysicsObject(obj: PhysicsObject, initialVelocity?: Vec3, initialAngularVelocity?: Vec3): void;
    removePhysicsObject(id: string): boolean;
    addSensor(sensor: Sensor): void;
    removeSensor(id: string): boolean;
    step(dt?: number): EngineStepResult;
    private updatePhysicsObjectsFromBodies;
    private applyElectromagneticForces;
    private applyThermalExpansion;
    private collectSensorData;
    private readSensor;
    start(): void;
    pause(): void;
    resume(): void;
    stop(): void;
    reset(): void;
    setSpeed(speed: number): void;
    getSpeed(): number;
    getState(): SimulationState;
    getSensorData(): Map<string, {
        time: number;
        value: number | Vec3;
    }[]>;
    getSensorDataById(sensorId: string): {
        time: number;
        value: number | Vec3;
    }[];
    getMechanicsSolver(): MechanicsSolver | undefined;
    getElectromagneticsSolver(): ElectromagneticsSolver | undefined;
    getThermodynamicsSolver(): ThermodynamicsSolver | undefined;
    setSimulationConfig(config: Partial<SimulationConfig>): void;
    getSimulationConfig(): SimulationConfig;
    setEngineConfig(config: Partial<EngineConfig>): void;
    getEngineConfig(): EngineConfig;
    getErrors(): string[];
    isEngineRunning(): boolean;
    isEnginePaused(): boolean;
    getComputationalComplexity(): number;
    shouldOffloadToBackend(): boolean;
}
export declare const SimulationEngineOps: {
    SimulationEngine: typeof SimulationEngine;
    DEFAULT_SIMULATION_CONFIG: SimulationConfig;
};
//# sourceMappingURL=simulationEngine.d.ts.map