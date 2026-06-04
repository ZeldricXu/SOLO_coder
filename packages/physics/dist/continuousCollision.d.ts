import { Vec3 } from '@physics-sim/shared';
import { RigidBodyState } from './types';
export interface CCDResult {
    collided: boolean;
    collisionTime: number;
    bodyA: string;
    bodyB: string;
    normal?: Vec3;
    point?: Vec3;
}
export interface CCDConfig {
    maxIterations: number;
    tolerance: number;
    ccdThreshold: number;
    minSubstepDt: number;
}
export declare const DEFAULT_CCD_CONFIG: CCDConfig;
export declare function getBoundingRadius(body: RigidBodyState): number;
export declare function needsCCD(body: RigidBodyState, dt: number): boolean;
export declare function detectCCD(bodies: Map<string, RigidBodyState>, dt: number, config?: Partial<CCDConfig>): CCDResult[];
declare function performCCD(bodyA: RigidBodyState, bodyB: RigidBodyState, dt: number, config: CCDConfig): CCDResult;
export declare function resolveCCD(bodies: Map<string, RigidBodyState>, ccdResults: CCDResult[], dt: number): number;
export declare const CCOps: {
    detectCCD: typeof detectCCD;
    performCCD: typeof performCCD;
    resolveCCD: typeof resolveCCD;
    needsCCD: typeof needsCCD;
    getBoundingRadius: typeof getBoundingRadius;
};
export {};
//# sourceMappingURL=continuousCollision.d.ts.map