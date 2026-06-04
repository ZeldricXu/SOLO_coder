import { Vec3 } from '@physics-sim/shared';
import { ScalarField as ScalarFieldType, FieldGrid, BoundaryCondition } from '@physics-sim/shared';
import { UniformGrid, ScalarField, VectorField } from '@physics-sim/math';
export interface HeatSource {
    position: Vec3;
    power: number;
    radius: number;
    id: string;
}
export interface ThermalBody {
    position: Vec3;
    size: Vec3;
    materialId: string;
    initialTemperature: number;
    id: string;
}
export interface ThermalConfig {
    dimensions: Vec3;
    resolution: Vec3;
    origin: Vec3;
    dt: number;
    maxIterations: number;
    tolerance: number;
    use3D: boolean;
}
export declare const DEFAULT_THERMAL_CONFIG: ThermalConfig;
export interface ThermalStepResult {
    temperature: ScalarFieldType;
    heatFlux?: VectorField;
    iterations: number;
    residual: number;
    solveTime: number;
}
export declare class ThermodynamicsSolver {
    private config;
    private uniformGrid;
    private grid;
    private temperatureField;
    private conductivityField;
    private densityField;
    private specificHeatField;
    private heatSources;
    private thermalBodies;
    private boundaryConditions;
    private time;
    constructor(config?: Partial<ThermalConfig>);
    private createUniformGrid;
    private createLegacyGrid;
    private getGridSize;
    private initializeMaterialProperties;
    private isPointInBox;
    addHeatSource(position: Vec3, power: number, radius?: number): HeatSource;
    removeHeatSource(id: string): boolean;
    addThermalBody(position: Vec3, size: Vec3, materialId: string, initialTemperature?: number): ThermalBody;
    removeThermalBody(id: string): boolean;
    setBoundaryConditions(conditions: BoundaryCondition[]): void;
    setInitialTemperature(temperature: number): void;
    private applyInitialTemperatures;
    step(dt?: number): ThermalStepResult;
    private computeHeatSourceTerm;
    private solveCrankNicolson;
    private applyBoundaryConditions;
    private applyNeumannCondition;
    private computeHeatFlux;
    getTemperatureAtPosition(position: Vec3): number;
    private worldToGrid;
    private gridToWorld;
    getTemperatureField(time?: number): ScalarFieldType;
    getTemperatureFieldData(): ScalarField;
    getUniformGrid(): UniformGrid;
    getGrid(): FieldGrid;
    getTime(): number;
    setConfig(config: Partial<ThermalConfig>): void;
    getConfig(): ThermalConfig;
    reset(): void;
}
export declare const ThermodynamicsSolverOps: {
    ThermodynamicsSolver: typeof ThermodynamicsSolver;
    DEFAULT_THERMAL_CONFIG: ThermalConfig;
};
//# sourceMappingURL=thermodynamicsSolver.d.ts.map