import { Vec3 } from '@physics-sim/shared';
import { AABB, RigidBodyState } from './types';
export declare function createAABB(min: Vec3, max: Vec3, bodyId: string): AABB;
export declare function aabbFromBody(body: RigidBodyState): AABB;
export declare function aabbIntersect(a: AABB, b: AABB): boolean;
export declare function aabbUnion(a: AABB, b: AABB): AABB;
export declare function aabbArea(aabb: AABB): number;
export declare function aabbContains(aabb: AABB, point: Vec3): boolean;
export declare function aabbExpand(aabb: AABB, margin: number): AABB;
export declare function broadPhase(aabbs: AABB[]): {
    a: string;
    b: string;
}[];
export declare const AABBOps: {
    createAABB: typeof createAABB;
    aabbFromBody: typeof aabbFromBody;
    aabbIntersect: typeof aabbIntersect;
    aabbUnion: typeof aabbUnion;
    aabbArea: typeof aabbArea;
    aabbContains: typeof aabbContains;
    aabbExpand: typeof aabbExpand;
    broadPhase: typeof broadPhase;
};
//# sourceMappingURL=aabb.d.ts.map