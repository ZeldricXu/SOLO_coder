import { eulerStep, rungeKutta4Step, midpointStep, rungeKuttaFehlberg45Step, adaptiveRKF45, implicitEulerStep, bdf2Step, } from '../integrators';
describe('integrators', () => {
    describe('RKF45 on exponential decay dx/dt = -λx', () => {
        const lambda = 2;
        const f = (_t, y) => [-lambda * y[0]];
        const analytical = (t) => Math.exp(-lambda * t);
        it('global error should be within tolerance', () => {
            const tolerance = 1e-6;
            const results = adaptiveRKF45(f, 0, [1], 2, 0.1, tolerance);
            const lastResult = results[results.length - 1];
            const expected = analytical(2);
            expect(lastResult.y[0]).toBeCloseTo(expected, 4);
        });
        it('should produce results that end near t=2', () => {
            const results = adaptiveRKF45(f, 0, [1], 2, 0.1, 1e-6);
            const lastResult = results[results.length - 1];
            expect(lastResult.t).toBeCloseTo(2, 6);
        });
    });
    describe('adaptive step size logic', () => {
        it('step size should increase in smooth regions', () => {
            const f = (_t, y) => [-0.1 * y[0]];
            const results = adaptiveRKF45(f, 0, [1], 10, 0.01, 1e-6, 1e-10, 1);
            const steps = results.map(r => r.timeStep);
            const laterHalf = steps.slice(Math.floor(steps.length / 2));
            const earlierHalf = steps.slice(0, Math.floor(steps.length / 2));
            const avgLater = laterHalf.reduce((a, b) => a + b, 0) / laterHalf.length;
            const avgEarlier = earlierHalf.reduce((a, b) => a + b, 0) / earlierHalf.length;
            expect(avgLater).toBeGreaterThan(avgEarlier);
        });
        it('step size should decrease for rapidly changing systems', () => {
            const f = (_t, y) => [-50 * y[0]];
            const results = adaptiveRKF45(f, 0, [1], 0.5, 0.1, 1e-8, 1e-12, 0.5);
            const steps = results.map(r => r.timeStep);
            const hasSmallSteps = steps.some(s => s < 0.1);
            expect(hasSmallSteps).toBe(true);
        });
    });
    describe('basic correctness of integrators', () => {
        const f = (_t, y) => [-y[0]];
        const analytical = (t, y0) => y0 * Math.exp(-t);
        const dt = 0.01;
        it('eulerStep should approximate the analytical solution', () => {
            let y = [1];
            let t = 0;
            for (let i = 0; i < 100; i++) {
                y = eulerStep(f, t, y, dt);
                t += dt;
            }
            expect(y[0]).toBeCloseTo(analytical(1, 1), 1);
        });
        it('rungeKutta4Step should approximate the analytical solution', () => {
            let y = [1];
            let t = 0;
            for (let i = 0; i < 100; i++) {
                y = rungeKutta4Step(f, t, y, dt);
                t += dt;
            }
            expect(y[0]).toBeCloseTo(analytical(1, 1), 6);
        });
        it('midpointStep should approximate the analytical solution', () => {
            let y = [1];
            let t = 0;
            for (let i = 0; i < 100; i++) {
                y = midpointStep(f, t, y, dt);
                t += dt;
            }
            expect(y[0]).toBeCloseTo(analytical(1, 1), 4);
        });
        it('implicitEulerStep should approximate the analytical solution', () => {
            let y = [1];
            let t = 0;
            for (let i = 0; i < 100; i++) {
                y = implicitEulerStep(f, t, y, dt);
                t += dt;
            }
            expect(y[0]).toBeCloseTo(analytical(1, 1), 2);
        });
        it('bdf2Step should approximate the analytical solution', () => {
            let yPrev = [1];
            let y = eulerStep(f, 0, [1], dt);
            let t = dt;
            for (let i = 1; i < 100; i++) {
                const yNext = bdf2Step(f, t, y, yPrev, dt);
                yPrev = y;
                y = yNext;
                t += dt;
            }
            expect(y[0]).toBeCloseTo(analytical(1, 1), 3);
        });
    });
    describe('rungeKuttaFehlberg45Step', () => {
        it('should return y4, y5 and error', () => {
            const f = (_t, y) => [-y[0]];
            const result = rungeKuttaFehlberg45Step(f, 0, [1], 0.1);
            expect(result.y4).toBeDefined();
            expect(result.y5).toBeDefined();
            expect(result.error).toBeGreaterThanOrEqual(0);
            expect(result.y5[0]).toBeCloseTo(Math.exp(-0.1), 4);
        });
    });
});
//# sourceMappingURL=integrators.test.js.map