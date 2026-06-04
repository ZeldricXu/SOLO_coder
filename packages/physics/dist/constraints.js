import { vec3 } from '@physics-sim/shared';
import { Vec3Ops } from '@physics-sim/math';
export function createContactConstraint(bodyA, bodyB, pointA, pointB, normal, depth, restitution = 0.5, friction = 0.5) {
    return {
        type: 'contact',
        bodyA,
        bodyB,
        pointA: { ...pointA },
        pointB: { ...pointB },
        normal: Vec3Ops.normalize(normal),
        depth,
        restitution,
        friction,
        compliance: 0,
        lambda: 0,
    };
}
export function createDistanceConstraint(bodyA, bodyB, anchorA, anchorB, distance, compliance = 0) {
    return {
        type: 'distance',
        bodyA,
        bodyB,
        anchorA: { ...anchorA },
        anchorB: { ...anchorB },
        distance,
        compliance,
        lambda: 0,
    };
}
export function createHingeConstraint(bodyA, bodyB, anchor, axis, minAngle = -Math.PI, maxAngle = Math.PI) {
    return {
        type: 'hinge',
        bodyA,
        bodyB,
        anchor: { ...anchor },
        axis: Vec3Ops.normalize(axis),
        minAngle,
        maxAngle,
        compliance: 0,
        lambda: 0,
    };
}
export function solveContactConstraint(c, bodies, dt, baumgarte = 0.2) {
    const bodyA = bodies.get(c.bodyA);
    const bodyB = bodies.get(c.bodyB);
    if (!bodyA || !bodyB)
        return;
    const posA = bodyA.position;
    const posB = bodyB.position;
    const rA = Vec3Ops.sub(c.pointA, posA);
    const rB = Vec3Ops.sub(c.pointB, posB);
    const velocityA = Vec3Ops.add(bodyA.velocity, Vec3Ops.cross(bodyA.angularVelocity, rA));
    const velocityB = Vec3Ops.add(bodyB.velocity, Vec3Ops.cross(bodyB.angularVelocity, rB));
    const relativeVelocity = Vec3Ops.sub(velocityA, velocityB);
    const normalVelocity = Vec3Ops.dot(relativeVelocity, c.normal);
    if (normalVelocity > 0)
        return;
    const restitutionVelocity = -c.restitution * normalVelocity;
    const biasVelocity = (baumgarte / dt) * c.depth;
    const totalVelocityBias = biasVelocity + restitutionVelocity;
    const invMassA = bodyA.isStatic ? 0 : bodyA.invMass;
    const invMassB = bodyB.isStatic ? 0 : bodyB.invMass;
    const angularContributionA = computeAngularContribution(bodyA, rA, c.normal);
    const angularContributionB = computeAngularContribution(bodyB, rB, c.normal);
    const totalInverseMass = invMassA + invMassB + angularContributionA + angularContributionB;
    if (totalInverseMass <= 0)
        return;
    const deltaLambda = (-normalVelocity + totalVelocityBias) / totalInverseMass;
    c.lambda += deltaLambda;
    const impulse = Vec3Ops.mul(c.normal, deltaLambda);
    applyImpulse(bodyA, impulse, rA, 1);
    applyImpulse(bodyB, impulse, rB, -1);
    const tangentVelocity = Vec3Ops.sub(relativeVelocity, Vec3Ops.mul(c.normal, normalVelocity));
    const tangentSpeed = Vec3Ops.length(tangentVelocity);
    if (tangentSpeed > 1e-6 && c.friction > 0) {
        const tangent = Vec3Ops.normalize(tangentVelocity);
        const tangentInvMass = invMassA + invMassB +
            computeAngularContribution(bodyA, rA, tangent) +
            computeAngularContribution(bodyB, rB, tangent);
        if (tangentInvMass > 0) {
            const maxFrictionImpulse = Math.abs(c.lambda) * c.friction;
            const frictionImpulse = Math.min(tangentSpeed / tangentInvMass, maxFrictionImpulse);
            const frictionVector = Vec3Ops.mul(tangent, -frictionImpulse);
            applyImpulse(bodyA, frictionVector, rA, 1);
            applyImpulse(bodyB, frictionVector, rB, -1);
        }
    }
}
function computeAngularContribution(body, r, normal) {
    if (body.isStatic)
        return 0;
    const angularPart = Vec3Ops.cross(r, normal);
    const transformed = vec3(body.invInertiaTensor[0][0] * angularPart.x + body.invInertiaTensor[0][1] * angularPart.y + body.invInertiaTensor[0][2] * angularPart.z, body.invInertiaTensor[1][0] * angularPart.x + body.invInertiaTensor[1][1] * angularPart.y + body.invInertiaTensor[1][2] * angularPart.z, body.invInertiaTensor[2][0] * angularPart.x + body.invInertiaTensor[2][1] * angularPart.y + body.invInertiaTensor[2][2] * angularPart.z);
    return Vec3Ops.dot(angularPart, transformed);
}
function applyImpulse(body, impulse, r, sign) {
    if (body.isStatic)
        return;
    const linearChange = Vec3Ops.mul(impulse, sign * body.invMass);
    body.velocity = Vec3Ops.add(body.velocity, linearChange);
    const angularChange = Vec3Ops.mul(vec3(body.invInertiaTensor[0][0] * (r.y * impulse.z - r.z * impulse.y) + body.invInertiaTensor[0][1] * (r.z * impulse.x - r.x * impulse.z) + body.invInertiaTensor[0][2] * (r.x * impulse.y - r.y * impulse.x), body.invInertiaTensor[1][0] * (r.y * impulse.z - r.z * impulse.y) + body.invInertiaTensor[1][1] * (r.z * impulse.x - r.x * impulse.z) + body.invInertiaTensor[1][2] * (r.x * impulse.y - r.y * impulse.x), body.invInertiaTensor[2][0] * (r.y * impulse.z - r.z * impulse.y) + body.invInertiaTensor[2][1] * (r.z * impulse.x - r.x * impulse.z) + body.invInertiaTensor[2][2] * (r.x * impulse.y - r.y * impulse.x)), sign);
    body.angularVelocity = Vec3Ops.add(body.angularVelocity, angularChange);
}
export function solveDistanceConstraint(c, bodies, dt) {
    const bodyA = bodies.get(c.bodyA);
    const bodyB = bodies.get(c.bodyB);
    if (!bodyA || !bodyB)
        return;
    const worldA = Vec3Ops.add(bodyA.position, c.anchorA);
    const worldB = Vec3Ops.add(bodyB.position, c.anchorB);
    const delta = Vec3Ops.sub(worldB, worldA);
    const distance = Vec3Ops.length(delta);
    const normal = distance > 0 ? Vec3Ops.div(delta, distance) : vec3(1, 0, 0);
    const error = distance - c.distance;
    const compliance = c.compliance / (dt * dt);
    const invMassA = bodyA.isStatic ? 0 : bodyA.invMass;
    const invMassB = bodyB.isStatic ? 0 : bodyB.invMass;
    const angularContributionA = computeAngularContribution(bodyA, c.anchorA, normal);
    const angularContributionB = computeAngularContribution(bodyB, c.anchorB, normal);
    const totalInverseMass = invMassA + invMassB + angularContributionA + angularContributionB + compliance;
    if (totalInverseMass <= 0)
        return;
    const deltaLambda = -error / totalInverseMass;
    c.lambda += deltaLambda;
    const impulse = Vec3Ops.mul(normal, deltaLambda);
    applyImpulse(bodyA, impulse, c.anchorA, -1);
    applyImpulse(bodyB, impulse, c.anchorB, 1);
}
export function solveSpringConstraint(c, bodies, dt) {
    const bodyA = c.bodyA ? bodies.get(c.bodyA) : null;
    const bodyB = c.bodyB ? bodies.get(c.bodyB) : null;
    const worldA = bodyA ? Vec3Ops.add(bodyA.position, c.anchorA) : c.anchorA;
    const worldB = bodyB ? Vec3Ops.add(bodyB.position, c.anchorB) : c.anchorB;
    const delta = Vec3Ops.sub(worldB, worldA);
    const distance = Vec3Ops.length(delta);
    const normal = distance > 0 ? Vec3Ops.div(delta, distance) : vec3(1, 0, 0);
    const velocityA = bodyA ? Vec3Ops.add(bodyA.velocity, Vec3Ops.cross(bodyA.angularVelocity, c.anchorA)) : vec3(0, 0, 0);
    const velocityB = bodyB ? Vec3Ops.add(bodyB.velocity, Vec3Ops.cross(bodyB.angularVelocity, c.anchorB)) : vec3(0, 0, 0);
    const relativeVelocity = Vec3Ops.sub(velocityB, velocityA);
    const normalVelocity = Vec3Ops.dot(relativeVelocity, normal);
    const springForce = c.stiffness * (distance - c.restLength);
    const dampingForce = c.damping * normalVelocity;
    const totalForce = springForce + dampingForce;
    const force = Vec3Ops.mul(normal, totalForce);
    if (bodyA && !bodyA.isStatic) {
        bodyA.force = Vec3Ops.add(bodyA.force, force);
    }
    if (bodyB && !bodyB.isStatic) {
        bodyB.force = Vec3Ops.sub(bodyB.force, force);
    }
}
export function solveConstraintsPBD(constraints, bodies, dt, iterations = 10, baumgarte = 0.2) {
    let totalImpulse = 0;
    for (const c of constraints) {
        c.lambda = 0;
    }
    for (let i = 0; i < iterations; i++) {
        for (const c of constraints) {
            if (c.type === 'contact') {
                const oldLambda = c.lambda;
                solveContactConstraint(c, bodies, dt, baumgarte);
                totalImpulse += Math.abs(c.lambda - oldLambda);
            }
        }
    }
    return totalImpulse;
}
export const ConstraintOps = {
    createContactConstraint,
    createDistanceConstraint,
    createHingeConstraint,
    solveContactConstraint,
    solveDistanceConstraint,
    solveSpringConstraint,
    solveConstraintsPBD,
};
//# sourceMappingURL=constraints.js.map