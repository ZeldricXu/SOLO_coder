import { Matrix3 } from '@physics-sim/shared';
import { Vec3 } from '@physics-sim/shared';
export declare function identity(): Matrix3;
export declare function multiply(a: Matrix3, b: Matrix3): Matrix3;
export declare function multiplyVector(m: Matrix3, v: Vec3): Vec3;
export declare function transpose(m: Matrix3): Matrix3;
export declare function determinant(m: Matrix3): number;
export declare function inverse(m: Matrix3): Matrix3 | null;
export declare function fromEuler(euler: Vec3): Matrix3;
export declare function scale(s: Vec3): Matrix3;
export declare const Matrix3Ops: {
    identity: typeof identity;
    multiply: typeof multiply;
    multiplyVector: typeof multiplyVector;
    transpose: typeof transpose;
    determinant: typeof determinant;
    inverse: typeof inverse;
    fromEuler: typeof fromEuler;
    scale: typeof scale;
};
//# sourceMappingURL=matrix3.d.ts.map