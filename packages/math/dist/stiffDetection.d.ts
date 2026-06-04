import { StateVector, DerivativeFunction } from './integrators';
export interface StiffnessAnalysisResult {
    isStiff: boolean;
    maxEigenvalue: number;
    minEigenvalue: number;
    stiffnessRatio: number;
    recommendedIntegrator: 'explicit' | 'implicit' | 'bdf';
    recommendedTimeStep: number;
}
export declare function estimateJacobian(f: DerivativeFunction, t: number, y: StateVector, epsilon?: number): number[][];
export declare function estimateEigenvalues(J: number[][]): {
    maxReal: number;
    minReal: number;
    eigenvalues: number[];
};
export declare function powerIteration(J: number[][], tolerance?: number, maxIterations?: number): {
    eigenvalue: number;
    eigenvector: number[];
};
export declare function analyzeStiffness(f: DerivativeFunction, t: number, y: StateVector, currentDt: number, tolerance?: number): StiffnessAnalysisResult;
export declare function monitorStiffness(f: DerivativeFunction, t: number, y: StateVector, currentDt: number, history?: StiffnessAnalysisResult[], windowSize?: number): {
    analysis: StiffnessAnalysisResult;
    shouldSwitch: boolean;
};
export declare const StiffDetection: {
    estimateJacobian: typeof estimateJacobian;
    estimateEigenvalues: typeof estimateEigenvalues;
    powerIteration: typeof powerIteration;
    analyzeStiffness: typeof analyzeStiffness;
    monitorStiffness: typeof monitorStiffness;
};
//# sourceMappingURL=stiffDetection.d.ts.map