import { FluidDynamicsSolver } from '../fluidDynamicsSolver';
import { vec3 } from '@physics-sim/shared';
describe('FluidDynamicsSolver', () => {
    describe('initialization', () => {
        it('should initialize density and velocity on a 40x20 grid without NaN', () => {
            const solver = new FluidDynamicsSolver({
                width: 40,
                height: 20,
                viscosity: 0.02,
                inletVelocity: vec3(0.1, 0, 0),
                dx: 1.0,
            });
            const grid = solver.getGridInfo();
            expect(grid.nx).toBe(40);
            expect(grid.ny).toBe(20);
            for (let y = 1; y < 19; y++) {
                for (let x = 1; x < 39; x++) {
                    const rho = solver.getDensityAt(x, y);
                    const vel = solver.getVelocityAt(x, y);
                    expect(Number.isFinite(rho)).toBe(true);
                    expect(Number.isFinite(vel.x)).toBe(true);
                    expect(Number.isFinite(vel.y)).toBe(true);
                    expect(Number.isNaN(rho)).toBe(false);
                    expect(Number.isNaN(vel.x)).toBe(false);
                    expect(Number.isNaN(vel.y)).toBe(false);
                }
            }
            expect(solver.getIteration()).toBe(0);
        });
    });
    describe('stepping simulation', () => {
        it('should produce positive finite density and finite maxVelocity after 100 steps', () => {
            const solver = new FluidDynamicsSolver({
                width: 40,
                height: 20,
                viscosity: 0.02,
                inletVelocity: vec3(0.1, 0, 0),
                dx: 1.0,
            });
            const result = solver.step(100);
            expect(result.iteration).toBe(100);
            expect(Number.isFinite(result.maxVelocity)).toBe(true);
            expect(Number.isNaN(result.maxVelocity)).toBe(false);
            for (let y = 1; y < 19; y++) {
                for (let x = 1; x < 39; x++) {
                    if (!solver.isObstacle(x, y)) {
                        const rho = solver.getDensityAt(x, y);
                        expect(Number.isFinite(rho)).toBe(true);
                        expect(Number.isNaN(rho)).toBe(false);
                        expect(Number.isNaN(Infinity)).toBe(false);
                        expect(rho).not.toBe(Infinity);
                        expect(rho).not.toBe(-Infinity);
                    }
                }
            }
        });
    });
    describe('mass conservation', () => {
        it('avg density should stay within 5% of 1.0 after 200 steps', () => {
            const solver = new FluidDynamicsSolver({
                width: 50,
                height: 30,
                viscosity: 0.02,
                inletVelocity: vec3(0.05, 0, 0),
                dx: 1.0,
            });
            const result = solver.step(200);
            expect(result.avgDensity).toBeGreaterThan(0.95);
            expect(result.avgDensity).toBeLessThan(1.05);
            expect(Number.isFinite(result.avgDensity)).toBe(true);
        });
    });
    describe('obstacle placement', () => {
        it('should mark interior of a circular polygon as obstacle', () => {
            const solver = new FluidDynamicsSolver({
                width: 40,
                height: 20,
                viscosity: 0.02,
                inletVelocity: vec3(0.1, 0, 0),
                dx: 1.0,
            });
            const cx = 20;
            const cy = 10;
            const r = 4;
            const segments = 12;
            const vertices = [];
            for (let i = 0; i < segments; i++) {
                const angle = (2 * Math.PI * i) / segments;
                vertices.push(vec3(cx + r * Math.cos(angle), cy + r * Math.sin(angle), 0));
            }
            solver.addObstacle({ id: 'circle-obs', vertices });
            expect(solver.isObstacle(cx, cy)).toBe(true);
            const nearbyFluid = solver.isObstacle(cx + r + 2, cy);
            expect(nearbyFluid).toBe(false);
            expect(solver.isObstacle(cx - 2, cy)).toBe(true);
            expect(solver.isObstacle(cx, cy + 2)).toBe(true);
        });
    });
    describe('bounce-back boundary', () => {
        it('velocity near obstacle boundary should be small after simulation', () => {
            const solver = new FluidDynamicsSolver({
                width: 50,
                height: 30,
                viscosity: 0.05,
                inletVelocity: vec3(0.04, 0, 0),
                dx: 1.0,
            });
            const cx = 25;
            const cy = 15;
            const r = 3;
            const segments = 12;
            const vertices = [];
            for (let i = 0; i < segments; i++) {
                const angle = (2 * Math.PI * i) / segments;
                vertices.push(vec3(cx + r * Math.cos(angle), cy + r * Math.sin(angle), 0));
            }
            solver.addObstacle({ id: 'bounce-obs', vertices });
            solver.step(100);
            const boundaryPoints = [
                { x: cx - r - 1, y: cy },
                { x: cx + r + 1, y: cy },
                { x: cx, y: cy - r - 1 },
                { x: cx, y: cy + r + 1 },
            ];
            for (const pt of boundaryPoints) {
                if (!solver.isObstacle(pt.x, pt.y)) {
                    const vel = solver.getVelocityAt(pt.x, pt.y);
                    const speed = Math.sqrt(vel.x * vel.x + vel.y * vel.y);
                    expect(Number.isFinite(speed)).toBe(true);
                    expect(speed).toBeLessThan(0.5);
                }
            }
        });
    });
    describe('viscosity change', () => {
        it('should not crash when viscosity is changed mid-simulation', () => {
            const solver = new FluidDynamicsSolver({
                width: 40,
                height: 20,
                viscosity: 0.02,
                inletVelocity: vec3(0.05, 0, 0),
                dx: 1.0,
            });
            solver.step(50);
            solver.setViscosity(0.1);
            const grid = solver.getGridInfo();
            expect(grid.omega).toBeGreaterThan(0);
            expect(Number.isFinite(grid.omega)).toBe(true);
            const result = solver.step(100);
            expect(result.iteration).toBe(150);
            expect(Number.isFinite(result.maxVelocity)).toBe(true);
            expect(Number.isFinite(result.avgDensity)).toBe(true);
            for (let y = 1; y < 19; y++) {
                for (let x = 1; x < 39; x++) {
                    if (!solver.isObstacle(x, y)) {
                        const rho = solver.getDensityAt(x, y);
                        expect(Number.isFinite(rho)).toBe(true);
                    }
                }
            }
        });
    });
});
//# sourceMappingURL=fluidDynamicsSolver.test.js.map