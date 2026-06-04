import { Vec3, vec3 } from '@physics-sim/shared';

export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

export function lerpVec3(a: Vec3, b: Vec3, t: number): Vec3 {
  return vec3(
    a.x + (b.x - a.x) * t,
    a.y + (b.y - a.y) * t,
    a.z + (b.z - a.z) * t
  );
}

export function bilinear(
  q00: number, q10: number, q01: number, q11: number,
  tx: number, ty: number
): number {
  const a = lerp(q00, q10, tx);
  const b = lerp(q01, q11, tx);
  return lerp(a, b, ty);
}

export function trilinear(
  q000: number, q100: number, q010: number, q110: number,
  q001: number, q101: number, q011: number, q111: number,
  tx: number, ty: number, tz: number
): number {
  const c00 = lerp(q000, q100, tx);
  const c10 = lerp(q010, q110, tx);
  const c01 = lerp(q001, q101, tx);
  const c11 = lerp(q011, q111, tx);
  
  const c0 = lerp(c00, c10, ty);
  const c1 = lerp(c01, c11, ty);
  
  return lerp(c0, c1, tz);
}

export interface LagrangeInterpolator {
  x: number[];
  y: number[];
  n: number;
}

export function createLagrangeInterpolator(x: number[], y: number[]): LagrangeInterpolator {
  return { x: [...x], y: [...y], n: x.length };
}

export function evaluateLagrange(interp: LagrangeInterpolator, x: number): number {
  let result = 0;
  const { x: xs, y: ys, n } = interp;

  for (let i = 0; i < n; i++) {
    let term = ys[i];
    for (let j = 0; j < n; j++) {
      if (j !== i) {
        term *= (x - xs[j]) / (xs[i] - xs[j]);
      }
    }
    result += term;
  }

  return result;
}

export interface CubicSpline {
  x: number[];
  y: number[];
  a: number[];
  b: number[];
  c: number[];
  d: number[];
  n: number;
}

export function createCubicSpline(x: number[], y: number[], natural: boolean = true): CubicSpline {
  const n = x.length - 1;
  const h = new Array(n);
  const alpha = new Array(n);
  
  for (let i = 0; i < n; i++) {
    h[i] = x[i + 1] - x[i];
  }
  
  for (let i = 1; i < n; i++) {
    alpha[i] = 3 * (y[i + 1] - y[i]) / h[i] - 3 * (y[i] - y[i - 1]) / h[i - 1];
  }
  
  const a = [...y];
  const b = new Array(n);
  const c = new Array(n + 1);
  const d = new Array(n);
  const l = new Array(n + 1);
  const mu = new Array(n + 1);
  const z = new Array(n + 1);
  
  if (natural) {
    l[0] = 1;
    mu[0] = 0;
    z[0] = 0;
    l[n] = 1;
    z[n] = 0;
    c[n] = 0;
  } else {
    l[0] = 2 * h[0];
    mu[0] = 0.5;
    z[0] = alpha[1] ? 3 * (y[1] - y[0]) / h[0] : 0;
  }
  
  for (let i = 1; i < n; i++) {
    l[i] = 2 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1];
    mu[i] = h[i] / l[i];
    z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
  }
  
  for (let j = n - 1; j >= 0; j--) {
    c[j] = z[j] - mu[j] * c[j + 1];
    b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3;
    d[j] = (c[j + 1] - c[j]) / (3 * h[j]);
  }
  
  return { x, y, a, b, c, d, n };
}

export function evaluateCubicSpline(spline: CubicSpline, x: number): number {
  const { x: xs, a, b, c, d, n } = spline;
  
  if (x <= xs[0]) return a[0];
  if (x >= xs[n]) return a[n];
  
  let low = 0, high = n;
  while (high - low > 1) {
    const mid = Math.floor((low + high) / 2);
    if (xs[mid] <= x) low = mid;
    else high = mid;
  }
  
  const dx = x - xs[low];
  return a[low] + b[low] * dx + c[low] * dx * dx + d[low] * dx * dx * dx;
}

export interface AkimaSpline {
  x: number[];
  y: number[];
  m: number[];
  n: number;
}

export function createAkimaSpline(x: number[], y: number[]): AkimaSpline {
  const n = x.length;
  const m = new Array(n);
  const delta = new Array(n + 3);
  
  for (let i = 0; i < n - 1; i++) {
    delta[i + 2] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
  }
  
  delta[0] = 3 * delta[2] - 2 * delta[3];
  delta[1] = 2 * delta[2] - delta[3];
  delta[n] = 2 * delta[n - 1] - delta[n - 2];
  delta[n + 1] = 3 * delta[n - 1] - 2 * delta[n - 2];
  
  for (let i = 0; i < n; i++) {
    const d1 = delta[i + 1] - delta[i];
    const d2 = delta[i + 3] - delta[i + 2];
    const w1 = Math.abs(d2);
    const w2 = Math.abs(d1);
    
    if (w1 + w2 > 1e-15) {
      m[i] = (w1 * delta[i + 1] + w2 * delta[i + 2]) / (w1 + w2);
    } else {
      m[i] = (delta[i + 1] + delta[i + 2]) / 2;
    }
  }
  
  return { x, y, m, n };
}

export function evaluateAkimaSpline(spline: AkimaSpline, x: number): number {
  const { x: xs, y, m, n } = spline;
  
  if (x <= xs[0]) return y[0];
  if (x >= xs[n - 1]) return y[n - 1];
  
  let low = 0, high = n - 1;
  while (high - low > 1) {
    const mid = Math.floor((low + high) / 2);
    if (xs[mid] <= x) low = mid;
    else high = mid;
  }
  
  const h = xs[low + 1] - xs[low];
  const t = (x - xs[low]) / h;
  const h00 = 2 * t * t * t - 3 * t * t + 1;
  const h10 = t * t * t - 2 * t * t + t;
  const h01 = -2 * t * t * t + 3 * t * t;
  const h11 = t * t * t - t * t;
  
  return h00 * y[low] + h10 * h * m[low] + h01 * y[low + 1] + h11 * h * m[low + 1];
}

export function nearestNeighbor(x: number[], y: number[], xi: number): number {
  let minDist = Infinity;
  let result = y[0];
  
  for (let i = 0; i < x.length; i++) {
    const dist = Math.abs(x[i] - xi);
    if (dist < minDist) {
      minDist = dist;
      result = y[i];
    }
  }
  
  return result;
}

export const Interpolation = {
  lerp,
  lerpVec3,
  bilinear,
  trilinear,
  createLagrangeInterpolator,
  evaluateLagrange,
  createCubicSpline,
  evaluateCubicSpline,
  createAkimaSpline,
  evaluateAkimaSpline,
  nearestNeighbor,
};
