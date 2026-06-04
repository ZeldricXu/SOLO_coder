export interface Vec2 {
    x: number;
    y: number;
}
export interface Vec3 {
    x: number;
    y: number;
    z: number;
}
export interface Vec4 {
    x: number;
    y: number;
    z: number;
    w: number;
}
export interface Matrix3 {
    m00: number;
    m01: number;
    m02: number;
    m10: number;
    m11: number;
    m12: number;
    m20: number;
    m21: number;
    m22: number;
}
export interface Matrix4 {
    m00: number;
    m01: number;
    m02: number;
    m03: number;
    m10: number;
    m11: number;
    m12: number;
    m13: number;
    m20: number;
    m21: number;
    m22: number;
    m23: number;
    m30: number;
    m31: number;
    m32: number;
    m33: number;
}
export type Vector = Vec2 | Vec3;
export declare function vec2(x?: number, y?: number): Vec2;
export declare function vec3(x?: number, y?: number, z?: number): Vec3;
export declare function vec4(x?: number, y?: number, z?: number, w?: number): Vec4;
//# sourceMappingURL=vectors.d.ts.map