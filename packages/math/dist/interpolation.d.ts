import { Vec3 } from '@physics-sim/shared';
export declare function lerp(a: number, b: number, t: number): number;
export declare function lerpVec3(a: Vec3, b: Vec3, t: number): Vec3;
export declare function bilinear(q00: number, q10: number, q01: number, q11: number, tx: number, ty: number): number;
export declare function trilinear(q000: number, q100: number, q010: number, q110: number, q001: number, q101: number, q011: number, q111: number, tx: number, ty: number, tz: number): number;
export interface LagrangeInterpolator {
    x: number[];
    y: number[];
    n: number;
}
export declare function createLagrangeInterpolator(x: number[], y: number[]): LagrangeInterpolator;
export declare function evaluateLagrange(interp: LagrangeInterpolator, x: number): number;
export interface CubicSpline {
    x: number[];
    y: number[];
    a: number[];
    b: number[];
    c: number[];
    d: number[];
    n: number;
}
export declare function createCubicSpline(x: number[], y: number[], natural?: boolean): CubicSpline;
export declare function evaluateCubicSpline(spline: CubicSpline, x: number): number;
export interface AkimaSpline {
    x: number[];
    y: number[];
    m: number[];
    n: number;
}
export declare function createAkimaSpline(x: number[], y: number[]): AkimaSpline;
export declare function evaluateAkimaSpline(spline: AkimaSpline, x: number): number;
export declare function nearestNeighbor(x: number[], y: number[], xi: number): number;
export declare const Interpolation: {
    lerp: typeof lerp;
    lerpVec3: typeof lerpVec3;
    bilinear: typeof bilinear;
    trilinear: typeof trilinear;
    createLagrangeInterpolator: typeof createLagrangeInterpolator;
    evaluateLagrange: typeof evaluateLagrange;
    createCubicSpline: typeof createCubicSpline;
    evaluateCubicSpline: typeof evaluateCubicSpline;
    createAkimaSpline: typeof createAkimaSpline;
    evaluateAkimaSpline: typeof evaluateAkimaSpline;
    nearestNeighbor: typeof nearestNeighbor;
};
//# sourceMappingURL=interpolation.d.ts.map