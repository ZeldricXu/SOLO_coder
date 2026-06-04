import { Vec3, vec3 } from '@physics-sim/shared';

export function add(a: Vec3, b: Vec3): Vec3 {
  return vec3(a.x + b.x, a.y + b.y, a.z + b.z);
}

export function sub(a: Vec3, b: Vec3): Vec3 {
  return vec3(a.x - b.x, a.y - b.y, a.z - b.z);
}

export function mul(v: Vec3, s: number): Vec3 {
  return vec3(v.x * s, v.y * s, v.z * s);
}

export function div(v: Vec3, s: number): Vec3 {
  return vec3(v.x / s, v.y / s, v.z / s);
}

export function dot(a: Vec3, b: Vec3): number {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}

export function cross(a: Vec3, b: Vec3): Vec3 {
  return vec3(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x
  );
}

export function length(v: Vec3): number {
  return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
}

export function lengthSq(v: Vec3): number {
  return v.x * v.x + v.y * v.y + v.z * v.z;
}

export function normalize(v: Vec3): Vec3 {
  const len = length(v);
  return len > 0 ? div(v, len) : vec3(0, 0, 0);
}

export function distance(a: Vec3, b: Vec3): number {
  return length(sub(a, b));
}

export function distanceSq(a: Vec3, b: Vec3): number {
  return lengthSq(sub(a, b));
}

export function negate(v: Vec3): Vec3 {
  return vec3(-v.x, -v.y, -v.z);
}

export function lerp(a: Vec3, b: Vec3, t: number): Vec3 {
  return vec3(
    a.x + (b.x - a.x) * t,
    a.y + (b.y - a.y) * t,
    a.z + (b.z - a.z) * t
  );
}

export function reflect(v: Vec3, n: Vec3): Vec3 {
  const d = dot(v, n);
  return sub(v, mul(n, 2 * d));
}

export function project(a: Vec3, b: Vec3): Vec3 {
  return mul(b, dot(a, b) / dot(b, b));
}

export function angle(a: Vec3, b: Vec3): number {
  return Math.acos(Math.max(-1, Math.min(1, dot(a, b) / (length(a) * length(b)))));
}

export function min(a: Vec3, b: Vec3): Vec3 {
  return vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
}

export function max(a: Vec3, b: Vec3): Vec3 {
  return vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
}

export function abs(v: Vec3): Vec3 {
  return vec3(Math.abs(v.x), Math.abs(v.y), Math.abs(v.z));
}

export const Vec3Ops = {
  add, sub, mul, div, dot, cross, length, lengthSq, normalize,
  distance, distanceSq, negate, lerp, reflect, project, angle, min, max, abs
};
