export type RootFunction = (x: number) => number;
export type RootFunctionVec = (x: number[]) => number[];
export declare function bisection(f: RootFunction, a: number, b: number, tolerance?: number, maxIterations?: number): {
    root: number;
    iterations: number;
    converged: boolean;
};
export declare function newtonRaphson(f: RootFunction, df: RootFunction, x0: number, tolerance?: number, maxIterations?: number): {
    root: number;
    iterations: number;
    converged: boolean;
};
export declare function secant(f: RootFunction, x0: number, x1: number, tolerance?: number, maxIterations?: number): {
    root: number;
    iterations: number;
    converged: boolean;
};
export declare function regulaFalsi(f: RootFunction, a: number, b: number, tolerance?: number, maxIterations?: number): {
    root: number;
    iterations: number;
    converged: boolean;
};
export declare function newtonRaphsonSystem(f: RootFunctionVec, jacobian: (x: number[]) => number[][], x0: number[], tolerance?: number, maxIterations?: number): {
    root: number[];
    iterations: number;
    converged: boolean;
};
export declare function findRootsPolynomial(coeffs: number[]): number[];
export declare const RootFinding: {
    bisection: typeof bisection;
    newtonRaphson: typeof newtonRaphson;
    secant: typeof secant;
    regulaFalsi: typeof regulaFalsi;
    newtonRaphsonSystem: typeof newtonRaphsonSystem;
    findRootsPolynomial: typeof findRootsPolynomial;
};
//# sourceMappingURL=rootFinding.d.ts.map