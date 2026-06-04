import { Vec2, vec2 } from '@physics-sim/shared';

export function add(a: Vec2, b: Vec2): Vec2 {
  return vec2(a.x + b.x, a.y + b.y);
}

export function sub(a: Vec2, b: Vec2): Vec2 {
  return vec2(a.x - b.x, a.y - b.y);
}

export function mul(v: Vec2, s: number): Vec2 {
  return vec2(v.x * s, v.y * s);
}

export function div(v: Vec2, s: number): Vec2 {
  return vec2(v.x / s, v.y / s);
}

export function dot(a: Vec2, b: Vec2): number {
  return a.x * b.x + a.y * b.y;
}

export function cross(a: Vec2, b: Vec2): number {
  return a.x * b.y - a.y * b.x;
}

export function length(v: Vec2): number {
  return Math.sqrt(v.x * v.x + v.y * v.y);
}

export function lengthSq(v: Vec2): number {
  return v.x * v.x + v.y * v.y;
}

export function normalize(v: Vec2): Vec2 {
  const len = length(v);
  return len > 0 ? div(v, len) : vec2(0, 0);
}

export function distance(a: Vec2, b: Vec2): number {
  return length(sub(a, b));
}

export function distanceSq(a: Vec2, b: Vec2): number {
  return lengthSq(sub(a, b));
}

export function negate(v: Vec2): Vec2 {
  return vec2(-v.x, -v.y);
}

export function lerp(a: Vec2, b: Vec2, t: number): Vec2 {
  return vec2(
    a.x + (b.x - a.x) * t,
    a.y + (b.y - a.y) * t
  );
}

export const Vec2Ops = {
  add, sub, mul, div, dot, cross, length, lengthSq, normalize,
  distance, distanceSq, negate, lerp
};
