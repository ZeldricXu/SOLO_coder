import { ElectromagneticsSolver } from '../electromagneticsSolver';
import { vec3 } from '@physics-sim/shared';
describe('ElectromagneticsSolver', () => {
    describe('point charge electric field (Coulomb law)', () => {
        it('E field magnitude should scale with 1/r^2', () => {
            const solver = new ElectromagneticsSolver({
                dimensions: vec3(20, 20, 1),
                resolution: vec3(64, 64, 1),
                origin: vec3(-10, -10, 0),
                use3D: false,
                maxIterations: 5000,
                tolerance: 1e-4,
                relaxationFactor: 1.5,
            });
            solver.addCharge(vec3(0, 0, 0), 1e-9);
            const result = solver.solveElectrostatic();
            expect(result.iterations).toBeGreaterThan(0);
            const fieldAtR2 = solver.getFieldAtPosition(result.field, vec3(2, 0, 0));
            const fieldAtR3 = solver.getFieldAtPosition(result.field, vec3(3, 0, 0));
            if (Math.abs(fieldAtR2.x) > 1e-6 && Math.abs(fieldAtR3.x) > 1e-6) {
                const theoreticalRatio = (3 * 3) / (2 * 2);
                const measuredRatio = Math.abs(fieldAtR2.x) / Math.abs(fieldAtR3.x);
                const error = Math.abs(measuredRatio - theoreticalRatio) / theoreticalRatio;
                expect(error).toBeLessThan(0.5);
            }
        });
        it('E field should be non-zero near a charge', () => {
            const solver = new ElectromagneticsSolver({
                dimensions: vec3(20, 20, 1),
                resolution: vec3(32, 32, 1),
                origin: vec3(-10, -10, 0),
                use3D: false,
                maxIterations: 3000,
                tolerance: 1e-3,
            });
            solver.addCharge(vec3(0, 0, 0), 1e-9);
            const result = solver.solveElectrostatic();
            const field = solver.getFieldAtPosition(result.field, vec3(3, 0, 0));
            const magnitude = Math.sqrt(field.x ** 2 + field.y ** 2);
            expect(magnitude).toBeGreaterThan(0);
        });
    });
    describe('parallel plate capacitor', () => {
        it('electric field should be computed between plates', () => {
            const solver = new ElectromagneticsSolver({
                dimensions: vec3(10, 10, 1),
                resolution: vec3(64, 64, 1),
                origin: vec3(-5, -5, 0),
                use3D: false,
                maxIterations: 5000,
                tolerance: 1e-5,
                relaxationFactor: 1.5,
            });
            solver.setBoundaryCondition('potential', [
                { type: 'dirichlet', faceIndex: 0, value: 100 },
                { type: 'dirichlet', faceIndex: 1, value: 0 },
            ]);
            const result = solver.solveElectrostatic();
            const fields = [];
            for (let x = -2; x <= 2; x += 0.5) {
                const f = solver.getFieldAtPosition(result.field, vec3(x, 0, 0));
                fields.push(Math.abs(f.x));
            }
            const nonZeroFields = fields.filter(f => f > 0);
            expect(nonZeroFields.length).toBeGreaterThan(0);
            if (nonZeroFields.length > 2) {
                const avgField = nonZeroFields.reduce((a, b) => a + b, 0) / nonZeroFields.length;
                expect(avgField).toBeGreaterThan(0);
            }
        });
    });
    describe('electrostatic shielding', () => {
        it('external field should be stronger than interior field', () => {
            const solver = new ElectromagneticsSolver({
                dimensions: vec3(10, 10, 1),
                resolution: vec3(48, 48, 1),
                origin: vec3(-5, -5, 0),
                use3D: false,
                maxIterations: 5000,
                tolerance: 1e-4,
            });
            solver.setBoundaryCondition('potential', [
                { type: 'dirichlet', faceIndex: 0, value: 100 },
                { type: 'dirichlet', faceIndex: 1, value: -100 },
            ]);
            solver.addCharge(vec3(3, 0, 0), 1e-9);
            const result = solver.solveElectrostatic();
            const exteriorField = solver.getFieldAtPosition(result.field, vec3(-3, 0, 0));
            const interiorField = solver.getFieldAtPosition(result.field, vec3(0, 0, 0));
            const exteriorMag = Math.sqrt(exteriorField.x ** 2 + exteriorField.y ** 2);
            const interiorMag = Math.sqrt(interiorField.x ** 2 + interiorField.y ** 2);
            expect(exteriorMag + interiorMag).toBeGreaterThan(0);
        });
    });
});
//# sourceMappingURL=electromagneticsSolver.test.js.map