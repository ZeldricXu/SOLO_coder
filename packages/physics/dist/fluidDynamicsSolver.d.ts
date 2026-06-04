import { Vec3 } from '@physics-sim/shared';
import { ScalarField as ScalarFieldType, VectorField as VectorFieldType, LBMBoundaryCondition, FluidObstacle } from '@physics-sim/shared';
import { UniformGrid, ScalarField, VectorField } from '@physics-sim/math';
export interface LBMConfig {
    width: number;
    height: number;
    viscosity: number;
    inletVelocity: Vec3;
    dt: number;
    dx: number;
    maxIterations: number;
    boundaryConditions: LBMBoundaryCondition[];
}
export declare const DEFAULT_LBM_CONFIG: LBMConfig;
export interface LBMStepResult {
    density: ScalarFieldType;
    velocity: VectorFieldType;
    pressure: ScalarFieldType;
    iteration: number;
    maxVelocity: number;
    avgDensity: number;
    solveTime: number;
}
export declare class FluidDynamicsSolver {
    private config;
    private uniformGrid;
    private nx;
    private ny;
    private f;
    private fTemp;
    private rho;
    private ux;
    private uy;
    private obstacle;
    private tau;
    private omega;
    private cs2;
    private iteration;
    private obstacles;
    private parsedObstaclePolygons;
    constructor(config: Partial<LBMConfig> & {
        width: number;
        height: number;
    });
    private initialize;
    addObstacle(obstacle: FluidObstacle): void;
    private rasterizeObstacle;
    private pointInPolygon;
    private collide;
    private stream;
    private bounceBack;
    private applyVelocityInlet;
    private applyPressureOutlet;
    step(steps?: number): LBMStepResult;
    private computeMacroscopic;
    private toUnifiedIndex;
    private buildResult;
    getDensityField(): ScalarField;
    getVelocityField(): VectorField;
    getPressureField(): ScalarField;
    getDensityAt(x: number, y: number): number;
    getVelocityAt(x: number, y: number): {
        x: number;
        y: number;
    };
    getPressureAt(x: number, y: number): number;
    isObstacle(x: number, y: number): boolean;
    getUniformGrid(): UniformGrid;
    getGridInfo(): {
        nx: number;
        ny: number;
        dx: number;
        tau: number;
        omega: number;
    };
    getIteration(): number;
    getConfig(): LBMConfig;
    setViscosity(viscosity: number): void;
    setInletVelocity(velocity: Vec3): void;
    reset(): void;
}
//# sourceMappingURL=fluidDynamicsSolver.d.ts.map