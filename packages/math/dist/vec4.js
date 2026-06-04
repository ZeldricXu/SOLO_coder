import { vec4 } from '@physics-sim/shared';
export function add(a, b) {
    return vec4(a.x + b.x, a.y + b.y, a.z + b.z, a.w + b.w);
}
export function sub(a, b) {
    return vec4(a.x - b.x, a.y - b.y, a.z - b.z, a.w - b.w);
}
export function mul(v, s) {
    return vec4(v.x * s, v.y * s, v.z * s, v.w * s);
}
export function div(v, s) {
    return vec4(v.x / s, v.y / s, v.z / s, v.w / s);
}
export function dot(a, b) {
    return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
}
export function length(v) {
    return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z + v.w * v.w);
}
export function lengthSq(v) {
    return v.x * v.x + v.y * v.y + v.z * v.z + v.w * v.w;
}
export function normalize(v) {
    const len = length(v);
    return len > 0 ? div(v, len) : vec4(0, 0, 0, 0);
}
export function lerp(a, b, t) {
    return vec4(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t);
}
export const Vec4Ops = {
    add, sub, mul, div, dot, length, lengthSq, normalize, lerp
};
//# sourceMappingURL=vec4.js.map