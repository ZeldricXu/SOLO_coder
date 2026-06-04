import { Vec3 } from '@physics-sim/shared';
import { RigidBodyState, SpringConstraint } from './types';
export interface PBDConstraint {
    type: string;
    bodyA?: string;
    bodyB?: string;
    compliance: number;
    lambda: number;
}
export interface PBDContact extends PBDConstraint {
    type: 'contact';
    bodyA: string;
    bodyB: string;
    pointA: Vec3;
    pointB: Vec3;
    normal: Vec3;
    depth: number;
    restitution: number;
    friction: number;
}
export interface PBDAngularConstraint extends PBDConstraint {
    type: 'angular';
    bodyA: string;
    bodyB: string;
    axis: Vec3;
    targetAngle: number;
}
export type PBDAnyConstraint = PBDContact | PBDAngularConstraint;
export declare function createContactConstraint(bodyA: string, bodyB: string, pointA: Vec3, pointB: Vec3, normal: Vec3, depth: number, restitution?: number, friction?: number): PBDContact;
export declare function createDistanceConstraint(bodyA: string, bodyB: string, anchorA: Vec3, anchorB: Vec3, distance: number, compliance?: number): {
    type: 'distance';
    bodyA: string;
    bodyB: string;
    anchorA: Vec3;
    anchorB: Vec3;
    distance: number;
    compliance: number;
    lambda: number;
};
export declare function createHingeConstraint(bodyA: string, bodyB: string, anchor: Vec3, axis: Vec3, minAngle?: number, maxAngle?: number): {
    type: 'hinge';
    bodyA: string;
    bodyB: string;
    anchor: Vec3;
    axis: Vec3;
    minAngle: number;
    maxAngle: number;
    compliance: number;
    lambda: number;
};
export declare function solveContactConstraint(c: PBDContact, bodies: Map<string, RigidBodyState>, dt: number, baumgarte?: number): void;
export declare function solveDistanceConstraint(c: {
    type: 'distance';
    bodyA: string;
    bodyB: string;
    anchorA: Vec3;
    anchorB: Vec3;
    distance: number;
    compliance: number;
    lambda: number;
}, bodies: Map<string, RigidBodyState>, dt: number): void;
export declare function solveSpringConstraint(c: SpringConstraint, bodies: Map<string, RigidBodyState>, dt: number): void;
export declare function solveConstraintsPBD(constraints: PBDAnyConstraint[], bodies: Map<string, RigidBodyState>, dt: number, iterations?: number, baumgarte?: number): number;
export declare const ConstraintOps: {
    createContactConstraint: typeof createContactConstraint;
    createDistanceConstraint: typeof createDistanceConstraint;
    createHingeConstraint: typeof createHingeConstraint;
    solveContactConstraint: typeof solveContactConstraint;
    solveDistanceConstraint: typeof solveDistanceConstraint;
    solveSpringConstraint: typeof solveSpringConstraint;
    solveConstraintsPBD: typeof solveConstraintsPBD;
};
//# sourceMappingURL=constraints.d.ts.map