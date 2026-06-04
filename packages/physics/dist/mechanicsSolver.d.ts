import { Vec3, PhysicsObject } from '@physics-sim/shared';
import { RigidBodyState, SolverStats, SpringConstraint, DistanceConstraint, HingeConstraint } from './types';
import { CollisionDetectionResult } from './collisionDetection';
import { CollisionResponseResult } from './collisionResponse';
import { StiffnessAnalysisResult } from '@physics-sim/math';
import { CCDResult, CCDConfig } from './continuousCollision';
export interface MechanicsSolverConfig {
    gravity: Vec3;
    dt: number;
    substeps: number;
    solverIterations: number;
    baumgarte: number;
    usePBD: boolean;
    useVerlet: boolean;
    adaptiveStepSize: boolean;
    tolerance: number;
    minDt: number;
    maxDt: number;
    useCCD: boolean;
    ccdConfig: Partial<CCDConfig>;
}
export declare const DEFAULT_MECHANICS_CONFIG: MechanicsSolverConfig;
export interface MechanicsStepResult {
    bodies: Map<string, RigidBodyState>;
    collisions: CollisionDetectionResult;
    response: CollisionResponseResult;
    stats: SolverStats;
    stiffness?: StiffnessAnalysisResult;
    actualDt: number;
    ccdResults?: CCDResult[];
    ccdHandled?: number;
}
export declare class MechanicsSolver {
    private config;
    private bodies;
    private springConstraints;
    private distanceConstraints;
    private hingeConstraints;
    private accumulatedTime;
    constructor(config?: Partial<MechanicsSolverConfig>);
    addPhysicsObject(obj: PhysicsObject, initialVelocity?: Vec3, initialAngularVelocity?: Vec3): RigidBodyState;
    removeBody(id: string): boolean;
    getBody(id: string): RigidBodyState | undefined;
    getAllBodies(): Map<string, RigidBodyState>;
    addSpringConstraint(constraint: SpringConstraint): void;
    addDistanceConstraint(constraint: DistanceConstraint): void;
    addHingeConstraint(constraint: HingeConstraint): void;
    applyForce(bodyId: string, force: Vec3, point: Vec3): void;
    applyImpulse(bodyId: string, impulse: Vec3, point: Vec3): void;
    step(dt?: number): MechanicsStepResult;
    private performSubstep;
    private integrateWithVerlet;
    private enforceDistanceConstraints;
    private enforceHingeConstraints;
    checkStiffness(): StiffnessAnalysisResult;
    reset(): void;
    setConfig(config: Partial<MechanicsSolverConfig>): void;
    getConfig(): MechanicsSolverConfig;
}
export declare const MechanicsSolverOps: {
    MechanicsSolver: typeof MechanicsSolver;
    DEFAULT_MECHANICS_CONFIG: MechanicsSolverConfig;
};
//# sourceMappingURL=mechanicsSolver.d.ts.map