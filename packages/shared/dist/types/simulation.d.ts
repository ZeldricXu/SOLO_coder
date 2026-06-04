import { PhysicsObject } from './physicsObjects';
import { Sensor } from './sensors';
import { Vec3 } from './vectors';
import { Field } from './fields';
export type SolverType = 'mechanics' | 'electromagnetics' | 'thermodynamics' | 'fluiddynamics';
export interface SolverConfig {
    type: SolverType;
    enabled: boolean;
    gravity: Vec3;
    timeStep: number;
    maxTimeStep: number;
    minTimeStep: number;
    tolerance: number;
    maxIterations: number;
    useAdaptiveStep: boolean;
    useGPU: boolean;
}
export interface MechanicsSolverConfig extends SolverConfig {
    type: 'mechanics';
    gravity: Vec3;
    airResistance: number;
    collisionIterations: number;
    constraintIterations: number;
    enableWarmStarting: boolean;
    baumgarteStabilization: number;
}
export interface ElectromagneticsSolverConfig extends SolverConfig {
    type: 'electromagnetics';
    gridResolution: Vec3;
    boundaryConditions: Record<string, number>;
    maxIterations: number;
    relaxationFactor: number;
}
export interface ThermodynamicsSolverConfig extends SolverConfig {
    type: 'thermodynamics';
    ambientTemperature: number;
    heatTransferCoefficient: number;
    radiationEnabled: boolean;
    stefanBoltzmannConstant: number;
}
export interface FluidDynamicsSolverConfig extends SolverConfig {
    type: 'fluiddynamics';
    gridResolution: Vec3;
    viscosity: number;
    inletVelocity: Vec3;
    boundaryType: 'bounce-back' | 'velocity-inlet';
    relaxationTime: number;
}
export interface LBMBoundaryCondition {
    type: 'bounce-back' | 'velocity-inlet' | 'pressure-outlet' | 'open';
    velocity?: Vec3;
    pressure?: number;
}
export interface FluidObstacle {
    id: string;
    vertices: Vec3[];
}
export interface ParameterSweepConfig {
    id: string;
    parameterName: string;
    parameterPath: string;
    startValue: number;
    endValue: number;
    stepSize: number;
    scene: any;
    baseConfig: SimpleSimulationConfig;
    resultExtractor: string;
}
export interface ParameterSweepResult {
    sweepId: string;
    parameterValues: number[];
    results: number[];
    status: 'running' | 'completed' | 'failed';
    progress: number;
    totalJobs: number;
    completedJobs: number;
    errors: string[];
}
export type SimulationState = {
    time: number;
    timeStep: number;
    isRunning: boolean;
    isPaused: boolean;
    speed: number;
    objects: Map<string, PhysicsObject>;
    sensors: Map<string, Sensor>;
    fields: Map<string, Field>;
    iteration: number;
};
export interface SimulationStepResult {
    state: SimulationState;
    sensorData: Map<string, {
        time: number;
        value: number | Vec3;
    }[]>;
    errors: string[];
    performance: {
        solveTime: number;
        collisionTime: number;
        constraintTime: number;
    };
}
export interface SimulationConfig {
    id: string;
    name: string;
    startTime: number;
    endTime: number;
    speed: number;
    mechanics: MechanicsSolverConfig;
    electromagnetics: ElectromagneticsSolverConfig;
    thermodynamics: ThermodynamicsSolverConfig;
    useBackendComputation: boolean;
    computationThreshold: number;
}
export interface SimulationResult {
    jobId?: string;
    success: boolean;
    frames: {
        time: number;
        objects: {
            id: string;
            position: any;
            rotation: any;
            velocity: any;
            angularVelocity: any;
        }[];
    }[];
    sensorData: Record<string, {
        time: number;
        value: any;
    }[]>;
    statistics?: {
        totalTime: number;
        timeStep: number;
        totalSteps: number;
        framesRendered: number;
        computationTime: number;
        stepsPerSecond: number;
        realTimeFactor: number;
    };
    error?: string;
}
export interface SimpleScene {
    id: string;
    name: string;
    objects: Map<string, any> | any[];
    sensors: any[];
    gravity: any;
}
export interface SimpleSimulationConfig {
    duration: number;
    timeStep: number;
    physicsTypes: string[];
    solverType?: string;
    collisionIterations?: number;
    constraintIterations?: number;
    gridResolution?: number;
    use3D?: boolean;
    supportsGPU?: boolean;
}
export interface GPUInfo {
    available: boolean;
    count: number;
    devices: {
        id: number;
        name: string;
        memoryTotal: number;
        memoryFree: number;
    }[];
    cudaVersion?: string;
}
export declare const DEFAULT_SIMULATION_CONFIG: SimulationConfig;
//# sourceMappingURL=simulation.d.ts.map