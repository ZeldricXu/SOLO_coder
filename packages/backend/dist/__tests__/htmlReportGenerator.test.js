import { HTMLReportGenerator } from '../services/HTMLReportGenerator';
describe('HTMLReportGenerator', () => {
    let generator;
    beforeEach(() => {
        generator = new HTMLReportGenerator();
    });
    it('should generate valid HTML document structure', async () => {
        const html = await generator.generateHTML({
            scene: {
                id: 'test-scene',
                name: 'Test',
                objects: new Map(),
                sensors: [],
                gravity: { x: 0, y: -9.81, z: 0 },
            },
            config: {
                duration: 10,
                timeStep: 0.001,
                physicsTypes: ['mechanics'],
            },
            result: {
                success: true,
                frames: [],
                sensorData: {},
                statistics: {
                    totalTime: 10,
                    timeStep: 0.001,
                    totalSteps: 10000,
                    framesRendered: 100,
                    computationTime: 5.2,
                    stepsPerSecond: 1923,
                    realTimeFactor: 1.92,
                },
            },
        });
        expect(html).toContain('<!DOCTYPE html>');
        expect(html).toContain('<html lang="zh-CN">');
        expect(html).toContain('</html>');
        expect(html).toContain('<head>');
        expect(html).toContain('</head>');
        expect(html).toContain('<body>');
        expect(html).toContain('</body>');
    });
    it('should include custom title and author', async () => {
        const html = await generator.generateHTML({
            scene: {
                id: 'test-scene',
                name: 'Test',
                objects: new Map(),
                sensors: [],
                gravity: { x: 0, y: -9.81, z: 0 },
            },
            config: {
                duration: 10,
                timeStep: 0.001,
                physicsTypes: ['mechanics'],
            },
            result: {
                success: true,
                frames: [],
                sensorData: {},
            },
            title: 'Custom Report Title',
            author: 'Test Author',
        });
        expect(html).toContain('Custom Report Title');
        expect(html).toContain('Test Author');
    });
    it('should include experiment description and purpose', async () => {
        const html = await generator.generateHTML({
            scene: {
                id: 'test-scene',
                name: 'Test',
                objects: new Map(),
                sensors: [],
                gravity: { x: 0, y: -9.81, z: 0 },
            },
            config: {
                duration: 10,
                timeStep: 0.001,
                physicsTypes: ['mechanics'],
            },
            result: {
                success: true,
                frames: [],
                sensorData: {},
            },
            description: 'This is the experiment description',
            experimentPurpose: 'To test something',
        });
        expect(html).toContain('This is the experiment description');
        expect(html).toContain('To test something');
    });
    it('should include fluid dynamics theory when physicsTypes contains fluiddynamics', async () => {
        const html = await generator.generateHTML({
            scene: {
                id: 'test-scene',
                name: 'Test',
                objects: new Map(),
                sensors: [],
                gravity: { x: 0, y: -9.81, z: 0 },
            },
            config: {
                duration: 10,
                timeStep: 0.001,
                physicsTypes: ['fluiddynamics'],
            },
            result: {
                success: true,
                frames: [],
                sensorData: {},
            },
        });
        expect(html).toContain('流体力学原理');
        expect(html).toContain('Boltzmann');
    });
    it('should include chart images when provided', async () => {
        const html = await generator.generateHTML({
            scene: {
                id: 'test-scene',
                name: 'Test',
                objects: new Map(),
                sensors: [],
                gravity: { x: 0, y: -9.81, z: 0 },
            },
            config: {
                duration: 10,
                timeStep: 0.001,
                physicsTypes: ['mechanics'],
            },
            result: {
                success: true,
                frames: [],
                sensorData: {},
            },
            charts: [
                { dataUrl: 'data:image/png;base64,iVBOR...', caption: 'Velocity over time' },
            ],
        });
        expect(html).toContain('data:image/png;base64');
        expect(html).toContain('Velocity over time');
    });
    it('should include performance statistics when available', async () => {
        const html = await generator.generateHTML({
            scene: {
                id: 'test-scene',
                name: 'Test',
                objects: new Map(),
                sensors: [],
                gravity: { x: 0, y: -9.81, z: 0 },
            },
            config: {
                duration: 10,
                timeStep: 0.001,
                physicsTypes: ['mechanics'],
            },
            result: {
                success: true,
                frames: [],
                sensorData: {},
                statistics: {
                    totalTime: 10,
                    timeStep: 0.001,
                    totalSteps: 10000,
                    framesRendered: 100,
                    computationTime: 5.2,
                    stepsPerSecond: 1923,
                    realTimeFactor: 1.92,
                },
            },
        });
        expect(html).toContain('10000');
        expect(html).toContain('5.200');
    });
});
//# sourceMappingURL=htmlReportGenerator.test.js.map