import { ReportGenerator } from '../services/ReportGenerator';
describe('ReportGenerator', () => {
    let generator;
    beforeEach(() => {
        generator = new ReportGenerator();
    });
    describe('generateLaTeX', () => {
        it('should generate valid LaTeX document structure', async () => {
            const reportData = {
                scene: {
                    id: 'test-scene',
                    name: 'Free Fall Experiment',
                    objects: [],
                    sensors: [],
                    gravity: { x: 0, y: -9.81, z: 0 },
                },
                config: {
                    duration: 10,
                    timeStep: 0.001,
                    physicsTypes: ['mechanics'],
                    solverType: 'verlet',
                    collisionIterations: 4,
                    constraintIterations: 10,
                },
                result: {
                    jobId: 'test-job',
                    success: true,
                    frames: [],
                    sensorData: {},
                    statistics: {
                        totalTime: 10,
                        timeStep: 0.001,
                        totalSteps: 10000,
                        framesRendered: 1000,
                        computationTime: 5.2,
                        stepsPerSecond: 1923,
                        realTimeFactor: 1.92,
                    },
                },
                title: 'Free Fall Experiment Report',
                author: 'Physics Lab',
                description: 'Testing gravitational free fall',
                experimentPurpose: 'Verify the relationship y = 1/2*g*t^2',
                conclusion: 'The experimental results match theoretical predictions within 1% error.',
            };
            const latex = await generator.generateLaTeX(reportData);
            expect(latex).toContain('\\documentclass');
            expect(latex).toContain('\\begin{document}');
            expect(latex).toContain('\\end{document}');
            expect(latex).toContain('Free Fall Experiment Report');
            expect(latex).toContain('Physics Lab');
        });
        it('should include parameter table in the report', async () => {
            const reportData = {
                scene: {
                    id: 'test',
                    name: 'Test',
                    objects: [],
                    sensors: [],
                    gravity: { x: 0, y: -9.81, z: 0 },
                },
                config: {
                    duration: 5,
                    timeStep: 0.01,
                    physicsTypes: ['mechanics'],
                },
                result: {
                    success: true,
                    frames: [],
                    sensorData: {},
                },
                title: 'Test Report',
            };
            const latex = await generator.generateLaTeX(reportData);
            expect(latex).toContain('tabular');
        });
    });
});
//# sourceMappingURL=reportGenerator.test.js.map