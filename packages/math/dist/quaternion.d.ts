import { Vec3 } from '@physics-sim/shared';
import { Matrix3 } from '@physics-sim/shared';
export interface Quaternion {
    x: number;
    y: number;
    z: number;
    w: number;
}
export declare function quaternion(x?: number, y?: number, z?: number, w?: number): Quaternion;
export declare function identity(): Quaternion;
export declare function fromAxisAngle(axis: Vec3, angle: number): Quaternion;
export declare function fromEuler(euler: Vec3): Quaternion;
export declare function fromMatrix3(m: Matrix3): Quaternion;
export declare function multiply(a: Quaternion, b: Quaternion): Quaternion;
export declare function rotate(q: Quaternion, v: Vec3): Vec3;
export declare function conjugate(q: Quaternion): Quaternion;
export declare function inverse(q: Quaternion): Quaternion;
export declare function normalize(q: Quaternion): Quaternion;
export declare function dot(a: Quaternion, b: Quaternion): number;
export declare function slerp(a: Quaternion, b: Quaternion, t: number): Quaternion;
export declare function toEuler(q: Quaternion): Vec3;
export declare const QuaternionOps: {
    quaternion: typeof quaternion;
    identity: typeof identity;
    fromAxisAngle: typeof fromAxisAngle;
    fromEuler: typeof fromEuler;
    fromMatrix3: typeof fromMatrix3;
    multiply: typeof multiply;
    rotate: typeof rotate;
    conjugate: typeof conjugate;
    inverse: typeof inverse;
    normalize: typeof normalize;
    dot: typeof dot;
    slerp: typeof slerp;
    toEuler: typeof toEuler;
};
//# sourceMappingURL=quaternion.d.ts.map