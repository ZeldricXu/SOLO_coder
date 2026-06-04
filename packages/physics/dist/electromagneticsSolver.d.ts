import { Vec3 } from '@physics-sim/shared';
import { ScalarField as ScalarFieldType, VectorField as VectorFieldType, FieldGrid, BoundaryCondition } from '@physics-sim/shared';
import { UniformGrid, ScalarField, VectorField } from '@physics-sim/math';
export interface Charge {
    position: Vec3;
    charge: number;
    id: string;
}
export interface Current {
    position: Vec3;
    direction: Vec3;
    magnitude: number;
    id: string;
}
export interface Magnet {
    position: Vec3;
    moment: Vec3;
    id: string;
}
export interface FDMConfig {
    dimensions: Vec3;
    resolution: Vec3;
    origin: Vec3;
    maxIterations: number;
    tolerance: number;
    relaxationFactor: number;
    use3D: boolean;
    solver: 'gauss-seidel' | 'cg' | 'cg-multigrid';
    useMultigrid: boolean;
    multigridLevels: number;
}
export declare const DEFAULT_FDM_CONFIG: FDMConfig;
export interface FieldSolverResult {
    potential?: ScalarFieldType;
    field: VectorFieldType;
    iterations: number;
    residual: number;
    solveTime: number;
    converged: boolean;
    solver: string;
}
export declare class ElectromagneticsSolver {
    private config;
    private uniformGrid;
    private grid;
    private charges;
    private currents;
    private magnets;
    private boundaryConditions;
    private lastPotentialField;
    private lastElectricField;
    constructor(config?: Partial<FDMConfig>);
    private createUniformGrid;
    private createLegacyGrid;
    addCharge(position: Vec3, charge: number): Charge;
    removeCharge(id: string): boolean;
    addCurrent(position: Vec3, direction: Vec3, magnitude: number): Current;
    removeCurrent(id: string): boolean;
    addMagnet(position: Vec3, moment: Vec3): Magnet;
    removeMagnet(id: string): boolean;
    setBoundaryCondition(fieldName: string, conditions: BoundaryCondition[]): void;
    solveElectrostatic(time?: number): FieldSolverResult;
    private toUnifiedVectorField;
    getPotentialFieldData(): ScalarField;
    getElectricFieldData(): VectorField;
    getTotalCharge(): number;
    solveMagnetostatic(time?: number): FieldSolverResult;
    private toLegacyVectorField;
    private buildChargeDensity;
    private buildCurrentDensity;
    private applyPotentialBoundaryConditions;
    private applyFaceBoundaryCondition;
    private solvePoissonEquation;
    private isInRegion;
    private computeElectricField;
    private computeMagneticField;
    private addMagneticDipoleField;
    getFieldAtPosition(field: VectorFieldType, position: Vec3): Vec3;
    private worldToGrid;
    private gridToWorld;
    getUniformGrid(): UniformGrid;
    getGrid(): FieldGrid;
    setConfig(config: Partial<FDMConfig>): void;
    getConfig(): FDMConfig;
    reset(): void;
}
export declare const ElectromagneticsSolverOps: {
    ElectromagneticsSolver: typeof ElectromagneticsSolver;
    DEFAULT_FDM_CONFIG: FDMConfig;
};
//# sourceMappingURL=electromagneticsSolver.d.ts.map