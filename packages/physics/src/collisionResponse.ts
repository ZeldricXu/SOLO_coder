import { Vec3 } from '@physics-sim/shared';
import { RigidBodyState, CollisionPair, Constraint } from './types';
import { PBDContact, createContactConstraint, PBDAnyConstraint } from './constraints';
import { Vec3Ops } from '@physics-sim/math';

export interface CollisionResponseResult {
  contactConstraints: PBDContact[];
  impulseApplied: number;
  responseTime: number;
}

export function generateContactConstraints(
  collisionPairs: CollisionPair[],
  bodies: Map<string, RigidBodyState>
): PBDContact[] {
  const constraints: PBDContact[] = [];
  
  for (const pair of collisionPairs) {
    const bodyA = bodies.get(pair.bodyA);
    const bodyB = bodies.get(pair.bodyB);
    
    if (!bodyA || !bodyB) continue;
    
    const restitution = Math.min(bodyA.restitution, bodyB.restitution);
    const friction = Math.sqrt(bodyA.friction * bodyB.friction);
    
    for (const point of pair.points) {
      const constraint = createContactConstraint(
        pair.bodyA,
        pair.bodyB,
        point.point,
        Vec3Ops.add(point.point, Vec3Ops.mul(point.normal, point.depth)),
        point.normal,
        point.depth,
        restitution,
        friction
      );
      
      constraints.push(constraint);
    }
  }
  
  return constraints;
}

export function resolveCollisionsWithImpulses(
  collisionPairs: CollisionPair[],
  bodies: Map<string, RigidBodyState>,
  iterations: number = 10
): number {
  let totalImpulse = 0;
  
  for (let iter = 0; iter < iterations; iter++) {
    for (const pair of collisionPairs) {
      const bodyA = bodies.get(pair.bodyA);
      const bodyB = bodies.get(pair.bodyB);
      
      if (!bodyA || !bodyB) continue;
      
      const restitution = Math.min(bodyA.restitution, bodyB.restitution);
      const friction = Math.sqrt(bodyA.friction * bodyB.friction);
      
      for (const point of pair.points) {
        const impulse = resolveSingleContact(
          bodyA,
          bodyB,
          point,
          restitution,
          friction
        );
        totalImpulse += impulse;
      }
    }
  }
  
  return totalImpulse;
}

function resolveSingleContact(
  bodyA: RigidBodyState,
  bodyB: RigidBodyState,
  contactPoint: { point: Vec3; normal: Vec3; depth: number },
  restitution: number,
  friction: number
): number {
  const posA = bodyA.position;
  const posB = bodyB.position;
  
  const rA = Vec3Ops.sub(contactPoint.point, posA);
  const rB = Vec3Ops.sub(contactPoint.point, posB);
  
  const velocityA = Vec3Ops.add(bodyA.velocity, Vec3Ops.cross(bodyA.angularVelocity, rA));
  const velocityB = Vec3Ops.add(bodyB.velocity, Vec3Ops.cross(bodyB.angularVelocity, rB));
  
  const relativeVelocity = Vec3Ops.sub(velocityA, velocityB);
  const normalVelocity = Vec3Ops.dot(relativeVelocity, contactPoint.normal);
  
  if (normalVelocity > 0) return 0;
  
  const restitutionVelocity = -restitution * normalVelocity;
  const baumgarteVelocity = 0.2 * contactPoint.depth / 0.016;
  const totalVelocityBias = baumgarteVelocity + restitutionVelocity;
  
  const invMassA = bodyA.isStatic ? 0 : bodyA.invMass;
  const invMassB = bodyB.isStatic ? 0 : bodyB.invMass;
  
  const angularContributionA = computeAngularContribution(bodyA, rA, contactPoint.normal);
  const angularContributionB = computeAngularContribution(bodyB, rB, contactPoint.normal);
  
  const totalInverseMass = invMassA + invMassB + angularContributionA + angularContributionB;
  
  if (totalInverseMass <= 0) return 0;
  
  const deltaLambda = (-normalVelocity + totalVelocityBias) / totalInverseMass;
  
  const impulse = Vec3Ops.mul(contactPoint.normal, deltaLambda);
  
  applyImpulse(bodyA, impulse, rA, 1);
  applyImpulse(bodyB, impulse, rB, -1);
  
  const tangentVelocity = Vec3Ops.sub(relativeVelocity, Vec3Ops.mul(contactPoint.normal, normalVelocity));
  const tangentSpeed = Vec3Ops.length(tangentVelocity);
  
  if (tangentSpeed > 1e-6 && friction > 0) {
    const tangent = Vec3Ops.normalize(tangentVelocity);
    
    const tangentInvMass = invMassA + invMassB + 
      computeAngularContribution(bodyA, rA, tangent) + 
      computeAngularContribution(bodyB, rB, tangent);
    
    if (tangentInvMass > 0) {
      const maxFrictionImpulse = Math.abs(deltaLambda) * friction;
      const frictionImpulse = Math.min(tangentSpeed / tangentInvMass, maxFrictionImpulse);
      const frictionVector = Vec3Ops.mul(tangent, -frictionImpulse);
      
      applyImpulse(bodyA, frictionVector, rA, 1);
      applyImpulse(bodyB, frictionVector, rB, -1);
    }
  }
  
  return Math.abs(deltaLambda);
}

function computeAngularContribution(body: RigidBodyState, r: Vec3, normal: Vec3): number {
  if (body.isStatic) return 0;
  
  const angularPart = Vec3Ops.cross(r, normal);
  const transformed = {
    x: body.invInertiaTensor[0][0] * angularPart.x + body.invInertiaTensor[0][1] * angularPart.y + body.invInertiaTensor[0][2] * angularPart.z,
    y: body.invInertiaTensor[1][0] * angularPart.x + body.invInertiaTensor[1][1] * angularPart.y + body.invInertiaTensor[1][2] * angularPart.z,
    z: body.invInertiaTensor[2][0] * angularPart.x + body.invInertiaTensor[2][1] * angularPart.y + body.invInertiaTensor[2][2] * angularPart.z,
  };
  
  return Vec3Ops.dot(angularPart, transformed);
}

function applyImpulse(body: RigidBodyState, impulse: Vec3, r: Vec3, sign: number): void {
  if (body.isStatic) return;
  
  const linearChange = Vec3Ops.mul(impulse, sign * body.invMass);
  body.velocity = Vec3Ops.add(body.velocity, linearChange);
  
  const crossProduct = {
    x: r.y * impulse.z - r.z * impulse.y,
    y: r.z * impulse.x - r.x * impulse.z,
    z: r.x * impulse.y - r.y * impulse.x,
  };
  
  const angularChange = Vec3Ops.mul(
    {
      x: body.invInertiaTensor[0][0] * crossProduct.x + body.invInertiaTensor[0][1] * crossProduct.y + body.invInertiaTensor[0][2] * crossProduct.z,
      y: body.invInertiaTensor[1][0] * crossProduct.x + body.invInertiaTensor[1][1] * crossProduct.y + body.invInertiaTensor[1][2] * crossProduct.z,
      z: body.invInertiaTensor[2][0] * crossProduct.x + body.invInertiaTensor[2][1] * crossProduct.y + body.invInertiaTensor[2][2] * crossProduct.z,
    },
    sign
  );
  body.angularVelocity = Vec3Ops.add(body.angularVelocity, angularChange);
}

export function applyGravity(
  bodies: Map<string, RigidBodyState>,
  gravity: Vec3,
  _dt: number
): void {
  bodies.forEach((body) => {
    if (body.isStatic) return;
    
    const gravityForce = Vec3Ops.mul(gravity, body.mass);
    body.force = Vec3Ops.add(body.force, gravityForce);
  });
}

export function integrateForcesAndVelocities(
  bodies: Map<string, RigidBodyState>,
  dt: number
): void {
  bodies.forEach((body) => {
    if (body.isStatic) return;
    
    body.prevPosition = { ...body.position };
    body.prevRotation = { ...body.rotation };
    
    const linearAcceleration = Vec3Ops.mul(body.force, body.invMass);
    body.velocity = Vec3Ops.add(
      body.velocity,
      Vec3Ops.mul(linearAcceleration, dt)
    );
    
    const angularAcceleration = {
      x: body.invInertiaTensor[0][0] * body.torque.x + body.invInertiaTensor[0][1] * body.torque.y + body.invInertiaTensor[0][2] * body.torque.z,
      y: body.invInertiaTensor[1][0] * body.torque.x + body.invInertiaTensor[1][1] * body.torque.y + body.invInertiaTensor[1][2] * body.torque.z,
      z: body.invInertiaTensor[2][0] * body.torque.x + body.invInertiaTensor[2][1] * body.torque.y + body.invInertiaTensor[2][2] * body.torque.z,
    };
    body.angularVelocity = Vec3Ops.add(
      body.angularVelocity,
      Vec3Ops.mul(angularAcceleration, dt)
    );
    
    body.position = Vec3Ops.add(
      body.position,
      Vec3Ops.mul(body.velocity, dt)
    );
    
    body.rotation = Vec3Ops.add(
      body.rotation,
      Vec3Ops.mul(body.angularVelocity, dt)
    );
    
    body.force = { x: 0, y: 0, z: 0 };
    body.torque = { x: 0, y: 0, z: 0 };
  });
}

export function processCollisionResponse(
  collisionPairs: CollisionPair[],
  bodies: Map<string, RigidBodyState>,
  usePBD: boolean = true
): CollisionResponseResult {
  const startTime = performance.now();
  
  let impulseApplied = 0;
  let contactConstraints: PBDContact[] = [];
  
  if (usePBD) {
    contactConstraints = generateContactConstraints(collisionPairs, bodies);
  } else {
    impulseApplied = resolveCollisionsWithImpulses(collisionPairs, bodies);
  }
  
  const endTime = performance.now();
  
  return {
    contactConstraints,
    impulseApplied,
    responseTime: endTime - startTime,
  };
}

export const CollisionResponse = {
  generateContactConstraints,
  resolveCollisionsWithImpulses,
  applyGravity,
  integrateForcesAndVelocities,
  processCollisionResponse,
};
