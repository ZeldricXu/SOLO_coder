import { vec2 } from '@physics-sim/shared';
export function add(a, b) {
    return vec2(a.x + b.x, a.y + b.y);
}
export function sub(a, b) {
    return vec2(a.x - b.x, a.y - b.y);
}
export function mul(v, s) {
    return vec2(v.x * s, v.y * s);
}
export function div(v, s) {
    return vec2(v.x / s, v.y / s);
}
export function dot(a, b) {
    return a.x * b.x + a.y * b.y;
}
export function cross(a, b) {
    return a.x * b.y - a.y * b.x;
}
export function length(v) {
    return Math.sqrt(v.x * v.x + v.y * v.y);
}
export function lengthSq(v) {
    return v.x * v.x + v.y * v.y;
}
export function normalize(v) {
    const len = length(v);
    return len > 0 ? div(v, len) : vec2(0, 0);
}
export function distance(a, b) {
    return length(sub(a, b));
}
export function distanceSq(a, b) {
    return lengthSq(sub(a, b));
}
export function negate(v) {
    return vec2(-v.x, -v.y);
}
export function lerp(a, b, t) {
    return vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
}
export const Vec2Ops = {
    add, sub, mul, div, dot, cross, length, lengthSq, normalize,
    distance, distanceSq, negate, lerp
};
//# sourceMappingURL=vec2.js.map