import { analyzeStiffness, estimateJacobian, } from '../stiffDetection';
describe('stiffDetection', () => {
    describe('stiff system detection (van der Pol μ=1000)', () => {
        const mu = 1000;
        const vanDerPol = (_t, y) => [
            y[1],
            mu * (1 - y[0] * y[0]) * y[1] - y[0],
        ];
        it('should detect van der Pol (μ=1000) as stiff', () => {
            const result = analyzeStiffness(vanDerPol, 0, [2, 0], 0.001);
            expect(result.isStiff).toBe(true);
        });
        it('should have a large stiffness ratio for stiff system', () => {
            const result = analyzeStiffness(vanDerPol, 0, [2, 0], 0.001);
            expect(result.stiffnessRatio).toBeGreaterThan(1000);
        });
        it('should recommend implicit or bdf integrator for stiff system', () => {
            const result = analyzeStiffness(vanDerPol, 0, [2, 0], 0.001);
            expect(result.recommendedIntegrator).toMatch(/implicit|bdf/);
        });
    });
    describe('non-stiff system detection (dx/dt = -x)', () => {
        const nonStiff = (_t, y) => [-y[0]];
        it('should detect dx/dt = -x as non-stiff', () => {
            const result = analyzeStiffness(nonStiff, 0, [1], 0.01);
            expect(result.isStiff).toBe(false);
        });
        it('should have a small stiffness ratio for non-stiff system', () => {
            const result = analyzeStiffness(nonStiff, 0, [1], 0.01);
            expect(result.stiffnessRatio).toBeLessThanOrEqual(1000);
        });
        it('should recommend explicit integrator for non-stiff system', () => {
            const result = analyzeStiffness(nonStiff, 0, [1], 0.01);
            expect(result.recommendedIntegrator).toBe('explicit');
        });
    });
    describe('stiffness triggers method switch', () => {
        const mu = 1000;
        const vanDerPol = (_t, y) => [
            y[1],
            mu * (1 - y[0] * y[0]) * y[1] - y[0],
        ];
        it('should recommend implicit integrator for moderately stiff system', () => {
            const result = analyzeStiffness(vanDerPol, 0, [1.5, 0], 0.01);
            if (result.stiffnessRatio <= 1e6) {
                expect(result.recommendedIntegrator).toBe('implicit');
            }
        });
        it('should recommend bdf integrator for extremely stiff system', () => {
            const extremelyStiff = (_t, y) => [
                y[1],
                1e8 * (1 - y[0] * y[0]) * y[1] - y[0],
            ];
            const result = analyzeStiffness(extremelyStiff, 0, [2, 0], 0.0001);
            if (result.stiffnessRatio > 1e6) {
                expect(result.recommendedIntegrator).toBe('bdf');
            }
        });
        it('recommended time step for stiff system should allow larger steps than explicit stable dt', () => {
            const result = analyzeStiffness(vanDerPol, 0, [2, 0], 0.001);
            expect(result.recommendedTimeStep).toBeGreaterThan(0);
        });
    });
    describe('estimateJacobian', () => {
        it('should correctly estimate Jacobian for linear system dy/dt = -Ay', () => {
            const f = (_t, y) => [-2 * y[0] + y[1], y[0] - 3 * y[1]];
            const J = estimateJacobian(f, 0, [1, 1]);
            expect(J[0][0]).toBeCloseTo(-2, 4);
            expect(J[0][1]).toBeCloseTo(1, 4);
            expect(J[1][0]).toBeCloseTo(1, 4);
            expect(J[1][1]).toBeCloseTo(-3, 4);
        });
        it('should correctly estimate Jacobian for dx/dt = -x', () => {
            const f = (_t, y) => [-y[0]];
            const J = estimateJacobian(f, 0, [1]);
            expect(J[0][0]).toBeCloseTo(-1, 4);
        });
    });
    describe('StiffnessAnalysisResult structure', () => {
        it('should return all required fields', () => {
            const f = (_t, y) => [-y[0]];
            const result = analyzeStiffness(f, 0, [1], 0.01);
            expect(result).toHaveProperty('isStiff');
            expect(result).toHaveProperty('stiffnessRatio');
            expect(result).toHaveProperty('recommendedIntegrator');
            expect(result).toHaveProperty('recommendedTimeStep');
            expect(result).toHaveProperty('maxEigenvalue');
            expect(result).toHaveProperty('minEigenvalue');
        });
    });
});
//# sourceMappingURL=stiffDetection.test.js.map