import { Vec3 } from '@physics-sim/shared';
import { ConvexShape, CollisionPoint } from './types';
export declare function epa(shapeA: ConvexShape, shapeB: ConvexShape, initialSimplex: Vec3[], maxIterations?: number, tolerance?: number): CollisionPoint | null;
export declare function computeContactManifold(bodyA: ConvexShape, bodyB: ConvexShape, collisionNormal: Vec3, collisionPoint: Vec3, maxPoints?: number): CollisionPoint[];
export declare const EPAOps: {
    epa: typeof epa;
    computeContactManifold: typeof computeContactManifold;
};
//# sourceMappingURL=epa.d.ts.map