import type { Mat4, Vec3 } from '@/utils/math';

export interface Frustum {
  planes: [number, number, number, number][];
}

function normalizePlane(
  a: number,
  b: number,
  c: number,
  d: number,
): [number, number, number, number] {
  const len = Math.sqrt(a * a + b * b + c * c);
  if (len === 0) return [0, 0, 0, 0];
  return [a / len, b / len, c / len, d / len];
}

export function extractFrustum(viewProjection: Mat4): Frustum {
  const m = viewProjection;
  const planes: [number, number, number, number][] = [
    normalizePlane(m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]),
    normalizePlane(m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]),
    normalizePlane(m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]),
    normalizePlane(m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]),
    normalizePlane(m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]),
    normalizePlane(m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]),
  ];
  return { planes };
}

export function frustumContainsSphere(
  frustum: Frustum,
  center: Vec3,
  radius: number,
): boolean {
  for (const [a, b, c, d] of frustum.planes) {
    if (a * center[0] + b * center[1] + c * center[2] + d < -radius) {
      return false;
    }
  }
  return true;
}

export function frustumContainsPoint(frustum: Frustum, point: Vec3): boolean {
  for (const [a, b, c, d] of frustum.planes) {
    if (a * point[0] + b * point[1] + c * point[2] + d < 0) {
      return false;
    }
  }
  return true;
}
