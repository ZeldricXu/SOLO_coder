import { Vec4 } from '@physics-sim/shared';
export declare function add(a: Vec4, b: Vec4): Vec4;
export declare function sub(a: Vec4, b: Vec4): Vec4;
export declare function mul(v: Vec4, s: number): Vec4;
export declare function div(v: Vec4, s: number): Vec4;
export declare function dot(a: Vec4, b: Vec4): number;
export declare function length(v: Vec4): number;
export declare function lengthSq(v: Vec4): number;
export declare function normalize(v: Vec4): Vec4;
export declare function lerp(a: Vec4, b: Vec4, t: number): Vec4;
export declare const Vec4Ops: {
    add: typeof add;
    sub: typeof sub;
    mul: typeof mul;
    div: typeof div;
    dot: typeof dot;
    length: typeof length;
    lengthSq: typeof lengthSq;
    normalize: typeof normalize;
    lerp: typeof lerp;
};
//# sourceMappingURL=vec4.d.ts.map