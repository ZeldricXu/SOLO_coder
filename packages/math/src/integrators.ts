import { Vec3, vec3 } from '@physics-sim/shared';
import * as Vec3Ops from './vec3';

export type StateVector = number[];
export type DerivativeFunction = (t: number, y: StateVector) => StateVector;
export type DerivativeFunctionVec3 = (t: number, y: Vec3[]) => Vec3[];

export interface IntegrationResult {
  y: StateVector;
  t: number;
  error: number;
  timeStep: number;
  iterations: number;
}

export function eulerStep(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  dt: number
): StateVector {
  const dydt = f(t, y);
  return y.map((yi, i) => yi + dydt[i] * dt);
}

export function implicitEulerStep(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  dt: number,
  tolerance: number = 1e-8,
  maxIterations: number = 100
): StateVector {
  let yNext = [...y];
  
  for (let iter = 0; iter < maxIterations; iter++) {
    const dydtNext = f(t + dt, yNext);
    const yPredicted = y.map((yi, i) => yi + dydtNext[i] * dt);
    
    let maxDiff = 0;
    for (let i = 0; i < y.length; i++) {
      maxDiff = Math.max(maxDiff, Math.abs(yPredicted[i] - yNext[i]));
    }
    
    yNext = yPredicted;
    
    if (maxDiff < tolerance) break;
  }
  
  return yNext;
}

export function midpointStep(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  dt: number
): StateVector {
  const k1 = f(t, y);
  const midY = y.map((yi, i) => yi + k1[i] * dt / 2);
  const k2 = f(t + dt / 2, midY);
  return y.map((yi, i) => yi + k2[i] * dt);
}

export function rungeKutta4Step(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  dt: number
): StateVector {
  const k1 = f(t, y);
  const k2 = f(t + dt / 2, y.map((yi, i) => yi + k1[i] * dt / 2));
  const k3 = f(t + dt / 2, y.map((yi, i) => yi + k2[i] * dt / 2));
  const k4 = f(t + dt, y.map((yi, i) => yi + k3[i] * dt));
  
  return y.map((yi, i) => yi + (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) * dt / 6);
}

export function rungeKuttaFehlberg45Step(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  dt: number
): { y4: StateVector; y5: StateVector; error: number } {
  const a2 = 1/4, a3 = 3/8, a4 = 12/13, a5 = 1, a6 = 1/2;
  const b21 = 1/4;
  const b31 = 3/32, b32 = 9/32;
  const b41 = 1932/2197, b42 = -7200/2197, b43 = 7296/2197;
  const b51 = 439/216, b52 = -8, b53 = 3680/513, b54 = -845/4104;
  const b61 = -8/27, b62 = 2, b63 = -3544/2565, b64 = 1859/4104, b65 = -11/40;
  
  const c1 = 16/135, c3 = 6656/12825, c4 = 28561/56430, c5 = -9/50, c6 = 2/55;
  const cs1 = 25/216, cs3 = 1408/2565, cs4 = 2197/4104, cs5 = -1/5;

  const n = y.length;
  const k1 = f(t, y);
  
  const y2 = y.map((yi, i) => yi + b21 * k1[i] * dt);
  const k2 = f(t + a2 * dt, y2);
  
  const y3 = y.map((yi, i) => yi + (b31 * k1[i] + b32 * k2[i]) * dt);
  const k3 = f(t + a3 * dt, y3);
  
  const y4Temp = y.map((yi, i) => yi + (b41 * k1[i] + b42 * k2[i] + b43 * k3[i]) * dt);
  const k4 = f(t + a4 * dt, y4Temp);
  
  const y5Temp = y.map((yi, i) => yi + (b51 * k1[i] + b52 * k2[i] + b53 * k3[i] + b54 * k4[i]) * dt);
  const k5 = f(t + a5 * dt, y5Temp);
  
  const y6 = y.map((yi, i) => yi + (b61 * k1[i] + b62 * k2[i] + b63 * k3[i] + b64 * k4[i] + b65 * k5[i]) * dt);
  const k6 = f(t + a6 * dt, y6);
  
  const y4 = new Array(n);
  const y5 = new Array(n);
  let error = 0;
  
  for (let i = 0; i < n; i++) {
    y4[i] = y[i] + (cs1 * k1[i] + cs3 * k3[i] + cs4 * k4[i] + cs5 * k5[i]) * dt;
    y5[i] = y[i] + (c1 * k1[i] + c3 * k3[i] + c4 * k4[i] + c5 * k5[i] + c6 * k6[i]) * dt;
    error += Math.pow(y5[i] - y4[i], 2);
  }
  
  error = Math.sqrt(error / n);
  
  return { y4, y5, error };
}

export function adaptiveRKF45(
  f: DerivativeFunction,
  t0: number,
  y0: StateVector,
  tEnd: number,
  initialDt: number,
  tolerance: number = 1e-6,
  minDt: number = 1e-10,
  maxDt: number = 1
): IntegrationResult[] {
  const results: IntegrationResult[] = [];
  let t = t0;
  let y = [...y0];
  let dt = initialDt;
  let iterations = 0;

  while (t < tEnd) {
    if (t + dt > tEnd) dt = tEnd - t;

    const { y4, y5, error } = rungeKuttaFehlberg45Step(f, t, y, dt);

    if (error <= tolerance || dt <= minDt) {
      results.push({ y: y5, t: t + dt, error, timeStep: dt, iterations });
      t += dt;
      y = y5;
      iterations = 0;
    }

    const scale = 0.9 * Math.pow(tolerance / Math.max(error, 1e-15), 0.2);
    dt = Math.max(minDt, Math.min(maxDt, dt * Math.max(0.5, Math.min(2, scale))));
    iterations++;
  }

  return results;
}

export function verletStep(
  positions: Vec3[],
  prevPositions: Vec3[],
  accelerations: Vec3[],
  dt: number
): { positions: Vec3[]; velocities: Vec3[] } {
  const newPositions = new Array(positions.length);
  const velocities = new Array(positions.length);

  for (let i = 0; i < positions.length; i++) {
    const p = positions[i];
    const pp = prevPositions[i];
    const a = accelerations[i];
    
    newPositions[i] = vec3(
      2 * p.x - pp.x + a.x * dt * dt,
      2 * p.y - pp.y + a.y * dt * dt,
      2 * p.z - pp.z + a.z * dt * dt
    );
    
    velocities[i] = vec3(
      (newPositions[i].x - pp.x) / (2 * dt),
      (newPositions[i].y - pp.y) / (2 * dt),
      (newPositions[i].z - pp.z) / (2 * dt)
    );
  }

  return { positions: newPositions, velocities };
}

export function velocityVerletStep(
  positions: Vec3[],
  velocities: Vec3[],
  accelerations: Vec3[],
  computeAccelerations: (positions: Vec3[]) => Vec3[],
  dt: number
): { positions: Vec3[]; velocities: Vec3[]; accelerations: Vec3[] } {
  const halfDt = dt * 0.5;
  const newPositions = new Array(positions.length);
  const newVelocities = new Array(positions.length);

  for (let i = 0; i < positions.length; i++) {
    newPositions[i] = vec3(
      positions[i].x + velocities[i].x * dt + 0.5 * accelerations[i].x * dt * dt,
      positions[i].y + velocities[i].y * dt + 0.5 * accelerations[i].y * dt * dt,
      positions[i].z + velocities[i].z * dt + 0.5 * accelerations[i].z * dt * dt
    );
  }

  const newAccelerations = computeAccelerations(newPositions);

  for (let i = 0; i < positions.length; i++) {
    newVelocities[i] = vec3(
      velocities[i].x + 0.5 * (accelerations[i].x + newAccelerations[i].x) * dt,
      velocities[i].y + 0.5 * (accelerations[i].y + newAccelerations[i].y) * dt,
      velocities[i].z + 0.5 * (accelerations[i].z + newAccelerations[i].z) * dt
    );
  }

  return { positions: newPositions, velocities: newVelocities, accelerations: newAccelerations };
}

export function bdf2Step(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  yPrev: StateVector,
  dt: number,
  tolerance: number = 1e-8,
  maxIterations: number = 100
): StateVector {
  let yNext = [...y];
  
  for (let iter = 0; iter < maxIterations; iter++) {
    const dydtNext = f(t + dt, yNext);
    const yPredicted = y.map((yi, i) => {
      return (4 * yi - yPrev[i] + 2 * dt * dydtNext[i]) / 3;
    });
    
    let maxDiff = 0;
    for (let i = 0; i < y.length; i++) {
      maxDiff = Math.max(maxDiff, Math.abs(yPredicted[i] - yNext[i]));
    }
    
    yNext = yPredicted;
    
    if (maxDiff < tolerance) break;
  }
  
  return yNext;
}

export const Integrators = {
  eulerStep,
  implicitEulerStep,
  midpointStep,
  rungeKutta4Step,
  rungeKuttaFehlberg45Step,
  adaptiveRKF45,
  verletStep,
  velocityVerletStep,
  bdf2Step,
};
