import { Vec3 } from '@physics-sim/shared';
import { RigidBodyState, ConvexShape } from './types';
export declare function getSupportPoint(shape: ConvexShape, direction: Vec3): Vec3;
export declare function minkowskiSupport(shapeA: ConvexShape, shapeB: ConvexShape, direction: Vec3): Vec3;
export declare function gjk(shapeA: ConvexShape, shapeB: ConvexShape, maxIterations?: number, tolerance?: number): {
    isColliding: boolean;
    simplex: Vec3[];
    direction: Vec3;
};
export declare function bodyToConvexShape(body: RigidBodyState): ConvexShape;
export declare const GJKOps: {
    getSupportPoint: typeof getSupportPoint;
    minkowskiSupport: typeof minkowskiSupport;
    gjk: typeof gjk;
    bodyToConvexShape: typeof bodyToConvexShape;
};
//# sourceMappingURL=gjk.d.ts.map