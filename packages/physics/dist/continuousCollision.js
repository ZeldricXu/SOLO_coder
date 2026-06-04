import { vec3 } from '@physics-sim/shared';
import { aabbFromBody } from './aabb';
import { gjk, bodyToConvexShape } from './gjk';
import { Vec3Ops } from '@physics-sim/math';
export const DEFAULT_CCD_CONFIG = {
    maxIterations: 10,
    tolerance: 1e-4,
    ccdThreshold: 0.8,
    minSubstepDt: 1e-6,
};
export function getBoundingRadius(body) {
    const obj = body.physicsObject;
    const geom = obj?.geometry;
    if (!geom)
        return 0.5;
    switch (geom.type) {
        case 'box':
            return Math.max(geom.width, geom.height, geom.depth) / 2;
        case 'sphere':
            return geom.radius;
        case 'cylinder':
            return Math.max(geom.radius || geom.radiusTop || 0.5, geom.height / 2);
        case 'plane':
            return Math.max(geom.width, geom.height) / 2;
        case 'incline':
            return Math.max(geom.width, geom.height, geom.depth) / 2;
        default:
            return 0.5;
    }
}
export function needsCCD(body, dt) {
    if (body.isStatic)
        return false;
    const radius = getBoundingRadius(body);
    if (radius <= 0)
        return false;
    const displacement = Vec3Ops.length(Vec3Ops.mul(body.velocity, dt));
    return displacement > radius * DEFAULT_CCD_CONFIG.ccdThreshold;
}
export function detectCCD(bodies, dt, config = {}) {
    const fullConfig = { ...DEFAULT_CCD_CONFIG, ...config };
    const results = [];
    const bodyArray = Array.from(bodies.values());
    for (let i = 0; i < bodyArray.length; i++) {
        for (let j = i + 1; j < bodyArray.length; j++) {
            const bodyA = bodyArray[i];
            const bodyB = bodyArray[j];
            if (bodyA.isStatic && bodyB.isStatic)
                continue;
            const needsCCDA = needsCCD(bodyA, dt);
            const needsCCDB = needsCCD(bodyB, dt);
            if (!needsCCDA && !needsCCDB)
                continue;
            const ccdResult = performCCD(bodyA, bodyB, dt, fullConfig);
            if (ccdResult.collided) {
                results.push(ccdResult);
            }
        }
    }
    return results.sort((a, b) => a.collisionTime - b.collisionTime);
}
function performCCD(bodyA, bodyB, dt, config) {
    let t0 = 0;
    let t1 = dt;
    const posA0 = { ...bodyA.prevPosition };
    const posA1 = { ...bodyA.position };
    const posB0 = { ...bodyB.prevPosition };
    const posB1 = { ...bodyB.position };
    const velA = Vec3Ops.mul(bodyA.velocity, dt);
    const velB = Vec3Ops.mul(bodyB.velocity, dt);
    const gjkResultAtT = (t) => {
        const alpha = t / dt;
        const testBodyA = {
            ...bodyA,
            position: Vec3Ops.lerp(posA0, posA1, alpha),
        };
        const testBodyB = {
            ...bodyB,
            position: Vec3Ops.lerp(posB0, posB1, alpha),
        };
        const shapeA = bodyToConvexShape(testBodyA);
        const shapeB = bodyToConvexShape(testBodyB);
        return gjk(shapeA, shapeB, 30, 1e-6);
    };
    const initialCheck = gjkResultAtT(0);
    if (initialCheck.isColliding) {
        return {
            collided: true,
            collisionTime: 0,
            bodyA: bodyA.id,
            bodyB: bodyB.id,
        };
    }
    const finalCheck = gjkResultAtT(dt);
    if (!finalCheck.isColliding) {
        const testBodyAStart = { ...bodyA, position: posA0 };
        const testBodyAEnd = { ...bodyA, position: posA1 };
        const aabbAStart = aabbFromBody(testBodyAStart);
        const aabbAEnd = aabbFromBody(testBodyAEnd);
        const aabbB = aabbFromBody(bodyB);
        const sweptAABB = {
            min: vec3(Math.min(aabbAStart.min.x, aabbAEnd.min.x) - 0.1, Math.min(aabbAStart.min.y, aabbAEnd.min.y) - 0.1, Math.min(aabbAStart.min.z, aabbAEnd.min.z) - 0.1),
            max: vec3(Math.max(aabbAStart.max.x, aabbAEnd.max.x) + 0.1, Math.max(aabbAStart.max.y, aabbAEnd.max.y) + 0.1, Math.max(aabbAStart.max.z, aabbAEnd.max.z) + 0.1),
            bodyId: bodyA.id,
        };
        const aabbsIntersect = aabbB.min.x <= sweptAABB.max.x && aabbB.max.x >= sweptAABB.min.x &&
            aabbB.min.y <= sweptAABB.max.y && aabbB.max.y >= sweptAABB.min.y &&
            aabbB.min.z <= sweptAABB.max.z && aabbB.max.z >= sweptAABB.min.z;
        if (!aabbsIntersect) {
            return { collided: false, collisionTime: -1, bodyA: bodyA.id, bodyB: bodyB.id };
        }
    }
    for (let iter = 0; iter < config.maxIterations; iter++) {
        const tMid = (t0 + t1) / 2;
        const midResult = gjkResultAtT(tMid);
        if (midResult.isColliding) {
            t1 = tMid;
        }
        else {
            t0 = tMid;
        }
        if (t1 - t0 < config.tolerance * dt) {
            break;
        }
    }
    const collisionTime = (t0 + t1) / 2;
    if (collisionTime < dt * 0.99) {
        return {
            collided: true,
            collisionTime,
            bodyA: bodyA.id,
            bodyB: bodyB.id,
        };
    }
    return {
        collided: false,
        collisionTime: -1,
        bodyA: bodyA.id,
        bodyB: bodyB.id,
    };
}
export function resolveCCD(bodies, ccdResults, dt) {
    let remainingDt = dt;
    let ccdHandled = 0;
    for (const ccd of ccdResults) {
        if (ccd.collisionTime < 0 || ccd.collisionTime >= remainingDt)
            continue;
        const bodyA = bodies.get(ccd.bodyA);
        const bodyB = bodies.get(ccd.bodyB);
        if (!bodyA || !bodyB)
            continue;
        const ccdDt = ccd.collisionTime;
        if (ccdDt > DEFAULT_CCD_CONFIG.minSubstepDt) {
            if (!bodyA.isStatic) {
                bodyA.position = Vec3Ops.add(bodyA.prevPosition, Vec3Ops.mul(bodyA.velocity, ccdDt));
            }
            if (!bodyB.isStatic) {
                bodyB.position = Vec3Ops.add(bodyB.prevPosition, Vec3Ops.mul(bodyB.velocity, ccdDt));
            }
            bodyA.prevPosition = { ...bodyA.position };
            bodyB.prevPosition = { ...bodyB.position };
            const relVel = Vec3Ops.sub(bodyA.velocity, bodyB.velocity);
            const normal = Vec3Ops.normalize(Vec3Ops.sub(bodyA.position, bodyB.position));
            const velAlongNormal = Vec3Ops.dot(relVel, normal);
            if (velAlongNormal > 0)
                continue;
            const restitution = Math.min(bodyA.restitution, bodyB.restitution);
            const impulse = -(1 + restitution) * velAlongNormal / (bodyA.invMass + bodyB.invMass);
            if (!bodyA.isStatic) {
                bodyA.velocity = Vec3Ops.add(bodyA.velocity, Vec3Ops.mul(normal, impulse * bodyA.invMass));
            }
            if (!bodyB.isStatic) {
                bodyB.velocity = Vec3Ops.sub(bodyB.velocity, Vec3Ops.mul(normal, impulse * bodyB.invMass));
            }
            remainingDt -= ccdDt;
            ccdHandled++;
        }
    }
    return ccdHandled;
}
export const CCOps = {
    detectCCD,
    performCCD,
    resolveCCD,
    needsCCD,
    getBoundingRadius,
};
//# sourceMappingURL=continuousCollision.js.map