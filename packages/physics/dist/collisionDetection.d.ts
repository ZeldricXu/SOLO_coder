import { RigidBodyState, CollisionPair, CollisionPoint, ConvexShape } from './types';
export interface CollisionDetectionResult {
    pairs: CollisionPair[];
    broadPhasePairs: number;
    narrowPhaseTests: number;
    detectionTime: number;
}
export declare function detectCollisions(bodies: Map<string, RigidBodyState>, gjkMaxIterations?: number, gjkTolerance?: number, epaMaxIterations?: number, epaTolerance?: number): CollisionDetectionResult;
export declare function generateContactManifold(bodyA: RigidBodyState, bodyB: RigidBodyState, collisionPoint: CollisionPoint, shapeA: ConvexShape, shapeB: ConvexShape, maxPoints?: number): CollisionPoint[];
export declare const CollisionDetection: {
    detectCollisions: typeof detectCollisions;
    generateContactManifold: typeof generateContactManifold;
};
//# sourceMappingURL=collisionDetection.d.ts.map