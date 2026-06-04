import { Vec3, vec3Subtract, vec3Normalize, vec3Cross, vec3Dot, radToDeg } from '@/utils/math';

export function measureDihedral(
  a1: { x: number; y: number; z: number },
  a2: { x: number; y: number; z: number },
  a3: { x: number; y: number; z: number },
  a4: { x: number; y: number; z: number }
): number {
  const p1: Vec3 = [a1.x, a1.y, a1.z];
  const p2: Vec3 = [a2.x, a2.y, a2.z];
  const p3: Vec3 = [a3.x, a3.y, a3.z];
  const p4: Vec3 = [a4.x, a4.y, a4.z];

  const b1 = vec3Subtract([0, 0, 0], p2, p1);
  const b2 = vec3Subtract([0, 0, 0], p3, p2);
  const b3 = vec3Subtract([0, 0, 0], p4, p3);

  const n1 = vec3Cross([0, 0, 0], b1, b2);
  const n2 = vec3Cross([0, 0, 0], b2, b3);

  const b2Norm = vec3Normalize([0, 0, 0], b2);
  const m1 = vec3Cross([0, 0, 0], n1, b2Norm);

  const x = vec3Dot(n1, n2);
  const y = vec3Dot(m1, n2);

  return radToDeg(Math.atan2(y, x));
}
