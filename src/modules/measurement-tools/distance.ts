import { vec3Distance } from '@/utils/math';

export function measureDistance(
  a1: { x: number; y: number; z: number },
  a2: { x: number; y: number; z: number }
): number {
  return vec3Distance([a1.x, a1.y, a1.z], [a2.x, a2.y, a2.z]);
}
