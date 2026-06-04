import { Vec3, vec3, PhysicsObject } from '@physics-sim/shared';

export interface RigidBodyState {
  id: string;
  position: Vec3;
  rotation: Vec3;
  velocity: Vec3;
  angularVelocity: Vec3;
  prevPosition: Vec3;
  prevRotation: Vec3;
  force: Vec3;
  torque: Vec3;
  mass: number;
  invMass: number;
  inertiaTensor: number[][];
  invInertiaTensor: number[][];
  restitution: number;
  friction: number;
  isStatic: boolean;
  physicsObject: PhysicsObject;
}

export interface AABB {
  min: Vec3;
  max: Vec3;
  bodyId: string;
}

export interface CollisionPoint {
  point: Vec3;
  normal: Vec3;
  depth: number;
}

export interface CollisionPair {
  bodyA: string;
  bodyB: string;
  points: CollisionPoint[];
  isColliding: boolean;
}

export interface ContactConstraint {
  type: 'contact';
  bodyA: string;
  bodyB: string;
  pointA: Vec3;
  pointB: Vec3;
  normal: Vec3;
  depth: number;
  restitution: number;
  friction: number;
  impulse: number;
  tangentImpulse: number[];
}

export interface SpringConstraint {
  type: 'spring';
  bodyA?: string;
  bodyB?: string;
  anchorA: Vec3;
  anchorB: Vec3;
  restLength: number;
  stiffness: number;
  damping: number;
}

export interface DistanceConstraint {
  type: 'distance';
  bodyA: string;
  bodyB: string;
  anchorA: Vec3;
  anchorB: Vec3;
  distance: number;
  compliance: number;
}

export interface HingeConstraint {
  type: 'hinge';
  bodyA: string;
  bodyB: string;
  anchor: Vec3;
  axis: Vec3;
  minAngle: number;
  maxAngle: number;
  currentAngle: number;
}

export type Constraint = 
  | ContactConstraint 
  | SpringConstraint 
  | DistanceConstraint 
  | HingeConstraint;

export interface ConvexShape {
  vertices: Vec3[];
  center: Vec3;
}

export interface SolverStats {
  collisionPairs: number;
  constraintIterations: number;
  totalImpulse: number;
  solveTime: number;
}
