import { Matrix3 } from '@physics-sim/shared';
import { Vec3 } from '@physics-sim/shared';
import * as Vec3Ops from './vec3';

export function identity(): Matrix3 {
  return {
    m00: 1, m01: 0, m02: 0,
    m10: 0, m11: 1, m12: 0,
    m20: 0, m21: 0, m22: 1,
  };
}

export function multiply(a: Matrix3, b: Matrix3): Matrix3 {
  return {
    m00: a.m00 * b.m00 + a.m01 * b.m10 + a.m02 * b.m20,
    m01: a.m00 * b.m01 + a.m01 * b.m11 + a.m02 * b.m21,
    m02: a.m00 * b.m02 + a.m01 * b.m12 + a.m02 * b.m22,
    m10: a.m10 * b.m00 + a.m11 * b.m10 + a.m12 * b.m20,
    m11: a.m10 * b.m01 + a.m11 * b.m11 + a.m12 * b.m21,
    m12: a.m10 * b.m02 + a.m11 * b.m12 + a.m12 * b.m22,
    m20: a.m20 * b.m00 + a.m21 * b.m10 + a.m22 * b.m20,
    m21: a.m20 * b.m01 + a.m21 * b.m11 + a.m22 * b.m21,
    m22: a.m20 * b.m02 + a.m21 * b.m12 + a.m22 * b.m22,
  };
}

export function multiplyVector(m: Matrix3, v: Vec3): Vec3 {
  return {
    x: m.m00 * v.x + m.m01 * v.y + m.m02 * v.z,
    y: m.m10 * v.x + m.m11 * v.y + m.m12 * v.z,
    z: m.m20 * v.x + m.m21 * v.y + m.m22 * v.z,
  };
}

export function transpose(m: Matrix3): Matrix3 {
  return {
    m00: m.m00, m01: m.m10, m02: m.m20,
    m10: m.m01, m11: m.m11, m12: m.m21,
    m20: m.m02, m21: m.m12, m22: m.m22,
  };
}

export function determinant(m: Matrix3): number {
  return (
    m.m00 * (m.m11 * m.m22 - m.m12 * m.m21) -
    m.m01 * (m.m10 * m.m22 - m.m12 * m.m20) +
    m.m02 * (m.m10 * m.m21 - m.m11 * m.m20)
  );
}

export function inverse(m: Matrix3): Matrix3 | null {
  const det = determinant(m);
  if (Math.abs(det) < 1e-10) return null;

  const invDet = 1 / det;

  return {
    m00: (m.m11 * m.m22 - m.m12 * m.m21) * invDet,
    m01: (m.m02 * m.m21 - m.m01 * m.m22) * invDet,
    m02: (m.m01 * m.m12 - m.m02 * m.m11) * invDet,
    m10: (m.m12 * m.m20 - m.m10 * m.m22) * invDet,
    m11: (m.m00 * m.m22 - m.m02 * m.m20) * invDet,
    m12: (m.m02 * m.m10 - m.m00 * m.m12) * invDet,
    m20: (m.m10 * m.m21 - m.m11 * m.m20) * invDet,
    m21: (m.m01 * m.m20 - m.m00 * m.m21) * invDet,
    m22: (m.m00 * m.m11 - m.m01 * m.m10) * invDet,
  };
}

export function fromEuler(euler: Vec3): Matrix3 {
  const cx = Math.cos(euler.x), sx = Math.sin(euler.x);
  const cy = Math.cos(euler.y), sy = Math.sin(euler.y);
  const cz = Math.cos(euler.z), sz = Math.sin(euler.z);

  return {
    m00: cy * cz,
    m01: cz * sx * sy - cx * sz,
    m02: cx * cz * sy + sx * sz,
    m10: cy * sz,
    m11: cx * cz + sx * sy * sz,
    m12: -cz * sx + cx * sy * sz,
    m20: -sy,
    m21: cy * sx,
    m22: cx * cy,
  };
}

export function scale(s: Vec3): Matrix3 {
  return {
    m00: s.x, m01: 0, m02: 0,
    m10: 0, m11: s.y, m12: 0,
    m20: 0, m21: 0, m22: s.z,
  };
}

export const Matrix3Ops = {
  identity, multiply, multiplyVector, transpose, determinant, inverse, fromEuler, scale
};
