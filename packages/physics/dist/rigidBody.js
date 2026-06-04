import { vec3, MATERIALS } from '@physics-sim/shared';
import { Vec3Ops } from '@physics-sim/math';
export function createRigidBody(physicsObject, initialVelocity = vec3(0, 0, 0), initialAngularVelocity = vec3(0, 0, 0)) {
    const mass = computeMass(physicsObject);
    const inertiaTensor = computeInertiaTensor(physicsObject, mass);
    const invInertiaTensor = invertMatrix(inertiaTensor);
    return {
        id: physicsObject.id,
        position: { ...physicsObject.position },
        rotation: { ...physicsObject.rotation },
        velocity: { ...initialVelocity },
        angularVelocity: { ...initialAngularVelocity },
        prevPosition: { ...physicsObject.position },
        prevRotation: { ...physicsObject.rotation },
        force: vec3(0, 0, 0),
        torque: vec3(0, 0, 0),
        mass,
        invMass: mass > 0 ? 1 / mass : 0,
        inertiaTensor,
        invInertiaTensor,
        restitution: physicsObject.mechanics?.restitution ?? 0.5,
        friction: physicsObject.mechanics?.friction ?? 0.5,
        isStatic: physicsObject.isStatic,
        physicsObject,
    };
}
export function computeMass(obj) {
    if (obj.isStatic)
        return Infinity;
    const material = MATERIALS[obj.materialId] || MATERIALS.steel;
    const density = material.density;
    let volume = 1;
    if (obj.type === 'joint') {
        return 0.1;
    }
    const objWithGeometry = obj;
    switch (objWithGeometry.geometry.type) {
        case 'box':
            volume = objWithGeometry.geometry.width * objWithGeometry.geometry.height * objWithGeometry.geometry.depth;
            break;
        case 'sphere':
            volume = (4 / 3) * Math.PI * Math.pow(objWithGeometry.geometry.radius, 3);
            break;
        case 'cylinder': {
            const geom = objWithGeometry.geometry;
            const r = Math.max(geom.radiusTop || geom.radius || 0, geom.radiusBottom || geom.radius || 0);
            volume = Math.PI * r * r * (geom.height || 1);
            break;
        }
        case 'plane':
            volume = objWithGeometry.geometry.width * objWithGeometry.geometry.height * 0.1;
            break;
        case 'incline':
            volume = objWithGeometry.geometry.width * objWithGeometry.geometry.height * objWithGeometry.geometry.depth * 0.5;
            break;
        default:
            volume = 1;
    }
    return density * volume;
}
export function computeInertiaTensor(obj, mass) {
    const I = [
        [0, 0, 0],
        [0, 0, 0],
        [0, 0, 0],
    ];
    if (obj.isStatic || mass === Infinity || obj.type === 'joint') {
        return I;
    }
    const objWithGeometry = obj;
    const geom = objWithGeometry.geometry;
    switch (geom.type) {
        case 'box': {
            const w = geom.width || 1;
            const h = geom.height || 1;
            const d = geom.depth || 1;
            I[0][0] = (mass / 12) * (h * h + d * d);
            I[1][1] = (mass / 12) * (w * w + d * d);
            I[2][2] = (mass / 12) * (w * w + h * h);
            break;
        }
        case 'sphere': {
            const r = geom.radius || 0.5;
            const value = (2 / 5) * mass * r * r;
            I[0][0] = value;
            I[1][1] = value;
            I[2][2] = value;
            break;
        }
        case 'cylinder': {
            const r = Math.max(geom.radiusTop || geom.radius || 0.5, geom.radiusBottom || geom.radius || 0.5);
            const h = geom.height || 1;
            I[0][0] = (mass / 12) * (3 * r * r + h * h);
            I[1][1] = 0.5 * mass * r * r;
            I[2][2] = (mass / 12) * (3 * r * r + h * h);
            break;
        }
        default:
            I[0][0] = mass;
            I[1][1] = mass;
            I[2][2] = mass;
    }
    return I;
}
function invertMatrix(m) {
    const n = m.length;
    const result = new Array(n).fill(0).map(() => new Array(n).fill(0));
    for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
            result[i][j] = i === j ? 1 : 0;
        }
    }
    const augmented = new Array(n).fill(0).map((_, i) => [...m[i], ...result[i]]);
    for (let col = 0; col < n; col++) {
        let maxRow = col;
        for (let row = col + 1; row < n; row++) {
            if (Math.abs(augmented[row][col]) > Math.abs(augmented[maxRow][col])) {
                maxRow = row;
            }
        }
        if (Math.abs(augmented[maxRow][col]) < 1e-15) {
            return new Array(n).fill(0).map(() => new Array(n).fill(0));
        }
        [augmented[col], augmented[maxRow]] = [augmented[maxRow], augmented[col]];
        const pivot = augmented[col][col];
        for (let row = col; row < 2 * n; row++) {
            augmented[col][row] /= pivot;
        }
        for (let row = 0; row < n; row++) {
            if (row !== col && Math.abs(augmented[row][col]) > 1e-15) {
                const factor = augmented[row][col];
                for (let col2 = col; col2 < 2 * n; col2++) {
                    augmented[row][col2] -= factor * augmented[col][col2];
                }
            }
        }
    }
    const inv = new Array(n).fill(0).map(() => new Array(n).fill(0));
    for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
            inv[i][j] = augmented[i][j + n];
        }
    }
    return inv;
}
export function applyForce(body, force, point) {
    if (body.isStatic)
        return;
    body.force = Vec3Ops.add(body.force, force);
    const r = Vec3Ops.sub(point, body.position);
    body.torque = Vec3Ops.add(body.torque, Vec3Ops.cross(r, force));
}
export function clearForces(body) {
    body.force = vec3(0, 0, 0);
    body.torque = vec3(0, 0, 0);
}
export function applyGravity(body, gravity) {
    if (body.isStatic || body.mass === Infinity)
        return;
    body.force = Vec3Ops.add(body.force, Vec3Ops.mul(gravity, body.mass));
}
export function applyDamping(body, linearDamping, angularDamping) {
    if (body.isStatic)
        return;
    body.velocity = Vec3Ops.mul(body.velocity, 1 - linearDamping);
    body.angularVelocity = Vec3Ops.mul(body.angularVelocity, 1 - angularDamping);
}
export function integrateVelocity(body, dt) {
    if (body.isStatic)
        return;
    body.prevPosition = { ...body.position };
    body.prevRotation = { ...body.rotation };
    const linearAccel = Vec3Ops.mul(body.force, body.invMass);
    body.velocity = Vec3Ops.add(body.velocity, Vec3Ops.mul(linearAccel, dt));
    const angularAccel = transformVector(body.invInertiaTensor, body.torque);
    body.angularVelocity = Vec3Ops.add(body.angularVelocity, Vec3Ops.mul(angularAccel, dt));
}
export function integratePosition(body, dt) {
    if (body.isStatic)
        return;
    body.position = Vec3Ops.add(body.position, Vec3Ops.mul(body.velocity, dt));
    body.rotation = Vec3Ops.add(body.rotation, Vec3Ops.mul(body.angularVelocity, dt));
}
function transformVector(matrix, v) {
    return vec3(matrix[0][0] * v.x + matrix[0][1] * v.y + matrix[0][2] * v.z, matrix[1][0] * v.x + matrix[1][1] * v.y + matrix[1][2] * v.z, matrix[2][0] * v.x + matrix[2][1] * v.y + matrix[2][2] * v.z);
}
export function verletIntegrate(body, dt) {
    if (body.isStatic)
        return;
    const newPosition = vec3(2 * body.position.x - body.prevPosition.x + body.force.x * body.invMass * dt * dt, 2 * body.position.y - body.prevPosition.y + body.force.y * body.invMass * dt * dt, 2 * body.position.z - body.prevPosition.z + body.force.z * body.invMass * dt * dt);
    body.velocity = vec3((newPosition.x - body.prevPosition.x) / (2 * dt), (newPosition.y - body.prevPosition.y) / (2 * dt), (newPosition.z - body.prevPosition.z) / (2 * dt));
    body.prevPosition = { ...body.position };
    body.position = newPosition;
}
export function getVelocityAtPoint(body, point) {
    const r = Vec3Ops.sub(point, body.position);
    return Vec3Ops.add(body.velocity, Vec3Ops.cross(body.angularVelocity, r));
}
export const RigidBodyOps = {
    createRigidBody,
    computeMass,
    computeInertiaTensor,
    applyForce,
    clearForces,
    applyGravity,
    applyDamping,
    integrateVelocity,
    integratePosition,
    verletIntegrate,
    getVelocityAtPoint,
};
//# sourceMappingURL=rigidBody.js.map