import { CurveFitType, CurveFitResult } from '@physics-sim/shared';
export declare function leastSquaresLinear(x: number[], y: number[]): {
    a: number;
    b: number;
    rSquared: number;
};
export declare function leastSquaresQuadratic(x: number[], y: number[]): {
    a: number;
    b: number;
    c: number;
    rSquared: number;
};
export declare function leastSquaresExponential(x: number[], y: number[]): {
    a: number;
    b: number;
    rSquared: number;
};
export declare function leastSquaresSine(x: number[], y: number[], initialFrequency?: number): {
    amplitude: number;
    frequency: number;
    phase: number;
    offset: number;
    rSquared: number;
};
export declare function solveLinearSystem(A: number[][], b: number[]): number[] | null;
export declare function estimateFrequency(x: number[], y: number[]): number;
export declare function fitCurve(type: CurveFitType, x: number[], y: number[]): CurveFitResult;
export declare const CurveFitting: {
    leastSquaresLinear: typeof leastSquaresLinear;
    leastSquaresQuadratic: typeof leastSquaresQuadratic;
    leastSquaresExponential: typeof leastSquaresExponential;
    leastSquaresSine: typeof leastSquaresSine;
    solveLinearSystem: typeof solveLinearSystem;
    estimateFrequency: typeof estimateFrequency;
    fitCurve: typeof fitCurve;
};
//# sourceMappingURL=curveFitting.d.ts.map