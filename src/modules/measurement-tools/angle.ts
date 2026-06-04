import { Vec3, vec3Subtract, vec3Normalize, vec3Dot, radToDeg, clamp } from '@/utils/math';

export function measureAngle(
  a1: { x: number; y: number; z: number },
  a2: { x: number; y: number; z: number },
  a3: { x: number; y: number; z: number }
): number {
  const p1: Vec3 = [a1.x, a1.y, a1.z];
  const p2: Vec3 = [a2.x, a2.y, a2.z];
  const p3: Vec3 = [a3.x, a3.y, a3.z];

  const v1 = vec3Normalize([0, 0, 0], vec3Subtract([0, 0, 0], p2, p1));
  const v2 = vec3Normalize([0, 0, 0], vec3Subtract([0, 0, 0], p2, p3));

  const dot = clamp(vec3Dot(v1, v2), -1, 1);
  return radToDeg(Math.acos(dot));
}
