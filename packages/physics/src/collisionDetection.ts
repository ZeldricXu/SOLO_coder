import { Vec3 } from '@physics-sim/shared';
import { RigidBodyState, CollisionPair, CollisionPoint, AABB, ConvexShape } from './types';
import { aabbFromBody, aabbIntersect, broadPhase } from './aabb';
import { gjk, bodyToConvexShape } from './gjk';
import { epa } from './epa';
import { Vec3Ops } from '@physics-sim/math';

export interface CollisionDetectionResult {
  pairs: CollisionPair[];
  broadPhasePairs: number;
  narrowPhaseTests: number;
  detectionTime: number;
}

export function detectCollisions(
  bodies: Map<string, RigidBodyState>,
  gjkMaxIterations: number = 50,
  gjkTolerance: number = 1e-6,
  epaMaxIterations: number = 64,
  epaTolerance: number = 1e-6
): CollisionDetectionResult {
  const startTime = performance.now();
  
  const aabbs: AABB[] = [];
  const bodyArray: RigidBodyState[] = [];
  
  bodies.forEach((body) => {
    if (!body.isStatic || hasDynamicNeighbors(bodies, body)) {
      aabbs.push(aabbFromBody(body));
      bodyArray.push(body);
    }
  });
  
  const broadPhaseResult = broadPhase(aabbs);
  
  const collisionPairs: CollisionPair[] = [];
  let narrowPhaseTests = 0;
  
  for (const pair of broadPhaseResult) {
    const bodyA = bodies.get(pair.a);
    const bodyB = bodies.get(pair.b);
    
    if (!bodyA || !bodyB) continue;
    if (bodyA.isStatic && bodyB.isStatic) continue;
    
    narrowPhaseTests++;
    
    const shapeA = bodyToConvexShape(bodyA);
    const shapeB = bodyToConvexShape(bodyB);
    
    const gjkResult = gjk(shapeA, shapeB, gjkMaxIterations, gjkTolerance);
    
    if (gjkResult.isColliding) {
      const epaResult = epa(
        shapeA,
        shapeB,
        gjkResult.simplex,
        epaMaxIterations,
        epaTolerance
      );
      
      if (epaResult) {
        const manifold = generateContactManifold(
          bodyA,
          bodyB,
          epaResult,
          shapeA,
          shapeB
        );
        
        if (manifold.length > 0) {
          collisionPairs.push({
            bodyA: pair.a,
            bodyB: pair.b,
            points: manifold,
            isColliding: true,
          });
        }
      }
    }
  }
  
  const endTime = performance.now();
  
  return {
    pairs: collisionPairs,
    broadPhasePairs: broadPhaseResult.length,
    narrowPhaseTests,
    detectionTime: endTime - startTime,
  };
}

function hasDynamicNeighbors(
  bodies: Map<string, RigidBodyState>,
  body: RigidBodyState
): boolean {
  if (!body.isStatic) return true;
  
  for (const other of bodies.values()) {
    if (!other.isStatic) {
      const aabb1 = aabbFromBody(body);
      const aabb2 = aabbFromBody(other);
      if (aabbIntersect(aabb1, aabb2)) {
        return true;
      }
    }
  }
  return false;
}

export function generateContactManifold(
  bodyA: RigidBodyState,
  bodyB: RigidBodyState,
  collisionPoint: CollisionPoint,
  shapeA: ConvexShape,
  shapeB: ConvexShape,
  maxPoints: number = 4
): CollisionPoint[] {
  const manifold: CollisionPoint[] = [];
  
  const referenceFace = findReferenceFace(shapeA, collisionPoint.normal);
  const incidentFace = findIncidentFace(shapeB, Vec3Ops.mul(collisionPoint.normal, -1));
  
  if (!referenceFace || !incidentFace) {
    manifold.push(collisionPoint);
    return manifold;
  }
  
  const clippedPoints = clipIncidentFace(
    referenceFace,
    incidentFace,
    collisionPoint.normal
  );
  
  for (const point of clippedPoints) {
    const projectedPoint = projectPointToFace(point, referenceFace);
    const depth = Vec3Ops.dot(
      Vec3Ops.sub(point, projectedPoint),
      collisionPoint.normal
    );
    
    if (depth <= collisionPoint.depth + 1e-3) {
      manifold.push({
        point: projectedPoint,
        normal: collisionPoint.normal,
        depth: Math.max(depth, 0),
      });
    }
  }
  
  if (manifold.length === 0) {
    manifold.push(collisionPoint);
  }
  
  return manifold.slice(0, maxPoints);
}

interface Face {
  vertices: Vec3[];
  normal: Vec3;
  offset: number;
}

function findReferenceFace(shape: ConvexShape, normal: Vec3): Face | null {
  let maxDot = -Infinity;
  let bestFace: Face | null = null;
  
  const faces = getFacesFromShape(shape);
  
  for (const face of faces) {
    const dot = Vec3Ops.dot(face.normal, normal);
    if (dot > maxDot) {
      maxDot = dot;
      bestFace = face;
    }
  }
  
  return bestFace;
}

function findIncidentFace(shape: ConvexShape, normal: Vec3): Face | null {
  return findReferenceFace(shape, normal);
}

function getFacesFromShape(shape: ConvexShape): Face[] {
  const faces: Face[] = [];
  const vertices = shape.vertices;
  
  if (vertices.length < 3) return faces;
  
  const center = shape.center;
  
  for (let i = 0; i < vertices.length; i += 3) {
    const v0 = vertices[i];
    const v1 = vertices[(i + 1) % vertices.length];
    const v2 = vertices[(i + 2) % vertices.length];
    
    const edge1 = Vec3Ops.sub(v1, v0);
    const edge2 = Vec3Ops.sub(v2, v0);
    const normal = Vec3Ops.normalize(Vec3Ops.cross(edge1, edge2));
    
    const toCenter = Vec3Ops.sub(center, v0);
    if (Vec3Ops.dot(normal, toCenter) > 0) {
      normal.x = -normal.x;
      normal.y = -normal.y;
      normal.z = -normal.z;
    }
    
    const offset = -Vec3Ops.dot(normal, v0);
    
    faces.push({
      vertices: [v0, v1, v2],
      normal,
      offset,
    });
  }
  
  return faces;
}

function clipIncidentFace(
  referenceFace: Face,
  incidentFace: Face,
  normal: Vec3
): Vec3[] {
  let points = [...incidentFace.vertices];
  
  const referenceEdges = [
    [referenceFace.vertices[0], referenceFace.vertices[1]],
    [referenceFace.vertices[1], referenceFace.vertices[2]],
    [referenceFace.vertices[2], referenceFace.vertices[0]],
  ];
  
  for (const [v1, v2] of referenceEdges) {
    const edge = Vec3Ops.sub(v2, v1);
    const edgeNormal = Vec3Ops.normalize(
      Vec3Ops.cross(normal, edge)
    );
    
    const offset = -Vec3Ops.dot(edgeNormal, v1);
    
    points = clipPointsAgainstPlane(points, edgeNormal, offset);
    
    if (points.length < 2) break;
  }
  
  return points;
}

function clipPointsAgainstPlane(
  points: Vec3[],
  planeNormal: Vec3,
  planeOffset: number
): Vec3[] {
  const clipped: Vec3[] = [];
  
  for (let i = 0; i < points.length; i++) {
    const v1 = points[i];
    const v2 = points[(i + 1) % points.length];
    
    const d1 = Vec3Ops.dot(planeNormal, v1) + planeOffset;
    const d2 = Vec3Ops.dot(planeNormal, v2) + planeOffset;
    
    if (d1 >= 0) {
      clipped.push(v1);
    }
    
    if ((d1 >= 0) !== (d2 >= 0)) {
      const t = d1 / (d1 - d2);
      const intersection = Vec3Ops.add(
        v1,
        Vec3Ops.mul(Vec3Ops.sub(v2, v1), t)
      );
      clipped.push(intersection);
    }
  }
  
  return clipped;
}

function projectPointToFace(point: Vec3, face: Face): Vec3 {
  const distance = Vec3Ops.dot(face.normal, point) + face.offset;
  return Vec3Ops.sub(
    point,
    Vec3Ops.mul(face.normal, distance)
  );
}

export const CollisionDetection = {
  detectCollisions,
  generateContactManifold,
};
