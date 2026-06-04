import { Vec4, vec4 } from '@physics-sim/shared';

export function add(a: Vec4, b: Vec4): Vec4 {
  return vec4(a.x + b.x, a.y + b.y, a.z + b.z, a.w + b.w);
}

export function sub(a: Vec4, b: Vec4): Vec4 {
  return vec4(a.x - b.x, a.y - b.y, a.z - b.z, a.w - b.w);
}

export function mul(v: Vec4, s: number): Vec4 {
  return vec4(v.x * s, v.y * s, v.z * s, v.w * s);
}

export function div(v: Vec4, s: number): Vec4 {
  return vec4(v.x / s, v.y / s, v.z / s, v.w / s);
}

export function dot(a: Vec4, b: Vec4): number {
  return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
}

export function length(v: Vec4): number {
  return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z + v.w * v.w);
}

export function lengthSq(v: Vec4): number {
  return v.x * v.x + v.y * v.y + v.z * v.z + v.w * v.w;
}

export function normalize(v: Vec4): Vec4 {
  const len = length(v);
  return len > 0 ? div(v, len) : vec4(0, 0, 0, 0);
}

export function lerp(a: Vec4, b: Vec4, t: number): Vec4 {
  return vec4(
    a.x + (b.x - a.x) * t,
    a.y + (b.y - a.y) * t,
    a.z + (b.z - a.z) * t,
    a.w + (b.w - a.w) * t
  );
}

export const Vec4Ops = {
  add, sub, mul, div, dot, length, lengthSq, normalize, lerp
};
