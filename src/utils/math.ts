export type Vec3 = [number, number, number];
export type Vec4 = [number, number, number, number];
export type Mat4 = [
  number, number, number, number,
  number, number, number, number,
  number, number, number, number,
  number, number, number, number,
];

export function vec3Create(x = 0, y = 0, z = 0): Vec3 {
  return [x, y, z];
}

export function vec3Copy(a: Vec3): Vec3 {
  return [a[0], a[1], a[2]];
}

export function vec3Add(out: Vec3, a: Vec3, b: Vec3): Vec3 {
  out[0] = a[0] + b[0];
  out[1] = a[1] + b[1];
  out[2] = a[2] + b[2];
  return out;
}

export function vec3Subtract(out: Vec3, a: Vec3, b: Vec3): Vec3 {
  out[0] = a[0] - b[0];
  out[1] = a[1] - b[1];
  out[2] = a[2] - b[2];
  return out;
}

export function vec3Scale(out: Vec3, a: Vec3, s: number): Vec3 {
  out[0] = a[0] * s;
  out[1] = a[1] * s;
  out[2] = a[2] * s;
  return out;
}

export function vec3Normalize(out: Vec3, a: Vec3): Vec3 {
  const len = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
  if (len === 0) {
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    return out;
  }
  out[0] = a[0] / len;
  out[1] = a[1] / len;
  out[2] = a[2] / len;
  return out;
}

export function vec3Cross(out: Vec3, a: Vec3, b: Vec3): Vec3 {
  const ax = a[0], ay = a[1], az = a[2];
  const bx = b[0], by = b[1], bz = b[2];
  out[0] = ay * bz - az * by;
  out[1] = az * bx - ax * bz;
  out[2] = ax * by - ay * bx;
  return out;
}

export function vec3Dot(a: Vec3, b: Vec3): number {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

export function vec3Length(a: Vec3): number {
  return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
}

export function vec3Distance(a: Vec3, b: Vec3): number {
  const dx = a[0] - b[0];
  const dy = a[1] - b[1];
  const dz = a[2] - b[2];
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

export function vec3Lerp(out: Vec3, a: Vec3, b: Vec3, t: number): Vec3 {
  out[0] = a[0] + t * (b[0] - a[0]);
  out[1] = a[1] + t * (b[1] - a[1]);
  out[2] = a[2] + t * (b[2] - a[2]);
  return out;
}

export function vec3TransformMat4(out: Vec3, a: Vec3, m: Mat4): Vec3 {
  const x = a[0], y = a[1], z = a[2];
  const w = m[3] * x + m[7] * y + m[11] * z + m[15] || 1;
  out[0] = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w;
  out[1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w;
  out[2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w;
  return out;
}

export function vec4Create(x = 0, y = 0, z = 0, w = 1): Vec4 {
  return [x, y, z, w];
}

export function vec4Copy(a: Vec4): Vec4 {
  return [a[0], a[1], a[2], a[3]];
}

export function quatNormalize(out: Vec4, a: Vec4): Vec4 {
  const len = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3]);
  if (len === 0) {
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    out[3] = 1;
    return out;
  }
  out[0] = a[0] / len;
  out[1] = a[1] / len;
  out[2] = a[2] / len;
  out[3] = a[3] / len;
  return out;
}

export function quatMultiply(out: Vec4, a: Vec4, b: Vec4): Vec4 {
  const ax = a[0], ay = a[1], az = a[2], aw = a[3];
  const bx = b[0], by = b[1], bz = b[2], bw = b[3];
  out[0] = ax * bw + aw * bx + ay * bz - az * by;
  out[1] = ay * bw + aw * by + az * bx - ax * bz;
  out[2] = az * bw + aw * bz + ax * by - ay * bx;
  out[3] = aw * bw - ax * bx - ay * by - az * bz;
  return out;
}

export function quatFromAxisAngle(out: Vec4, axis: Vec3, angle: number): Vec4 {
  const halfAngle = angle / 2;
  const s = Math.sin(halfAngle);
  out[0] = axis[0] * s;
  out[1] = axis[1] * s;
  out[2] = axis[2] * s;
  out[3] = Math.cos(halfAngle);
  return out;
}

export function quatConjugate(out: Vec4, a: Vec4): Vec4 {
  out[0] = -a[0];
  out[1] = -a[1];
  out[2] = -a[2];
  out[3] = a[3];
  return out;
}

export function mat4Identity(): Mat4 {
  return [
    1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    0, 0, 0, 1,
  ];
}

export function mat4LookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
  const zx = eye[0] - target[0];
  const zy = eye[1] - target[1];
  const zz = eye[2] - target[2];
  let len = Math.sqrt(zx * zx + zy * zy + zz * zz);
  const fz = len === 0 ? [0, 0, 1] : [zx / len, zy / len, zz / len];

  const sx = up[1] * fz[2] - up[2] * fz[1];
  const sy = up[2] * fz[0] - up[0] * fz[2];
  const sz = up[0] * fz[1] - up[1] * fz[0];
  len = Math.sqrt(sx * sx + sy * sy + sz * sz);
  const fs = len === 0 ? [1, 0, 0] : [sx / len, sy / len, sz / len];

  const ux = fz[1] * fs[2] - fz[2] * fs[1];
  const uy = fz[2] * fs[0] - fz[0] * fs[2];
  const uz = fz[0] * fs[1] - fz[1] * fs[0];

  return [
    fs[0], ux, fz[0], 0,
    fs[1], uy, fz[1], 0,
    fs[2], uy, fz[2], 0,
    -(fs[0] * eye[0] + fs[1] * eye[1] + fs[2] * eye[2]),
    -(ux * eye[0] + uy * eye[1] + uz * eye[2]),
    -(fz[0] * eye[0] + fz[1] * eye[1] + fz[2] * eye[2]),
    1,
  ];
}

export function mat4Multiply(a: Mat4, b: Mat4): Mat4 {
  const out = new Array(16) as unknown as Mat4;
  for (let i = 0; i < 4; i++) {
    for (let j = 0; j < 4; j++) {
      out[j * 4 + i] =
        a[i] * b[j * 4] +
        a[4 + i] * b[j * 4 + 1] +
        a[8 + i] * b[j * 4 + 2] +
        a[12 + i] * b[j * 4 + 3];
    }
  }
  return out;
}

export function mat4FromQuat(q: Vec4): Mat4 {
  const x = q[0], y = q[1], z = q[2], w = q[3];
  const x2 = x + x, y2 = y + y, z2 = z + z;
  const xx = x * x2, xy = x * y2, xz = x * z2;
  const yy = y * y2, yz = y * z2, zz = z * z2;
  const wx = w * x2, wy = w * y2, wz = w * z2;
  return [
    1 - yy - zz, xy + wz, xz - wy, 0,
    xy - wz, 1 - xx - zz, yz + wx, 0,
    xz + wy, yz - wx, 1 - xx - yy, 0,
    0, 0, 0, 1,
  ];
}

export function mat4Translation(tx: number, ty: number, tz: number): Mat4 {
  return [
    1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    tx, ty, tz, 1,
  ];
}

export function mat4Invert(m: Mat4): Mat4 {
  const m00 = m[0], m01 = m[1], m02 = m[2], m03 = m[3];
  const m10 = m[4], m11 = m[5], m12 = m[6], m13 = m[7];
  const m20 = m[8], m21 = m[9], m22 = m[10], m23 = m[11];
  const m30 = m[12], m31 = m[13], m32 = m[14], m33 = m[15];
  const b00 = m00 * m11 - m01 * m10;
  const b01 = m00 * m12 - m02 * m10;
  const b02 = m00 * m13 - m03 * m10;
  const b03 = m01 * m12 - m02 * m11;
  const b04 = m01 * m13 - m03 * m11;
  const b05 = m02 * m13 - m03 * m12;
  const b06 = m20 * m31 - m21 * m30;
  const b07 = m20 * m32 - m22 * m30;
  const b08 = m20 * m33 - m23 * m30;
  const b09 = m21 * m32 - m22 * m31;
  const b10 = m21 * m33 - m23 * m31;
  const b11 = m22 * m33 - m23 * m32;
  let det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
  if (det === 0) return mat4Identity();
  det = 1.0 / det;
  return [
    (m11 * b11 - m12 * b10 + m13 * b09) * det,
    (m02 * b10 - m01 * b11 - m03 * b09) * det,
    (m31 * b05 - m32 * b04 + m33 * b03) * det,
    (m22 * b04 - m21 * b05 - m23 * b03) * det,
    (m12 * b08 - m10 * b11 - m13 * b07) * det,
    (m00 * b11 - m02 * b08 + m03 * b07) * det,
    (m32 * b02 - m30 * b05 - m33 * b01) * det,
    (m20 * b05 - m22 * b02 + m23 * b01) * det,
    (m10 * b10 - m11 * b08 + m13 * b06) * det,
    (m01 * b08 - m00 * b10 - m03 * b06) * det,
    (m30 * b04 - m31 * b02 + m33 * b00) * det,
    (m21 * b02 - m20 * b04 - m23 * b00) * det,
    (m11 * b07 - m10 * b09 - m12 * b06) * det,
    (m00 * b09 - m01 * b07 + m02 * b06) * det,
    (m31 * b01 - m30 * b03 - m32 * b00) * det,
    (m20 * b03 - m21 * b01 + m22 * b00) * det,
  ];
}

export function mat4Perspective(fovy: number, aspect: number, near: number, far: number): Mat4 {
  const f = 1.0 / Math.tan(fovy / 2);
  const nf = 1 / (near - far);
  return [
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) * nf, -1,
    0, 0, 2 * far * near * nf, 0,
  ];
}

export function degToRad(deg: number): number {
  return deg * Math.PI / 180;
}

export function radToDeg(rad: number): number {
  return rad * 180 / Math.PI;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
  return t * t * (3 - 2 * t);
}

export function smoothstep01(t: number): number {
  const clamped = Math.max(0, Math.min(1, t));
  return clamped * clamped * (3 - 2 * clamped);
}

export function quatSlerp(out: Vec4, a: Vec4, b: Vec4, t: number): Vec4 {
  let dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
  if (dot < 0) {
    dot = -dot;
    out[0] = -b[0]; out[1] = -b[1]; out[2] = -b[2]; out[3] = -b[3];
  } else {
    out[0] = b[0]; out[1] = b[1]; out[2] = b[2]; out[3] = b[3];
  }
  if (dot > 0.9995) {
    out[0] = a[0] + t * (out[0] - a[0]);
    out[1] = a[1] + t * (out[1] - a[1]);
    out[2] = a[2] + t * (out[2] - a[2]);
    out[3] = a[3] + t * (out[3] - a[3]);
    const len = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
    out[0] /= len; out[1] /= len; out[2] /= len; out[3] /= len;
    return out;
  }
  const theta0 = Math.acos(dot);
  const theta = theta0 * t;
  const sinTheta = Math.sin(theta);
  const sinTheta0 = Math.sin(theta0);
  const s0 = Math.cos(theta) - dot * sinTheta / sinTheta0;
  const s1 = sinTheta / sinTheta0;
  out[0] = a[0] * s0 + out[0] * s1;
  out[1] = a[1] * s0 + out[1] * s1;
  out[2] = a[2] * s0 + out[2] * s1;
  out[3] = a[3] * s0 + out[3] * s1;
  return out;
}
