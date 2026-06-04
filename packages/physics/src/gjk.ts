import { Vec3, vec3, PhysicsObject } from '@physics-sim/shared';
import { RigidBodyState, ConvexShape, CollisionPoint } from './types';
import { Vec3Ops } from '@physics-sim/math';

export function getSupportPoint(shape: ConvexShape, direction: Vec3): Vec3 {
  let maxDot = -Infinity;
  let supportPoint = shape.vertices[0];

  for (const vertex of shape.vertices) {
    const dot = Vec3Ops.dot(vertex, direction);
    if (dot > maxDot) {
      maxDot = dot;
      supportPoint = vertex;
    }
  }

  return supportPoint;
}

export function minkowskiSupport(
  shapeA: ConvexShape,
  shapeB: ConvexShape,
  direction: Vec3
): Vec3 {
  const supportA = getSupportPoint(shapeA, direction);
  const supportB = getSupportPoint(shapeB, Vec3Ops.negate(direction));
  return Vec3Ops.sub(supportA, supportB);
}

function sameDirection(a: Vec3, b: Vec3): boolean {
  return Vec3Ops.dot(a, b) > 0;
}

function containsOrigin(simplex: Vec3[], direction: Vec3): { contains: boolean; newDirection: Vec3 } {
  const a = simplex[simplex.length - 1];
  const ao = Vec3Ops.negate(a);

  if (simplex.length === 4) {
    const b = simplex[2];
    const c = simplex[1];
    const d = simplex[0];

    const ab = Vec3Ops.sub(b, a);
    const ac = Vec3Ops.sub(c, a);
    const ad = Vec3Ops.sub(d, a);

    const abcNormal = Vec3Ops.cross(ab, ac);
    const acdNormal = Vec3Ops.cross(ac, ad);
    const adbNormal = Vec3Ops.cross(ad, ab);

    if (sameDirection(abcNormal, ao)) {
      simplex.splice(0, 1);
      return { contains: false, newDirection: abcNormal };
    } else if (sameDirection(acdNormal, ao)) {
      simplex.splice(2, 1);
      return { contains: false, newDirection: acdNormal };
    } else if (sameDirection(adbNormal, ao)) {
      simplex.splice(1, 1);
      return { contains: false, newDirection: adbNormal };
    }

    return { contains: true, newDirection: vec3(0, 0, 0) };
  } else if (simplex.length === 3) {
    const b = simplex[1];
    const c = simplex[0];

    const ab = Vec3Ops.sub(b, a);
    const ac = Vec3Ops.sub(c, a);
    const abcNormal = Vec3Ops.cross(ab, ac);

    if (sameDirection(Vec3Ops.cross(ab, abcNormal), ao)) {
      simplex.splice(0, 1);
      return { contains: false, newDirection: Vec3Ops.cross(ab, ao) };
    } else if (sameDirection(Vec3Ops.cross(abcNormal, ac), ao)) {
      simplex.splice(1, 1);
      return { contains: false, newDirection: Vec3Ops.cross(ac, ao) };
    }

    if (sameDirection(abcNormal, ao)) {
      return { contains: false, newDirection: abcNormal };
    }

    return { contains: false, newDirection: Vec3Ops.negate(abcNormal) };
  } else if (simplex.length === 2) {
    const b = simplex[0];
    const ab = Vec3Ops.sub(b, a);

    const abPerp = Vec3Ops.cross(Vec3Ops.cross(ab, ao), ab);
    return { contains: false, newDirection: abPerp };
  }

  return { contains: false, newDirection: ao };
}

export function gjk(
  shapeA: ConvexShape,
  shapeB: ConvexShape,
  maxIterations: number = 100,
  tolerance: number = 1e-10
): { isColliding: boolean; simplex: Vec3[]; direction: Vec3 } {
  const simplex: Vec3[] = [];
  let direction = vec3(1, 1, 1);

  let support = minkowskiSupport(shapeA, shapeB, direction);
  simplex.push(support);

  if (!sameDirection(support, direction)) {
    return { isColliding: false, simplex, direction };
  }

  direction = Vec3Ops.negate(support);

  for (let i = 0; i < maxIterations; i++) {
    support = minkowskiSupport(shapeA, shapeB, direction);

    if (!sameDirection(support, direction)) {
      return { isColliding: false, simplex, direction };
    }

    simplex.push(support);

    const { contains, newDirection } = containsOrigin(simplex, direction);
    
    if (contains) {
      return { isColliding: true, simplex, direction: newDirection };
    }

    direction = newDirection;

    if (Vec3Ops.length(direction) < tolerance) {
      return { isColliding: true, simplex, direction };
    }
  }

  return { isColliding: false, simplex, direction };
}

export function bodyToConvexShape(body: RigidBodyState): ConvexShape {
  const obj = body.physicsObject;
  const vertices: Vec3[] = [];
  const pos = body.position;

  switch (obj.geometry.type) {
    case 'box': {
      const hw = obj.geometry.width / 2;
      const hh = obj.geometry.height / 2;
      const hd = obj.geometry.depth / 2;
      for (let x = -1; x <= 1; x += 2) {
        for (let y = -1; y <= 1; y += 2) {
          for (let z = -1; z <= 1; z += 2) {
            vertices.push(vec3(pos.x + x * hw, pos.y + y * hh, pos.z + z * hd));
          }
        }
      }
      break;
    }
    case 'sphere': {
      const r = obj.geometry.radius;
      const subdivisions = 16;
      for (let i = 0; i < subdivisions; i++) {
        const theta = (i * Math.PI) / subdivisions;
        const sinTheta = Math.sin(theta);
        const cosTheta = Math.cos(theta);
        for (let j = 0; j < subdivisions * 2; j++) {
          const phi = (j * 2 * Math.PI) / (subdivisions * 2);
          vertices.push(vec3(
            pos.x + r * sinTheta * Math.cos(phi),
            pos.y + r * cosTheta,
            pos.z + r * sinTheta * Math.sin(phi)
          ));
        }
      }
      break;
    }
    case 'cylinder': {
      const r = Math.max(obj.geometry.radiusTop, obj.geometry.radiusBottom);
      const h = obj.geometry.height / 2;
      const segments = 16;
      for (let y = -1; y <= 1; y += 2) {
        for (let i = 0; i < segments; i++) {
          const angle = (i * 2 * Math.PI) / segments;
          vertices.push(vec3(
            pos.x + r * Math.cos(angle),
            pos.y + y * h,
            pos.z + r * Math.sin(angle)
          ));
        }
      }
      break;
    }
    case 'plane': {
      const hw = obj.geometry.width / 2;
      const hh = obj.geometry.height / 2;
      vertices.push(vec3(pos.x - hw, pos.y, pos.z - hh));
      vertices.push(vec3(pos.x + hw, pos.y, pos.z - hh));
      vertices.push(vec3(pos.x + hw, pos.y, pos.z + hh));
      vertices.push(vec3(pos.x - hw, pos.y, pos.z + hh));
      vertices.push(vec3(pos.x - hw, pos.y + 0.1, pos.z - hh));
      vertices.push(vec3(pos.x + hw, pos.y + 0.1, pos.z - hh));
      vertices.push(vec3(pos.x + hw, pos.y + 0.1, pos.z + hh));
      vertices.push(vec3(pos.x - hw, pos.y + 0.1, pos.z + hh));
      break;
    }
    default: {
      vertices.push(vec3(pos.x - 1, pos.y - 1, pos.z - 1));
      vertices.push(vec3(pos.x + 1, pos.y - 1, pos.z - 1));
      vertices.push(vec3(pos.x + 1, pos.y + 1, pos.z - 1));
      vertices.push(vec3(pos.x - 1, pos.y + 1, pos.z - 1));
      vertices.push(vec3(pos.x - 1, pos.y - 1, pos.z + 1));
      vertices.push(vec3(pos.x + 1, pos.y - 1, pos.z + 1));
      vertices.push(vec3(pos.x + 1, pos.y + 1, pos.z + 1));
      vertices.push(vec3(pos.x - 1, pos.y + 1, pos.z + 1));
    }
  }

  return { vertices, center: pos };
}

export const GJKOps = {
  getSupportPoint,
  minkowskiSupport,
  gjk,
  bodyToConvexShape,
};
