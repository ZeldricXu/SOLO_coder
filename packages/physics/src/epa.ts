import { Vec3, vec3 } from '@physics-sim/shared';
import { ConvexShape, CollisionPoint } from './types';
import { Vec3Ops } from '@physics-sim/math';
import { minkowskiSupport, getSupportPoint } from './gjk';

interface EPAFace {
  normal: Vec3;
  distance: number;
  vertices: Vec3[];
}

interface EPAEdge {
  a: Vec3;
  b: Vec3;
}

function computeFaceNormal(a: Vec3, b: Vec3, c: Vec3): Vec3 {
  const ab = Vec3Ops.sub(b, a);
  const ac = Vec3Ops.sub(c, a);
  return Vec3Ops.normalize(Vec3Ops.cross(ab, ac));
}

function pointPlaneDistance(point: Vec3, face: EPAFace): number {
  return Vec3Ops.dot(face.normal, Vec3Ops.sub(point, face.vertices[0]));
}

function findClosestFace(polytope: Vec3[]): { face: EPAFace; index: number } {
  let minDistance = Infinity;
  let closestFace: EPAFace | null = null;
  let closestIndex = 0;

  for (let i = 0; i < polytope.length; i += 3) {
    if (i + 2 >= polytope.length) break;
    
    const a = polytope[i];
    const b = polytope[i + 1];
    const c = polytope[i + 2];
    
    const normal = computeFaceNormal(a, b, c);
    const distance = Vec3Ops.dot(normal, a);
    
    if (Math.abs(distance) < minDistance) {
      minDistance = Math.abs(distance);
      closestFace = { normal, distance, vertices: [a, b, c] };
      closestIndex = i;
    }
  }

  if (!closestFace) {
    const a = polytope[0], b = polytope[1], c = polytope[2];
    const normal = computeFaceNormal(a, b, c);
    closestFace = { normal, distance: Vec3Ops.dot(normal, a), vertices: [a, b, c] };
  }

  return { face: closestFace, index: closestIndex };
}

function expandPolytope(
  shapeA: ConvexShape,
  shapeB: ConvexShape,
  polytope: Vec3[],
  closestFace: EPAFace
): { newVertices: Vec3[]; support: Vec3 } {
  const direction = closestFace.normal;
  const support = minkowskiSupport(shapeA, shapeB, direction);
  
  const newPolytope = [...polytope];
  
  const edgeMap = new Map<string, EPAEdge>();
  
  for (let i = 0; i < polytope.length; i += 3) {
    if (i + 2 >= polytope.length) break;
    
    const face = [polytope[i], polytope[i + 1], polytope[i + 2]];
    const normal = computeFaceNormal(face[0], face[1], face[2]);
    
    if (Vec3Ops.dot(normal, Vec3Ops.sub(support, face[0])) > 0) {
      const edges: EPAEdge[] = [
        { a: face[0], b: face[1] },
        { a: face[1], b: face[2] },
        { a: face[2], b: face[0] },
      ];
      
      for (const edge of edges) {
        const key = [
          edge.a.x, edge.a.y, edge.a.z,
          edge.b.x, edge.b.y, edge.b.z
        ].join(',');
        const reverseKey = [
          edge.b.x, edge.b.y, edge.b.z,
          edge.a.x, edge.a.y, edge.a.z
        ].join(',');
        
        if (edgeMap.has(reverseKey)) {
          edgeMap.delete(reverseKey);
        } else {
          edgeMap.set(key, edge);
        }
      }
      
      polytope.splice(i, 3);
      i -= 3;
    }
  }
  
  for (const edge of edgeMap.values()) {
    newPolytope.push(edge.a, edge.b, support);
  }
  
  return { newVertices: newPolytope, support };
}

export function epa(
  shapeA: ConvexShape,
  shapeB: ConvexShape,
  initialSimplex: Vec3[],
  maxIterations: number = 100,
  tolerance: number = 1e-10
): CollisionPoint | null {
  let polytope = [...initialSimplex];
  
  while (polytope.length < 4) {
    const dir = vec3(
      Math.random() - 0.5,
      Math.random() - 0.5,
      Math.random() - 0.5
    );
    if (Vec3Ops.length(dir) < 1e-10) continue;
    const support = minkowskiSupport(shapeA, shapeB, Vec3Ops.normalize(dir));
    polytope.push(support);
    if (polytope.length >= 4) break;
  }
  
  for (let i = 0; i < maxIterations; i++) {
    const { face: closestFace } = findClosestFace(polytope);
    const { newVertices, support } = expandPolytope(shapeA, shapeB, polytope, closestFace);
    
    const supportDistance = Vec3Ops.dot(closestFace.normal, support);
    
    if (Math.abs(supportDistance - closestFace.distance) < tolerance) {
      const pointOnB = Vec3Ops.mul(closestFace.normal, closestFace.distance * 0.5);
      const pointOnA = Vec3Ops.negate(pointOnB);
      
      return {
        point: Vec3Ops.mul(closestFace.normal, closestFace.distance * 0.5),
        normal: Vec3Ops.normalize(closestFace.normal),
        depth: Math.abs(closestFace.distance),
      };
    }
    
    polytope = newVertices;
  }
  
  const { face: closestFace } = findClosestFace(polytope);
  return {
    point: Vec3Ops.mul(closestFace.normal, closestFace.distance * 0.5),
    normal: Vec3Ops.normalize(closestFace.normal),
    depth: Math.abs(closestFace.distance),
  };
}

export function computeContactManifold(
  bodyA: ConvexShape,
  bodyB: ConvexShape,
  collisionNormal: Vec3,
  collisionPoint: Vec3,
  maxPoints: number = 4
): CollisionPoint[] {
  const points: CollisionPoint[] = [];
  
  points.push({
    point: collisionPoint,
    normal: collisionNormal,
    depth: Vec3Ops.dot(collisionNormal, collisionPoint),
  });
  
  const tangent1 = Vec3Ops.normalize(
    Math.abs(collisionNormal.y) < 0.9
      ? Vec3Ops.cross(collisionNormal, vec3(0, 1, 0))
      : Vec3Ops.cross(collisionNormal, vec3(1, 0, 0))
  );
  const tangent2 = Vec3Ops.cross(collisionNormal, tangent1);
  
  const margin = 0.01;
  const offsets = [
    vec3(margin, 0, 0),
    vec3(-margin, 0, 0),
    vec3(0, margin, 0),
    vec3(0, -margin, 0),
  ];
  
  for (let i = 1; i < maxPoints && i < offsets.length; i++) {
    const worldOffset = Vec3Ops.add(
      Vec3Ops.mul(tangent1, offsets[i].x),
      Vec3Ops.mul(tangent2, offsets[i].y)
    );
    
    const contactPoint = Vec3Ops.add(collisionPoint, worldOffset);
    const depth = Vec3Ops.dot(collisionNormal, contactPoint);
    
    if (depth > 0) {
      points.push({
        point: contactPoint,
        normal: collisionNormal,
        depth,
      });
    }
  }
  
  return points;
}

export const EPAOps = {
  epa,
  computeContactManifold,
};
