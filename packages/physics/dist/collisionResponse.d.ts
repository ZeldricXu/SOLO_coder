import { Vec3 } from '@physics-sim/shared';
import { RigidBodyState, CollisionPair } from './types';
import { PBDContact } from './constraints';
export interface CollisionResponseResult {
    contactConstraints: PBDContact[];
    impulseApplied: number;
    responseTime: number;
}
export declare function generateContactConstraints(collisionPairs: CollisionPair[], bodies: Map<string, RigidBodyState>): PBDContact[];
export declare function resolveCollisionsWithImpulses(collisionPairs: CollisionPair[], bodies: Map<string, RigidBodyState>, iterations?: number): number;
export declare function applyGravity(bodies: Map<string, RigidBodyState>, gravity: Vec3, _dt: number): void;
export declare function integrateForcesAndVelocities(bodies: Map<string, RigidBodyState>, dt: number): void;
export declare function processCollisionResponse(collisionPairs: CollisionPair[], bodies: Map<string, RigidBodyState>, usePBD?: boolean): CollisionResponseResult;
export declare const CollisionResponse: {
    generateContactConstraints: typeof generateContactConstraints;
    resolveCollisionsWithImpulses: typeof resolveCollisionsWithImpulses;
    applyGravity: typeof applyGravity;
    integrateForcesAndVelocities: typeof integrateForcesAndVelocities;
    processCollisionResponse: typeof processCollisionResponse;
};
//# sourceMappingURL=collisionResponse.d.ts.map