import { Vec3, vec3 } from '@physics-sim/shared';
import * as Vec3Ops from './vec3';
import { Matrix3 } from '@physics-sim/shared';

export interface Quaternion {
  x: number;
  y: number;
  z: number;
  w: number;
}

export function quaternion(x: number = 0, y: number = 0, z: number = 0, w: number = 1): Quaternion {
  return { x, y, z, w };
}

export function identity(): Quaternion {
  return { x: 0, y: 0, z: 0, w: 1 };
}

export function fromAxisAngle(axis: Vec3, angle: number): Quaternion {
  const halfAngle = angle * 0.5;
  const s = Math.sin(halfAngle);
  const c = Math.cos(halfAngle);
  const n = Vec3Ops.normalize(axis);
  return { x: n.x * s, y: n.y * s, z: n.z * s, w: c };
}

export function fromEuler(euler: Vec3): Quaternion {
  const cx = Math.cos(euler.x * 0.5), sx = Math.sin(euler.x * 0.5);
  const cy = Math.cos(euler.y * 0.5), sy = Math.sin(euler.y * 0.5);
  const cz = Math.cos(euler.z * 0.5), sz = Math.sin(euler.z * 0.5);

  return {
    x: sx * cy * cz - cx * sy * sz,
    y: cx * sy * cz + sx * cy * sz,
    z: cx * cy * sz - sx * sy * cz,
    w: cx * cy * cz + sx * sy * sz,
  };
}

export function fromMatrix3(m: Matrix3): Quaternion {
  const trace = m.m00 + m.m11 + m.m22;
  let q: Quaternion = identity();

  if (trace > 0) {
    const s = 0.5 / Math.sqrt(trace + 1);
    q.w = 0.25 / s;
    q.x = (m.m21 - m.m12) * s;
    q.y = (m.m02 - m.m20) * s;
    q.z = (m.m10 - m.m01) * s;
  } else if (m.m00 > m.m11 && m.m00 > m.m22) {
    const s = 2 * Math.sqrt(1 + m.m00 - m.m11 - m.m22);
    q.w = (m.m21 - m.m12) / s;
    q.x = 0.25 * s;
    q.y = (m.m01 + m.m10) / s;
    q.z = (m.m02 + m.m20) / s;
  } else if (m.m11 > m.m22) {
    const s = 2 * Math.sqrt(1 + m.m11 - m.m00 - m.m22);
    q.w = (m.m02 - m.m20) / s;
    q.x = (m.m01 + m.m10) / s;
    q.y = 0.25 * s;
    q.z = (m.m12 + m.m21) / s;
  } else {
    const s = 2 * Math.sqrt(1 + m.m22 - m.m00 - m.m11);
    q.w = (m.m10 - m.m01) / s;
    q.x = (m.m02 + m.m20) / s;
    q.y = (m.m12 + m.m21) / s;
    q.z = 0.25 * s;
  }

  return q;
}

export function multiply(a: Quaternion, b: Quaternion): Quaternion {
  return {
    x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
  };
}

export function rotate(q: Quaternion, v: Vec3): Vec3 {
  const qv = { x: v.x, y: v.y, z: v.z, w: 0 };
  const qInv = conjugate(q);
  const result = multiply(multiply(q, qv), qInv);
  return { x: result.x, y: result.y, z: result.z };
}

export function conjugate(q: Quaternion): Quaternion {
  return { x: -q.x, y: -q.y, z: -q.z, w: q.w };
}

export function inverse(q: Quaternion): Quaternion {
  const lenSq = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w;
  if (lenSq === 0) return identity();
  return {
    x: -q.x / lenSq,
    y: -q.y / lenSq,
    z: -q.z / lenSq,
    w: q.w / lenSq,
  };
}

export function normalize(q: Quaternion): Quaternion {
  const len = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
  if (len === 0) return identity();
  return {
    x: q.x / len,
    y: q.y / len,
    z: q.z / len,
    w: q.w / len,
  };
}

export function dot(a: Quaternion, b: Quaternion): number {
  return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
}

export function slerp(a: Quaternion, b: Quaternion, t: number): Quaternion {
  let d = dot(a, b);
  let bCopy = b;

  if (d < 0) {
    d = -d;
    bCopy = { x: -b.x, y: -b.y, z: -b.z, w: -b.w };
  }

  if (d > 0.9995) {
    return normalize({
      x: a.x + (bCopy.x - a.x) * t,
      y: a.y + (bCopy.y - a.y) * t,
      z: a.z + (bCopy.z - a.z) * t,
      w: a.w + (bCopy.w - a.w) * t,
    });
  }

  const angle = Math.acos(d);
  const sinAngle = Math.sin(angle);
  const ratioA = Math.sin((1 - t) * angle) / sinAngle;
  const ratioB = Math.sin(t * angle) / sinAngle;

  return {
    x: a.x * ratioA + bCopy.x * ratioB,
    y: a.y * ratioA + bCopy.y * ratioB,
    z: a.z * ratioA + bCopy.z * ratioB,
    w: a.w * ratioA + bCopy.w * ratioB,
  };
}

export function toEuler(q: Quaternion): Vec3 {
  const sinr_cosp = 2 * (q.w * q.x + q.y * q.z);
  const cosr_cosp = 1 - 2 * (q.x * q.x + q.y * q.y);
  const x = Math.atan2(sinr_cosp, cosr_cosp);

  const sinp = 2 * (q.w * q.y - q.z * q.x);
  let y = Math.asin(Math.min(1, Math.max(-1, sinp)));

  const siny_cosp = 2 * (q.w * q.z + q.x * q.y);
  const cosy_cosp = 1 - 2 * (q.y * q.y + q.z * q.z);
  const z = Math.atan2(siny_cosp, cosy_cosp);

  return { x, y, z };
}

export const QuaternionOps = {
  quaternion, identity, fromAxisAngle, fromEuler, fromMatrix3,
  multiply, rotate, conjugate, inverse, normalize, dot, slerp, toEuler
};
