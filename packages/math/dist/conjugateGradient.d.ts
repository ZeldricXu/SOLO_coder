export interface CGResult {
    x: Float32Array;
    iterations: number;
    residual: number;
    converged: boolean;
}
export interface MultigridLevel {
    nx: number;
    ny: number;
    nz: number;
    data: Float32Array;
}
export declare function dotProduct(a: Float32Array, b: Float32Array): number;
export declare function conjugateGradient(A: (x: Float32Array) => Float32Array, b: Float32Array, x0: Float32Array, tolerance?: number, maxIterations?: number, preconditioner?: (r: Float32Array) => Float32Array): CGResult;
export declare function createMultigridPreconditioner(nx: number, ny: number, nz: number, dx: number, dy: number, dz: number, use3D: boolean, numLevels?: number): (r: Float32Array) => Float32Array;
//# sourceMappingURL=conjugateGradient.d.ts.map