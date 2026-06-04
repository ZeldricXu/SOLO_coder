import { Vec3 } from '@physics-sim/shared';
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
export declare function eulerStep(f: DerivativeFunction, t: number, y: StateVector, dt: number): StateVector;
export declare function implicitEulerStep(f: DerivativeFunction, t: number, y: StateVector, dt: number, tolerance?: number, maxIterations?: number): StateVector;
export declare function midpointStep(f: DerivativeFunction, t: number, y: StateVector, dt: number): StateVector;
export declare function rungeKutta4Step(f: DerivativeFunction, t: number, y: StateVector, dt: number): StateVector;
export declare function rungeKuttaFehlberg45Step(f: DerivativeFunction, t: number, y: StateVector, dt: number): {
    y4: StateVector;
    y5: StateVector;
    error: number;
};
export declare function adaptiveRKF45(f: DerivativeFunction, t0: number, y0: StateVector, tEnd: number, initialDt: number, tolerance?: number, minDt?: number, maxDt?: number): IntegrationResult[];
export declare function verletStep(positions: Vec3[], prevPositions: Vec3[], accelerations: Vec3[], dt: number): {
    positions: Vec3[];
    velocities: Vec3[];
};
export declare function velocityVerletStep(positions: Vec3[], velocities: Vec3[], accelerations: Vec3[], computeAccelerations: (positions: Vec3[]) => Vec3[], dt: number): {
    positions: Vec3[];
    velocities: Vec3[];
    accelerations: Vec3[];
};
export declare function bdf2Step(f: DerivativeFunction, t: number, y: StateVector, yPrev: StateVector, dt: number, tolerance?: number, maxIterations?: number): StateVector;
export declare const Integrators: {
    eulerStep: typeof eulerStep;
    implicitEulerStep: typeof implicitEulerStep;
    midpointStep: typeof midpointStep;
    rungeKutta4Step: typeof rungeKutta4Step;
    rungeKuttaFehlberg45Step: typeof rungeKuttaFehlberg45Step;
    adaptiveRKF45: typeof adaptiveRKF45;
    verletStep: typeof verletStep;
    velocityVerletStep: typeof velocityVerletStep;
    bdf2Step: typeof bdf2Step;
};
//# sourceMappingURL=integrators.d.ts.map