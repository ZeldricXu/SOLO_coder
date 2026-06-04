import { gjk, getSupportPoint, minkowskiSupport } from '../gjk';
import { vec3 } from '@physics-sim/shared';
function makeBoxShape(cx, cy, cz, hw, hh, hd) {
    const vertices = [];
    for (let x = -1; x <= 1; x += 2) {
        for (let y = -1; y <= 1; y += 2) {
            for (let z = -1; z <= 1; z += 2) {
                vertices.push(vec3(cx + x * hw, cy + y * hh, cz + z * hd));
            }
        }
    }
    return { vertices, center: vec3(cx, cy, cz) };
}
describe('GJK Algorithm', () => {
    describe('separated convex bodies', () => {
        it('should return isColliding=false for separated boxes', () => {
            const shapeA = makeBoxShape(0, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(5, 0, 0, 1, 1, 1);
            const result = gjk(shapeA, shapeB);
            expect(result.isColliding).toBe(false);
        });
        it('should return isColliding=false for boxes separated along y-axis', () => {
            const shapeA = makeBoxShape(0, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(0, 5, 0, 1, 1, 1);
            const result = gjk(shapeA, shapeB);
            expect(result.isColliding).toBe(false);
        });
    });
    describe('intersecting convex bodies', () => {
        it('should return isColliding=true for overlapping boxes', () => {
            const shapeA = makeBoxShape(0, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(0.5, 0, 0, 1, 1, 1);
            const result = gjk(shapeA, shapeB);
            expect(result.isColliding).toBe(true);
        });
        it('should return isColliding=true for heavily overlapping boxes', () => {
            const shapeA = makeBoxShape(0, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(0.1, 0.1, 0.1, 1, 1, 1);
            const result = gjk(shapeA, shapeB);
            expect(result.isColliding).toBe(true);
        });
    });
    describe('touching convex bodies', () => {
        it('should handle nearly-touching boxes at boundary', () => {
            const shapeA = makeBoxShape(0, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(2.01, 0, 0, 1, 1, 1);
            const result = gjk(shapeA, shapeB);
            expect(result.isColliding).toBe(false);
        });
        it('should detect slight overlap', () => {
            const shapeA = makeBoxShape(0, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(1.99, 0, 0, 1, 1, 1);
            const result = gjk(shapeA, shapeB);
            expect(result.isColliding).toBe(true);
        });
    });
    describe('support point', () => {
        it('should find the correct support point in a direction', () => {
            const shape = makeBoxShape(0, 0, 0, 1, 1, 1);
            const support = getSupportPoint(shape, vec3(1, 0, 0));
            expect(support.x).toBeCloseTo(1, 5);
            const dot = support.x * 1 + support.y * 0 + support.z * 0;
            expect(dot).toBeCloseTo(1, 5);
        });
        it('should find support point in negative direction', () => {
            const shape = makeBoxShape(0, 0, 0, 1, 1, 1);
            const support = getSupportPoint(shape, vec3(-1, 0, 0));
            expect(support.x).toBeCloseTo(-1, 5);
        });
    });
    describe('minkowski support', () => {
        it('should compute correct minkowski difference support', () => {
            const shapeA = makeBoxShape(2, 0, 0, 1, 1, 1);
            const shapeB = makeBoxShape(-2, 0, 0, 1, 1, 1);
            const support = minkowskiSupport(shapeA, shapeB, vec3(1, 0, 0));
            expect(support.x).toBeCloseTo(6, 5);
        });
    });
});
//# sourceMappingURL=gjk.test.js.map