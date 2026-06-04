import { Matrix4, Vec3 } from '@physics-sim/shared';
export declare function identity(): Matrix4;
export declare function multiply(a: Matrix4, b: Matrix4): Matrix4;
export declare function multiplyPoint(m: Matrix4, p: Vec3): Vec3;
export declare function multiplyVector(m: Matrix4, v: Vec3): Vec3;
export declare function transpose(m: Matrix4): Matrix4;
export declare function determinant(m: Matrix4): number;
export declare function inverse(m: Matrix4): Matrix4 | null;
export declare function translation(t: Vec3): Matrix4;
export declare function scale(s: Vec3): Matrix4;
export declare function lookAt(eye: Vec3, target: Vec3, up: Vec3): Matrix4;
export declare const Matrix4Ops: {
    identity: typeof identity;
    multiply: typeof multiply;
    multiplyPoint: typeof multiplyPoint;
    multiplyVector: typeof multiplyVector;
    transpose: typeof transpose;
    determinant: typeof determinant;
    inverse: typeof inverse;
    translation: typeof translation;
    scale: typeof scale;
    lookAt: typeof lookAt;
};
//# sourceMappingURL=matrix4.d.ts.map