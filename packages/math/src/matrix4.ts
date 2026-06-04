import { Matrix4, Vec3, vec3 } from '@physics-sim/shared';

export function identity(): Matrix4 {
  return {
    m00: 1, m01: 0, m02: 0, m03: 0,
    m10: 0, m11: 1, m12: 0, m13: 0,
    m20: 0, m21: 0, m22: 1, m23: 0,
    m30: 0, m31: 0, m32: 0, m33: 1,
  };
}

export function multiply(a: Matrix4, b: Matrix4): Matrix4 {
  return {
    m00: a.m00 * b.m00 + a.m01 * b.m10 + a.m02 * b.m20 + a.m03 * b.m30,
    m01: a.m00 * b.m01 + a.m01 * b.m11 + a.m02 * b.m21 + a.m03 * b.m31,
    m02: a.m00 * b.m02 + a.m01 * b.m12 + a.m02 * b.m22 + a.m03 * b.m32,
    m03: a.m00 * b.m03 + a.m01 * b.m13 + a.m02 * b.m23 + a.m03 * b.m33,
    m10: a.m10 * b.m00 + a.m11 * b.m10 + a.m12 * b.m20 + a.m13 * b.m30,
    m11: a.m10 * b.m01 + a.m11 * b.m11 + a.m12 * b.m21 + a.m13 * b.m31,
    m12: a.m10 * b.m02 + a.m11 * b.m12 + a.m12 * b.m22 + a.m13 * b.m32,
    m13: a.m10 * b.m03 + a.m11 * b.m13 + a.m12 * b.m23 + a.m13 * b.m33,
    m20: a.m20 * b.m00 + a.m21 * b.m10 + a.m22 * b.m20 + a.m23 * b.m30,
    m21: a.m20 * b.m01 + a.m21 * b.m11 + a.m22 * b.m21 + a.m23 * b.m31,
    m22: a.m20 * b.m02 + a.m21 * b.m12 + a.m22 * b.m22 + a.m23 * b.m32,
    m23: a.m20 * b.m03 + a.m21 * b.m13 + a.m22 * b.m23 + a.m23 * b.m33,
    m30: a.m30 * b.m00 + a.m31 * b.m10 + a.m32 * b.m20 + a.m33 * b.m30,
    m31: a.m30 * b.m01 + a.m31 * b.m11 + a.m32 * b.m21 + a.m33 * b.m31,
    m32: a.m30 * b.m02 + a.m31 * b.m12 + a.m32 * b.m22 + a.m33 * b.m32,
    m33: a.m30 * b.m03 + a.m31 * b.m13 + a.m32 * b.m23 + a.m33 * b.m33,
  };
}

export function multiplyPoint(m: Matrix4, p: Vec3): Vec3 {
  const w = m.m30 * p.x + m.m31 * p.y + m.m32 * p.z + m.m33;
  return {
    x: (m.m00 * p.x + m.m01 * p.y + m.m02 * p.z + m.m03) / w,
    y: (m.m10 * p.x + m.m11 * p.y + m.m12 * p.z + m.m13) / w,
    z: (m.m20 * p.x + m.m21 * p.y + m.m22 * p.z + m.m23) / w,
  };
}

export function multiplyVector(m: Matrix4, v: Vec3): Vec3 {
  return {
    x: m.m00 * v.x + m.m01 * v.y + m.m02 * v.z,
    y: m.m10 * v.x + m.m11 * v.y + m.m12 * v.z,
    z: m.m20 * v.x + m.m21 * v.y + m.m22 * v.z,
  };
}

export function transpose(m: Matrix4): Matrix4 {
  return {
    m00: m.m00, m01: m.m10, m02: m.m20, m03: m.m30,
    m10: m.m01, m11: m.m11, m12: m.m21, m13: m.m31,
    m20: m.m02, m21: m.m12, m22: m.m22, m23: m.m32,
    m30: m.m03, m31: m.m13, m32: m.m23, m33: m.m33,
  };
}

export function determinant(m: Matrix4): number {
  return (
    m.m03 * m.m12 * m.m21 * m.m30 - m.m02 * m.m13 * m.m21 * m.m30 -
    m.m03 * m.m11 * m.m22 * m.m30 + m.m01 * m.m13 * m.m22 * m.m30 +
    m.m02 * m.m11 * m.m23 * m.m30 - m.m01 * m.m12 * m.m23 * m.m30 -
    m.m03 * m.m12 * m.m20 * m.m31 + m.m02 * m.m13 * m.m20 * m.m31 +
    m.m03 * m.m10 * m.m22 * m.m31 - m.m00 * m.m13 * m.m22 * m.m31 -
    m.m02 * m.m10 * m.m23 * m.m31 + m.m00 * m.m12 * m.m23 * m.m31 +
    m.m03 * m.m11 * m.m20 * m.m32 - m.m01 * m.m13 * m.m20 * m.m32 -
    m.m03 * m.m10 * m.m21 * m.m32 + m.m00 * m.m13 * m.m21 * m.m32 +
    m.m01 * m.m10 * m.m23 * m.m32 - m.m00 * m.m11 * m.m23 * m.m32 -
    m.m02 * m.m11 * m.m20 * m.m33 + m.m01 * m.m12 * m.m20 * m.m33 +
    m.m02 * m.m10 * m.m21 * m.m33 - m.m00 * m.m12 * m.m21 * m.m33 -
    m.m01 * m.m10 * m.m22 * m.m33 + m.m00 * m.m11 * m.m22 * m.m33
  );
}

export function inverse(m: Matrix4): Matrix4 | null {
  const det = determinant(m);
  if (Math.abs(det) < 1e-10) return null;

  const invDet = 1 / det;

  const result: Matrix4 = identity();

  result.m00 = (
    m.m12 * m.m23 * m.m31 - m.m13 * m.m22 * m.m31 +
    m.m13 * m.m21 * m.m32 - m.m11 * m.m23 * m.m32 -
    m.m12 * m.m21 * m.m33 + m.m11 * m.m22 * m.m33
  ) * invDet;

  result.m10 = (
    m.m13 * m.m22 * m.m30 - m.m12 * m.m23 * m.m30 -
    m.m13 * m.m20 * m.m32 + m.m10 * m.m23 * m.m32 +
    m.m12 * m.m20 * m.m33 - m.m10 * m.m22 * m.m33
  ) * invDet;

  result.m20 = (
    m.m11 * m.m23 * m.m30 - m.m13 * m.m21 * m.m30 +
    m.m13 * m.m20 * m.m31 - m.m10 * m.m23 * m.m31 -
    m.m11 * m.m20 * m.m33 + m.m10 * m.m21 * m.m33
  ) * invDet;

  result.m30 = (
    m.m12 * m.m21 * m.m30 - m.m11 * m.m22 * m.m30 -
    m.m12 * m.m20 * m.m31 + m.m10 * m.m22 * m.m31 +
    m.m11 * m.m20 * m.m32 - m.m10 * m.m21 * m.m32
  ) * invDet;

  result.m01 = (
    m.m03 * m.m22 * m.m31 - m.m02 * m.m23 * m.m31 -
    m.m03 * m.m21 * m.m32 + m.m01 * m.m23 * m.m32 +
    m.m02 * m.m21 * m.m33 - m.m01 * m.m22 * m.m33
  ) * invDet;

  result.m11 = (
    m.m02 * m.m23 * m.m30 - m.m03 * m.m22 * m.m30 +
    m.m03 * m.m20 * m.m32 - m.m00 * m.m23 * m.m32 -
    m.m02 * m.m20 * m.m33 + m.m00 * m.m22 * m.m33
  ) * invDet;

  result.m21 = (
    m.m03 * m.m21 * m.m30 - m.m01 * m.m23 * m.m30 -
    m.m03 * m.m20 * m.m31 + m.m00 * m.m23 * m.m31 +
    m.m01 * m.m20 * m.m33 - m.m00 * m.m21 * m.m33
  ) * invDet;

  result.m31 = (
    m.m01 * m.m22 * m.m30 - m.m02 * m.m21 * m.m30 +
    m.m02 * m.m20 * m.m31 - m.m00 * m.m22 * m.m31 -
    m.m01 * m.m20 * m.m32 + m.m00 * m.m21 * m.m32
  ) * invDet;

  result.m02 = (
    m.m02 * m.m13 * m.m31 - m.m03 * m.m12 * m.m31 +
    m.m03 * m.m11 * m.m32 - m.m01 * m.m13 * m.m32 -
    m.m02 * m.m11 * m.m33 + m.m01 * m.m12 * m.m33
  ) * invDet;

  result.m12 = (
    m.m03 * m.m12 * m.m30 - m.m02 * m.m13 * m.m30 -
    m.m03 * m.m10 * m.m32 + m.m00 * m.m13 * m.m32 +
    m.m02 * m.m10 * m.m33 - m.m00 * m.m12 * m.m33
  ) * invDet;

  result.m22 = (
    m.m01 * m.m13 * m.m30 - m.m03 * m.m11 * m.m30 +
    m.m03 * m.m10 * m.m31 - m.m00 * m.m13 * m.m31 -
    m.m01 * m.m10 * m.m33 + m.m00 * m.m11 * m.m33
  ) * invDet;

  result.m32 = (
    m.m02 * m.m11 * m.m30 - m.m01 * m.m12 * m.m30 -
    m.m02 * m.m10 * m.m31 + m.m00 * m.m12 * m.m31 +
    m.m01 * m.m10 * m.m32 - m.m00 * m.m11 * m.m32
  ) * invDet;

  result.m03 = (
    m.m03 * m.m12 * m.m21 - m.m02 * m.m13 * m.m21 -
    m.m03 * m.m11 * m.m22 + m.m01 * m.m13 * m.m22 +
    m.m02 * m.m11 * m.m23 - m.m01 * m.m12 * m.m23
  ) * invDet;

  result.m13 = (
    m.m02 * m.m13 * m.m20 - m.m03 * m.m12 * m.m20 +
    m.m03 * m.m10 * m.m22 - m.m00 * m.m13 * m.m22 -
    m.m02 * m.m10 * m.m23 + m.m00 * m.m12 * m.m23
  ) * invDet;

  result.m23 = (
    m.m03 * m.m11 * m.m20 - m.m01 * m.m13 * m.m20 -
    m.m03 * m.m10 * m.m21 + m.m00 * m.m13 * m.m21 +
    m.m01 * m.m10 * m.m23 - m.m00 * m.m11 * m.m23
  ) * invDet;

  result.m33 = (
    m.m01 * m.m12 * m.m20 - m.m02 * m.m11 * m.m20 +
    m.m02 * m.m10 * m.m21 - m.m00 * m.m12 * m.m21 -
    m.m01 * m.m10 * m.m22 + m.m00 * m.m11 * m.m22
  ) * invDet;

  return result;
}

export function translation(t: Vec3): Matrix4 {
  return {
    m00: 1, m01: 0, m02: 0, m03: t.x,
    m10: 0, m11: 1, m12: 0, m13: t.y,
    m20: 0, m21: 0, m22: 1, m23: t.z,
    m30: 0, m31: 0, m32: 0, m33: 1,
  };
}

export function scale(s: Vec3): Matrix4 {
  return {
    m00: s.x, m01: 0, m02: 0, m03: 0,
    m10: 0, m11: s.y, m12: 0, m13: 0,
    m20: 0, m21: 0, m22: s.z, m23: 0,
    m30: 0, m31: 0, m32: 0, m33: 1,
  };
}

export function lookAt(eye: Vec3, target: Vec3, up: Vec3): Matrix4 {
  const f = normalize({ x: target.x - eye.x, y: target.y - eye.y, z: target.z - eye.z });
  const s = normalize(cross(f, up));
  const u = cross(s, f);

  return {
    m00: s.x, m01: s.y, m02: s.z, m03: -dot(s, eye),
    m10: u.x, m11: u.y, m12: u.z, m13: -dot(u, eye),
    m20: -f.x, m21: -f.y, m22: -f.z, m23: dot(f, eye),
    m30: 0, m31: 0, m32: 0, m33: 1,
  };
}

function normalize(v: Vec3): Vec3 {
  const len = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
  return len > 0 ? { x: v.x / len, y: v.y / len, z: v.z / len } : { x: 0, y: 0, z: 0 };
}

function cross(a: Vec3, b: Vec3): Vec3 {
  return {
    x: a.y * b.z - a.z * b.y,
    y: a.z * b.x - a.x * b.z,
    z: a.x * b.y - a.y * b.x,
  };
}

function dot(a: Vec3, b: Vec3): number {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}

export const Matrix4Ops = {
  identity, multiply, multiplyPoint, multiplyVector, transpose, determinant, inverse, translation, scale, lookAt
};
