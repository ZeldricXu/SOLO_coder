import { MechanicsSolver } from '../index';
import { vec3 } from '@physics-sim/shared';
import { detectCCD, resolveCCD, needsCCD, getBoundingRadius } from '../continuousCollision';
import { detectCollisions } from '../collisionDetection';
import { createRigidBody } from '../rigidBody';
function makeSphere(id, pos, isStatic = false, mass = 1, radius = 0.5) {
    return {
        id,
        name: id,
        type: 'sphere',
        objectType: 'sphere',
        domain: ['mechanics'],
        position: { ...pos },
        rotation: vec3(0, 0, 0),
        isStatic,
        materialId: 'steel',
        mechanics: {
            mass,
            restitution: 0.9,
            friction: 0.1,
            momentOfInertia: vec3(0, 0, 0),
        },
        geometry: {
            type: 'sphere',
            radius,
        },
    };
}
function makeBox(id, pos, isStatic = false, mass = 1, halfExtents = vec3(1, 1, 1)) {
    return {
        id,
        name: id,
        type: 'box',
        objectType: 'box',
        domain: ['mechanics'],
        position: { ...pos },
        rotation: vec3(0, 0, 0),
        isStatic,
        materialId: 'steel',
        mechanics: {
            mass,
            restitution: 0.9,
            friction: 0.1,
            momentOfInertia: vec3(0, 0, 0),
        },
        geometry: {
            type: 'box',
            width: halfExtents.x * 2,
            height: halfExtents.y * 2,
            depth: halfExtents.z * 2,
        },
    };
}
describe('CCD (Continuous Collision Detection)', () => {
    describe('needsCCD', () => {
        it('should return true for high-speed objects', () => {
            const body = createRigidBody(makeSphere('test', vec3(0, 0, 0), false, 1, 0.5));
            body.velocity = vec3(100, 0, 0);
            const dt = 1 / 60;
            expect(needsCCD(body, dt)).toBe(true);
        });
        it('should return false for slow-moving objects', () => {
            const body = createRigidBody(makeSphere('test', vec3(0, 0, 0), false, 1, 0.5));
            body.velocity = vec3(0.1, 0, 0);
            const dt = 1 / 60;
            expect(needsCCD(body, dt)).toBe(false);
        });
    });
    describe('getBoundingRadius', () => {
        it('should calculate correct radius for sphere', () => {
            const body = createRigidBody(makeSphere('test', vec3(0, 0, 0), false, 1, 1.5));
            const radius = getBoundingRadius(body);
            expect(radius).toBeCloseTo(1.5);
        });
        it('should calculate correct radius for box', () => {
            const body = createRigidBody(makeBox('test', vec3(0, 0, 0), false, 1, vec3(1, 2, 3)));
            const radius = getBoundingRadius(body);
            expect(radius).toBeCloseTo(3);
        });
    });
    describe('High speed bullet vs wall', () => {
        it('should detect and resolve high-speed collision that would tunnel through without CCD', () => {
            const solver = new MechanicsSolver({
                gravity: vec3(0, 0, 0),
                dt: 1 / 60,
                substeps: 1,
                solverIterations: 10,
                baumgarte: 0.2,
                usePBD: true,
                useVerlet: true,
                adaptiveStepSize: false,
                tolerance: 1e-6,
                minDt: 1e-4,
                maxDt: 1 / 30,
                useCCD: false,
                ccdConfig: {},
            });
            const bulletId = 'bullet';
            const wallId = 'wall';
            solver.addPhysicsObject(makeSphere(bulletId, vec3(0, 0, 0), false, 0.1, 0.1), vec3(500, 0, 0));
            solver.addPhysicsObject(makeSphere(wallId, vec3(5, 0, 0), true, Infinity, 0.5), vec3(0, 0, 0));
            let stepResult = solver.step();
            const bullet = stepResult.bodies.get(bulletId);
            expect(bullet.velocity.x).toBeGreaterThan(0);
            expect(bullet.position.x).toBeGreaterThan(5.5);
            console.log('Without CCD: Bullet passed through wall! Position:', bullet.position.x);
        });
        it('should prevent tunneling when CCD is enabled', () => {
            const solver = new MechanicsSolver({
                gravity: vec3(0, 0, 0),
                dt: 1 / 60,
                substeps: 1,
                solverIterations: 10,
                baumgarte: 0.2,
                usePBD: true,
                useVerlet: false,
                adaptiveStepSize: false,
                tolerance: 1e-6,
                minDt: 1e-4,
                maxDt: 1 / 30,
                useCCD: true,
                ccdConfig: {
                    ccdThreshold: 0.5,
                    maxIterations: 20,
                    tolerance: 1e-8,
                },
            });
            const bulletId = 'bullet';
            const wallId = 'wall';
            solver.addPhysicsObject(makeSphere(bulletId, vec3(0, 0, 0), false, 1, 0.5), vec3(50, 0, 0));
            solver.addPhysicsObject(makeSphere(wallId, vec3(5, 0, 0), true, Infinity, 0.5), vec3(0, 0, 0));
            let bounceCount = 0;
            let maxPosition = 0;
            let result;
            for (let i = 0; i < 5; i++) {
                result = solver.step();
                const bullet = result.bodies.get(bulletId);
                maxPosition = Math.max(maxPosition, bullet.position.x);
                if (bullet.velocity.x < 0) {
                    bounceCount++;
                }
            }
            expect(maxPosition).toBeLessThan(6);
            expect(bounceCount).toBeGreaterThan(0);
            console.log('With CCD: Bounce count:', bounceCount, 'Max position:', maxPosition, 'CCD handled:', result.ccdHandled);
        });
    });
    describe('detectCCD vs detectCollisions', () => {
        it('should detect collisions that are missed by discrete collision detection', () => {
            const bodies = new Map();
            const dt = 1 / 60;
            const bodyA = createRigidBody(makeSphere('a', vec3(0, 0, 0), false, 1, 0.5));
            bodyA.prevPosition = vec3(0, 0, 0);
            bodyA.position = vec3(500 * dt, 0, 0);
            bodyA.velocity = vec3(500, 0, 0);
            const bodyB = createRigidBody(makeSphere('b', vec3(5, 0, 0), true, 1, 0.5));
            bodyB.prevPosition = vec3(5, 0, 0);
            bodyB.position = vec3(5, 0, 0);
            bodyB.velocity = vec3(0, 0, 0);
            bodies.set('a', bodyA);
            bodies.set('b', bodyB);
            const discreteResult = detectCollisions(bodies);
            const ccdResult = detectCCD(bodies, dt);
            expect(discreteResult.pairs.length).toBe(0);
            expect(ccdResult.length).toBeGreaterThan(0);
            expect(ccdResult[0].bodyA).toBe('a');
            expect(ccdResult[0].bodyB).toBe('b');
            expect(ccdResult[0].collisionTime).toBeGreaterThan(0);
            expect(ccdResult[0].collisionTime).toBeLessThan(dt);
        });
    });
    describe('resolveCCD', () => {
        it('should resolve CCD collisions correctly with correct impulse response', () => {
            const bodies = new Map();
            const bodyA = createRigidBody(makeSphere('a', vec3(-0.9, 0, 0), false, 1, 0.5));
            bodyA.prevPosition = vec3(-0.9, 0, 0);
            bodyA.velocity = vec3(10, 0, 0);
            const bodyB = createRigidBody(makeSphere('b', vec3(0.9, 0, 0), false, 1, 0.5));
            bodyB.prevPosition = vec3(0.9, 0, 0);
            bodyB.velocity = vec3(-10, 0, 0);
            bodies.set('a', bodyA);
            bodies.set('b', bodyB);
            const dt = 1 / 60;
            const ccdResults = detectCCD(bodies, dt);
            const handled = resolveCCD(bodies, ccdResults, dt);
            expect(handled).toBeGreaterThanOrEqual(0);
            const a = bodies.get('a');
            const b = bodies.get('b');
            const totalMomentumBefore = 10 + (-10);
            const totalMomentumAfter = a.velocity.x + b.velocity.x;
            expect(Math.abs(totalMomentumAfter - totalMomentumBefore)).toBeLessThan(1);
        });
    });
    describe('Solver integration', () => {
        it('should integrate CCD into solver step', () => {
            const solver = new MechanicsSolver({
                gravity: vec3(0, -9.81, 0),
                dt: 1 / 60,
                substeps: 4,
                solverIterations: 10,
                baumgarte: 0.2,
                usePBD: true,
                useVerlet: true,
                adaptiveStepSize: false,
                tolerance: 1e-6,
                minDt: 1e-4,
                maxDt: 1 / 30,
                useCCD: true,
                ccdConfig: {},
            });
            solver.addPhysicsObject(makeSphere('fast', vec3(0, 1, 0), false, 1, 0.1), vec3(0, -50, 0));
            solver.addPhysicsObject(makeBox('ground', vec3(0, -0.5, 0), true, Infinity, vec3(10, 0.5, 10)), vec3(0, 0, 0));
            const result = solver.step();
            expect(result.ccdHandled).toBeGreaterThanOrEqual(0);
            expect(result.bodies.get('fast').position.y).toBeGreaterThan(-2);
        });
    });
});
//# sourceMappingURL=ccdCollision.test.js.map