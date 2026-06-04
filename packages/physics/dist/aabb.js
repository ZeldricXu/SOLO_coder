import { vec3 } from '@physics-sim/shared';
export function createAABB(min, max, bodyId) {
    return { min, max, bodyId };
}
export function aabbFromBody(body) {
    const obj = body.physicsObject;
    let halfSize = vec3(1, 1, 1);
    switch (obj.geometry.type) {
        case 'box':
            halfSize = vec3(obj.geometry.width / 2, obj.geometry.height / 2, obj.geometry.depth / 2);
            break;
        case 'sphere':
            halfSize = vec3(obj.geometry.radius, obj.geometry.radius, obj.geometry.radius);
            break;
        case 'cylinder':
            halfSize = vec3(Math.max(obj.geometry.radiusTop, obj.geometry.radiusBottom), obj.geometry.height / 2, Math.max(obj.geometry.radiusTop, obj.geometry.radiusBottom));
            break;
        case 'plane':
            halfSize = vec3(obj.geometry.width / 2, 0.01, obj.geometry.height / 2);
            break;
        case 'incline':
            halfSize = vec3(obj.geometry.width / 2, obj.geometry.height / 2, obj.geometry.depth / 2);
            break;
        default:
            halfSize = vec3(1, 1, 1);
    }
    return {
        min: vec3(body.position.x - halfSize.x, body.position.y - halfSize.y, body.position.z - halfSize.z),
        max: vec3(body.position.x + halfSize.x, body.position.y + halfSize.y, body.position.z + halfSize.z),
        bodyId: body.id,
    };
}
export function aabbIntersect(a, b) {
    return (a.min.x <= b.max.x && a.max.x >= b.min.x &&
        a.min.y <= b.max.y && a.max.y >= b.min.y &&
        a.min.z <= b.max.z && a.max.z >= b.min.z);
}
export function aabbUnion(a, b) {
    return {
        min: vec3(Math.min(a.min.x, b.min.x), Math.min(a.min.y, b.min.y), Math.min(a.min.z, b.min.z)),
        max: vec3(Math.max(a.max.x, b.max.x), Math.max(a.max.y, b.max.y), Math.max(a.max.z, b.max.z)),
        bodyId: '',
    };
}
export function aabbArea(aabb) {
    const dx = aabb.max.x - aabb.min.x;
    const dy = aabb.max.y - aabb.min.y;
    const dz = aabb.max.z - aabb.min.z;
    return 2 * (dx * dy + dy * dz + dz * dx);
}
export function aabbContains(aabb, point) {
    return (point.x >= aabb.min.x && point.x <= aabb.max.x &&
        point.y >= aabb.min.y && point.y <= aabb.max.y &&
        point.z >= aabb.min.z && point.z <= aabb.max.z);
}
export function aabbExpand(aabb, margin) {
    return {
        min: vec3(aabb.min.x - margin, aabb.min.y - margin, aabb.min.z - margin),
        max: vec3(aabb.max.x + margin, aabb.max.y + margin, aabb.max.z + margin),
        bodyId: aabb.bodyId,
    };
}
export function broadPhase(aabbs) {
    const pairs = [];
    const n = aabbs.length;
    const sortedX = [...aabbs].sort((a, b) => a.min.x - b.min.x);
    const sortedY = [...aabbs].sort((a, b) => a.min.y - b.min.y);
    const sortedZ = [...aabbs].sort((a, b) => a.min.z - b.min.z);
    const candidatePairs = new Set();
    for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
            if (sortedX[j].min.x > sortedX[i].max.x)
                break;
            if (sortedX[i].bodyId === sortedX[j].bodyId)
                continue;
            if (aabbIntersect(sortedX[i], sortedX[j])) {
                const pairKey = [sortedX[i].bodyId, sortedX[j].bodyId].sort().join('|');
                candidatePairs.add(pairKey);
            }
        }
    }
    for (const key of candidatePairs) {
        const [a, b] = key.split('|');
        pairs.push({ a, b });
    }
    return pairs;
}
export const AABBOps = {
    createAABB,
    aabbFromBody,
    aabbIntersect,
    aabbUnion,
    aabbArea,
    aabbContains,
    aabbExpand,
    broadPhase,
};
//# sourceMappingURL=aabb.js.map