import { ThermodynamicsSolver } from '../thermodynamicsSolver';
import { vec3 } from '@physics-sim/shared';
describe('ThermodynamicsSolver', () => {
    describe('1D steady-state heat conduction: linear temperature profile', () => {
        it('temperature should decrease from left to right boundary', () => {
            const T_left = 400;
            const T_right = 300;
            const solver = new ThermodynamicsSolver({
                dimensions: vec3(1, 1, 1),
                resolution: vec3(64, 4, 1),
                origin: vec3(0, 0, 0),
                use3D: false,
                dt: 0.01,
                maxIterations: 2000,
                tolerance: 1e-6,
            });
            solver.addThermalBody(vec3(0.5, 0.5, 0), vec3(1, 1, 1), 'copper', 350);
            solver.setBoundaryConditions([
                { type: 'dirichlet', faceIndex: 0, value: T_left },
                { type: 'dirichlet', faceIndex: 1, value: T_right },
            ]);
            for (let i = 0; i < 500; i++) {
                solver.step(0.01);
            }
            const tempAtLeft = solver.getTemperatureAtPosition(vec3(0.1, 0.5, 0));
            const tempAtRight = solver.getTemperatureAtPosition(vec3(0.9, 0.5, 0));
            const tempAtMid = solver.getTemperatureAtPosition(vec3(0.5, 0.5, 0));
            expect(tempAtLeft).toBeGreaterThan(tempAtRight);
            expect(tempAtMid).toBeLessThan(T_left);
            expect(tempAtMid).toBeGreaterThan(T_right);
        });
    });
    describe('thermal conductivity interface: heat flux continuity', () => {
        it('temperature at interface should be between boundary temperatures', () => {
            const solver = new ThermodynamicsSolver({
                dimensions: vec3(1, 1, 1),
                resolution: vec3(64, 4, 1),
                origin: vec3(0, 0, 0),
                use3D: false,
                dt: 0.001,
                maxIterations: 2000,
                tolerance: 1e-6,
            });
            solver.addThermalBody(vec3(0.25, 0.5, 0), vec3(0.5, 1, 1), 'copper', 400);
            solver.addThermalBody(vec3(0.75, 0.5, 0), vec3(0.5, 1, 1), 'aluminum', 300);
            solver.setBoundaryConditions([
                { type: 'dirichlet', faceIndex: 0, value: 500 },
                { type: 'dirichlet', faceIndex: 1, value: 200 },
            ]);
            for (let i = 0; i < 1000; i++) {
                solver.step(0.001);
            }
            const tempAtInterface = solver.getTemperatureAtPosition(vec3(0.5, 0.5, 0));
            expect(tempAtInterface).toBeGreaterThan(200);
            expect(tempAtInterface).toBeLessThan(500);
        });
    });
});
//# sourceMappingURL=thermodynamicsSolver.test.js.map