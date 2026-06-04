import { Vec3, PhysicsObject } from '@physics-sim/shared';
import { RigidBodyState } from './types';
export declare function createRigidBody(physicsObject: PhysicsObject, initialVelocity?: Vec3, initialAngularVelocity?: Vec3): RigidBodyState;
export declare function computeMass(obj: PhysicsObject): number;
export declare function computeInertiaTensor(obj: PhysicsObject, mass: number): number[][];
export declare function applyForce(body: RigidBodyState, force: Vec3, point: Vec3): void;
export declare function clearForces(body: RigidBodyState): void;
export declare function applyGravity(body: RigidBodyState, gravity: Vec3): void;
export declare function applyDamping(body: RigidBodyState, linearDamping: number, angularDamping: number): void;
export declare function integrateVelocity(body: RigidBodyState, dt: number): void;
export declare function integratePosition(body: RigidBodyState, dt: number): void;
export declare function verletIntegrate(body: RigidBodyState, dt: number): void;
export declare function getVelocityAtPoint(body: RigidBodyState, point: Vec3): Vec3;
export declare const RigidBodyOps: {
    createRigidBody: typeof createRigidBody;
    computeMass: typeof computeMass;
    computeInertiaTensor: typeof computeInertiaTensor;
    applyForce: typeof applyForce;
    clearForces: typeof clearForces;
    applyGravity: typeof applyGravity;
    applyDamping: typeof applyDamping;
    integrateVelocity: typeof integrateVelocity;
    integratePosition: typeof integratePosition;
    verletIntegrate: typeof verletIntegrate;
    getVelocityAtPoint: typeof getVelocityAtPoint;
};
//# sourceMappingURL=rigidBody.d.ts.map