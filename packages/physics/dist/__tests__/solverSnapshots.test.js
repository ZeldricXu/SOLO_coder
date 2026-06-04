import { MechanicsSolver } from '../mechanicsSolver';
import { ElectromagneticsSolver } from '../electromagneticsSolver';
import { ThermodynamicsSolver } from '../thermodynamicsSolver';
import { FluidDynamicsSolver } from '../fluidDynamicsSolver';
import { vec3 } from '@physics-sim/shared';
import { ScalarField, UniformGrid } from '@physics-sim/math';
function roundToPrecision(value, precision = 6) {
    const factor = Math.pow(10, precision);
    return Math.round(value * factor) / factor;
}
function roundVec3(v, precision = 6) {
    return vec3(roundToPrecision(v.x, precision), roundToPrecision(v.y, precision), roundToPrecision(v.z, precision));
}
function roundArray(arr, precision = 4) {
    return Array.from(arr).map(v => roundToPrecision(v, precision));
}
function sampleFieldData(field, samplePoints) {
    const results = [];
    for (const [x, y, z] of samplePoints) {
        const val = field.interpolate(x, y, z);
        if (Array.isArray(val)) {
            results.push(...val.map(v => roundToPrecision(v, 4)));
        }
        else {
            results.push(roundToPrecision(val, 4));
        }
    }
    return results;
}
describe('Solver Numerical Snapshots', () => {
    describe('MechanicsSolver snapshots', () => {
        it('free fall simulation produces consistent results', () => {
            const g = 9.81;
            const dt = 0.001;
            const totalTime = 0.5;
            const steps = Math.floor(totalTime / dt);
            const solver = new MechanicsSolver({
                gravity: vec3(0, -g, 0),
                dt,
                substeps: 1,
                useVerlet: false,
                usePBD: false,
                solverIterations: 10,
                baumgarte: 0.2,
                adaptiveStepSize: false,
                tolerance: 1e-6,
                minDt: 1e-4,
                maxDt: 0.1,
            });
            const ball = {
                id: 'snapshot-ball',
                name: 'ball',
                type: 'sphere',
                objectType: 'sphere',
                domain: ['mechanics'],
                position: vec3(0, 10, 0),
                rotation: vec3(0, 0, 0),
                isStatic: false,
                materialId: 'steel',
                mechanics: {
                    mass: 1,
                    restitution: 0.9,
                    friction: 0.1,
                    momentOfInertia: vec3(0, 0, 0),
                },
                geometry: { type: 'sphere', radius: 0.5 },
            };
            solver.addPhysicsObject(ball, vec3(0, 0, 0));
            for (let i = 0; i < steps; i++) {
                solver.step(dt);
            }
            const body = solver.getBody('snapshot-ball');
            const snapshot = {
                finalPosition: roundVec3(body.position),
                finalVelocity: roundVec3(body.velocity),
                finalRotation: roundVec3(body.rotation),
                kineticEnergy: roundToPrecision(0.5 * body.mass * (body.velocity.x ** 2 + body.velocity.y ** 2 + body.velocity.z ** 2)),
                potentialEnergy: roundToPrecision(body.mass * g * body.position.y),
            };
            expect(snapshot).toMatchSnapshot();
        });
        it('spring oscillator period is consistent', () => {
            const k = 100;
            const mass = 1;
            const dt = 0.001;
            const totalTime = 2 * Math.PI * Math.sqrt(mass / k);
            const steps = Math.floor(totalTime / dt);
            const solver = new MechanicsSolver({
                gravity: vec3(0, 0, 0),
                dt,
                substeps: 1,
                useVerlet: true,
                usePBD: false,
                solverIterations: 10,
                baumgarte: 0.2,
                adaptiveStepSize: false,
                tolerance: 1e-6,
                minDt: 1e-4,
                maxDt: 0.1,
            });
            const anchor = {
                id: 'anchor',
                name: 'anchor',
                type: 'box',
                objectType: 'box',
                domain: ['mechanics'],
                position: vec3(0, 0, 0),
                rotation: vec3(0, 0, 0),
                isStatic: true,
                materialId: 'steel',
                mechanics: { mass: 1e10, restitution: 0, friction: 0, momentOfInertia: vec3(0, 0, 0) },
                geometry: { type: 'box', width: 0.5, height: 0.5, depth: 0.5 },
            };
            const bob = {
                id: 'bob',
                name: 'bob',
                type: 'sphere',
                objectType: 'sphere',
                domain: ['mechanics'],
                position: vec3(1, 0, 0),
                rotation: vec3(0, 0, 0),
                isStatic: false,
                materialId: 'steel',
                mechanics: { mass, restitution: 0, friction: 0, momentOfInertia: vec3(0, 0, 0) },
                geometry: { type: 'sphere', radius: 0.3 },
            };
            solver.addPhysicsObject(anchor, vec3(0, 0, 0));
            solver.addPhysicsObject(bob, vec3(0, 0, 0));
            solver.addSpringConstraint({
                type: 'spring',
                bodyA: 'anchor',
                bodyB: 'bob',
                anchorA: vec3(0, 0, 0),
                anchorB: vec3(0, 0, 0),
                restLength: 1.0,
                stiffness: k,
                damping: 0,
            });
            const positions = [];
            for (let i = 0; i < steps; i++) {
                solver.step(dt);
                if (i % 50 === 0) {
                    const body = solver.getBody('bob');
                    positions.push(roundToPrecision(body.position.x, 4));
                }
            }
            const finalBob = solver.getBody('bob');
            const snapshot = {
                finalPosition: roundVec3(finalBob.position),
                finalVelocity: roundVec3(finalBob.velocity),
                sampledPositions: positions,
                periodCheck: roundToPrecision(Math.abs(finalBob.position.x - 1.0), 3),
            };
            expect(snapshot).toMatchSnapshot();
        });
    });
    describe('ElectromagneticsSolver snapshots', () => {
        it('point charge field distribution matches expectations', () => {
            const solver = new ElectromagneticsSolver({
                dimensions: vec3(2, 2, 2),
                resolution: vec3(16, 16, 16),
                origin: vec3(-1, -1, -1),
                use3D: false,
                maxIterations: 500,
                tolerance: 1e-8,
                relaxationFactor: 1.5,
            });
            solver.addCharge(vec3(0, 0, 0), 1e-9);
            const result = solver.solveElectrostatic();
            const potentialField = solver.getPotentialFieldData();
            const electricField = solver.getElectricFieldData();
            const samplePoints = [
                [0.5, 0, 0],
                [0, 0.5, 0],
                [0.707, 0.707, 0],
                [-0.5, 0, 0],
            ];
            const snapshot = {
                iterations: result.iterations,
                residual: roundToPrecision(result.residual, 10),
                potentialSamples: sampleFieldData(potentialField, samplePoints),
                electricFieldSamples: sampleFieldData(electricField, samplePoints),
            };
            expect(snapshot).toMatchSnapshot();
        });
        it('parallel plate capacitor field is uniform', () => {
            const solver = new ElectromagneticsSolver({
                dimensions: vec3(1, 1, 0.1),
                resolution: vec3(20, 20, 4),
                origin: vec3(-0.5, -0.5, -0.05),
                use3D: false,
                maxIterations: 1000,
                tolerance: 1e-8,
                relaxationFactor: 1.5,
            });
            solver.setBoundaryCondition('potential', [
                { type: 'dirichlet', faceIndex: 2, value: 100 },
                { type: 'dirichlet', faceIndex: 3, value: -100 },
            ]);
            const result = solver.solveElectrostatic();
            const electricField = solver.getElectricFieldData();
            const samplePoints = [
                [0, 0.25, 0],
                [0, 0, 0],
                [0, -0.25, 0],
                [0.25, 0, 0],
                [-0.25, 0, 0],
            ];
            const fieldValues = sampleFieldData(electricField, samplePoints);
            const fieldMagnitudes = [];
            for (let i = 0; i < fieldValues.length; i += 3) {
                const mag = Math.sqrt(fieldValues[i] ** 2 + fieldValues[i + 1] ** 2 + fieldValues[i + 2] ** 2);
                fieldMagnitudes.push(roundToPrecision(mag, 4));
            }
            const snapshot = {
                iterations: result.iterations,
                electricFieldComponents: fieldValues,
                fieldMagnitudes,
                uniformity: roundToPrecision(Math.max(...fieldMagnitudes) - Math.min(...fieldMagnitudes), 4),
            };
            expect(snapshot).toMatchSnapshot();
        });
    });
    describe('ThermodynamicsSolver snapshots', () => {
        it('1D steady-state heat conduction linear profile', () => {
            const T_left = 400;
            const T_right = 300;
            const solver = new ThermodynamicsSolver({
                dimensions: vec3(1, 0.1, 0.1),
                resolution: vec3(32, 4, 1),
                origin: vec3(0, 0, 0),
                use3D: false,
                maxIterations: 10000,
                tolerance: 1e-8,
            });
            solver.addThermalBody(vec3(0.5, 0.05, 0.05), vec3(1, 0.1, 0.1), 'copper', 350);
            solver.setBoundaryConditions([
                { type: 'dirichlet', faceIndex: 0, value: T_left },
                { type: 'dirichlet', faceIndex: 1, value: T_right },
            ]);
            for (let i = 0; i < 3000; i++) {
                solver.step(0.01);
            }
            const tempField = solver.getTemperatureFieldData();
            const samplePoints = [
                [0.1, 0.05, 0.05],
                [0.3, 0.05, 0.05],
                [0.5, 0.05, 0.05],
                [0.7, 0.05, 0.05],
                [0.9, 0.05, 0.05],
            ];
            const temperatures = sampleFieldData(tempField, samplePoints);
            const slopes = [];
            for (let i = 1; i < temperatures.length; i++) {
                slopes.push(roundToPrecision((temperatures[i] - temperatures[i - 1]) / 0.2, 4));
            }
            const snapshot = {
                temperatures,
                slopes,
                linearityCheck: roundToPrecision(Math.max(...slopes) - Math.min(...slopes), 4),
                avgTemperature: roundToPrecision(temperatures.reduce((a, b) => a + b, 0) / temperatures.length, 4),
            };
            expect(snapshot).toMatchSnapshot();
        });
        it('heat flux continuity at material interface', () => {
            const solver = new ThermodynamicsSolver({
                dimensions: vec3(1, 0.1, 0.1),
                resolution: vec3(40, 4, 1),
                origin: vec3(0, 0, 0),
                use3D: false,
                maxIterations: 10000,
                tolerance: 1e-8,
            });
            solver.addThermalBody(vec3(0.25, 0.05, 0.05), vec3(0.5, 0.1, 0.1), 'copper', 350);
            solver.addThermalBody(vec3(0.75, 0.05, 0.05), vec3(0.5, 0.1, 0.1), 'aluminum', 350);
            solver.setBoundaryConditions([
                { type: 'dirichlet', faceIndex: 0, value: 400 },
                { type: 'dirichlet', faceIndex: 1, value: 300 },
            ]);
            for (let i = 0; i < 5000; i++) {
                solver.step(0.001);
            }
            const tempField = solver.getTemperatureFieldData();
            const samplePoints = [
                [0.45, 0.05, 0.05],
                [0.5, 0.05, 0.05],
                [0.55, 0.05, 0.05],
            ];
            const temperatures = sampleFieldData(tempField, samplePoints);
            const gradientLeft = (temperatures[1] - temperatures[0]) / 0.05;
            const gradientRight = (temperatures[2] - temperatures[1]) / 0.05;
            const snapshot = {
                temperatures,
                gradientLeft: roundToPrecision(gradientLeft, 4),
                gradientRight: roundToPrecision(gradientRight, 4),
                interfaceTemperature: temperatures[1],
                fluxRatio: roundToPrecision(Math.abs(gradientLeft / gradientRight), 4),
            };
            expect(snapshot).toMatchSnapshot();
        });
    });
    describe('FluidDynamicsSolver snapshots', () => {
        it('LBM D2Q9 channel flow develops consistent velocity profile', () => {
            const nx = 60;
            const ny = 20;
            const solver = new FluidDynamicsSolver({
                width: nx,
                height: ny,
                viscosity: 0.02,
                inletVelocity: vec3(0.1, 0, 0),
                dt: 1.0,
                dx: 1.0,
                maxIterations: 10000,
                boundaryConditions: [{ type: 'bounce-back' }],
            });
            const steps = 500;
            for (let i = 0; i < steps; i++) {
                solver.step(1);
            }
            const velocityField = solver.getVelocityField();
            const densityField = solver.getDensityField();
            const samplePoints = [
                [30, 5, 0],
                [30, 10, 0],
                [30, 15, 0],
                [15, 10, 0],
                [45, 10, 0],
            ];
            const velocitySamples = sampleFieldData(velocityField, samplePoints);
            const densitySamples = sampleFieldData(densityField, samplePoints);
            const velocityMagnitudes = [];
            for (let i = 0; i < velocitySamples.length; i += 3) {
                const mag = Math.sqrt(velocitySamples[i] ** 2 + velocitySamples[i + 1] ** 2 + velocitySamples[i + 2] ** 2);
                velocityMagnitudes.push(roundToPrecision(mag, 4));
            }
            const snapshot = {
                iteration: solver.getIteration(),
                velocityComponents: velocitySamples,
                velocityMagnitudes,
                densitySamples,
                avgDensity: roundToPrecision(densitySamples.reduce((a, b) => a + b, 0) / densitySamples.length, 4),
                tau: roundToPrecision(solver.getGridInfo().tau, 6),
            };
            expect(snapshot).toMatchSnapshot();
        });
        it('uniform grid access is consistent with legacy API', () => {
            const nx = 30;
            const ny = 20;
            const solver = new FluidDynamicsSolver({
                width: nx,
                height: ny,
                viscosity: 0.01,
                inletVelocity: vec3(0.05, 0, 0),
                dt: 1.0,
                dx: 1.0,
                maxIterations: 10000,
                boundaryConditions: [],
            });
            const steps = 100;
            for (let i = 0; i < steps; i++) {
                solver.step(1);
            }
            const uniformGrid = solver.getUniformGrid();
            const densityField = solver.getDensityField();
            const testX = 15;
            const testY = 10;
            const unifiedIdx = uniformGrid.getIndex(testX, testY, 0);
            const fluidIdx = testY * nx + testX;
            const unifiedDensity = densityField.getScalar(testX, testY, 0);
            const legacyDensity = solver.getDensityAt(testX, testY);
            const snapshot = {
                gridDimensions: [uniformGrid.dimension, uniformGrid.spacing, uniformGrid.dataLocation],
                totalNodes: uniformGrid.totalNodes,
                coordinateAtOrigin: uniformGrid.getCoordinate(0, 0, 0),
                coordinateAtTest: uniformGrid.getCoordinate(testX, testY, 0),
                indexConsistency: { unifiedIdx, fluidIdx, match: unifiedIdx === fluidIdx },
                densityConsistency: {
                    unified: roundToPrecision(unifiedDensity, 6),
                    legacy: roundToPrecision(legacyDensity, 6),
                    match: Math.abs(unifiedDensity - legacyDensity) < 1e-10,
                },
            };
            expect(snapshot).toMatchSnapshot();
        });
    });
    describe('Unified Grid/Field consistency checks', () => {
        it('UniformGrid 2D indexing is row-major (x-fastest)', () => {
            const nx = 10;
            const ny = 8;
            const grid = UniformGrid.create2D(10, 8, nx, ny, [0, 0, 0], 'node');
            const indices = [];
            for (let y = 0; y < ny; y++) {
                for (let x = 0; x < nx; x++) {
                    indices.push({ x, y, idx: grid.getIndex(x, y, 0) });
                }
            }
            const expectedOrder = indices.map(i => i.idx);
            const isXFastest = expectedOrder.every((v, i) => v === i);
            const roundtripChecks = indices.map(({ x, y, idx }) => {
                const [rx, ry, rz] = grid.getIndices(idx);
                return { x, y, idx, roundtrip: rx === x && ry === y && rz === 0 };
            });
            const allRoundtrip = roundtripChecks.every(c => c.roundtrip);
            const snapshot = {
                gridInfo: {
                    dimension: grid.dimension,
                    spacing: grid.spacing,
                    totalNodes: grid.totalNodes,
                    isXFastest,
                    allRoundtrip,
                },
                sampleIndices: indices.slice(0, 5),
                sampleRoundtrips: roundtripChecks.slice(0, 5),
            };
            expect(snapshot).toMatchSnapshot();
        });
        it('ScalarField interpolation is consistent', () => {
            const nx = 5;
            const ny = 5;
            const grid = UniformGrid.create2D(4, 4, nx, ny, [0, 0, 0], 'node');
            const field = new ScalarField(grid, undefined, 'test', 0);
            for (let y = 0; y < ny; y++) {
                for (let x = 0; x < nx; x++) {
                    field.setScalar(x + y * 10, x, y, 0);
                }
            }
            const testPoints = [
                { x: 0, y: 0 },
                { x: 2, y: 2 },
                { x: 0.5, y: 0.5 },
                { x: 1.5, y: 2.5 },
                { x: 3.2, y: 1.8 },
            ];
            const nearestResults = testPoints.map(p => ({
                point: p,
                value: roundToPrecision(field.interpolate(p.x, p.y, 0, 'nearest'), 6),
            }));
            const linearResults = testPoints.map(p => ({
                point: p,
                value: roundToPrecision(field.interpolate(p.x, p.y, 0, 'linear'), 6),
            }));
            const snapshot = {
                fieldData: roundArray(field.data, 4),
                nearestResults,
                linearResults,
            };
            expect(snapshot).toMatchSnapshot();
        });
    });
});
//# sourceMappingURL=solverSnapshots.test.js.map