import { Vec3 } from '@physics-sim/shared';
import { VectorField, ScalarField, Streamline, ParticleState, ParticleSystemConfig, CrossSectionPlane } from '@physics-sim/shared';
export declare function computeStreamlines(field: VectorField, seedPoints: Vec3[], config?: {
    maxSteps?: number;
    stepSize?: number;
    maxLength?: number;
}): Streamline[];
export declare function generateStreamlineSeedPoints(field: VectorField, density?: number): Vec3[];
export declare class ParticleSystem {
    private particles;
    private config;
    private nextId;
    constructor(config: ParticleSystemConfig);
    update(field: VectorField | ScalarField, dt: number): ParticleState[];
    private emitParticle;
    private colorByValue;
    getParticles(): ParticleState[];
    clear(): void;
    setEmitRate(rate: number): void;
}
export declare function computeCrossSection(field: ScalarField | VectorField, plane: CrossSectionPlane): ScalarField;
//# sourceMappingURL=fieldVisualization.d.ts.map