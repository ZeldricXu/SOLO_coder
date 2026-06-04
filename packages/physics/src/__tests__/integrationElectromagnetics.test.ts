import { ElectromagneticsSolver } from '../index';
import { vec3 } from '@physics-sim/shared';

describe('Integration: Complete electromagnetics experiment pipeline', () => {
  it('should place two charges, compute field, and return valid results', () => {
    const solver = new ElectromagneticsSolver({
      dimensions: vec3(20, 20, 1),
      resolution: vec3(64, 64, 1),
      origin: vec3(-10, -10, 0),
      use3D: false,
      maxIterations: 5000,
      tolerance: 1e-4,
      relaxationFactor: 1.5,
    });

    const Q1 = 2e-9;
    const Q2 = -1e-9;
    solver.addCharge(vec3(-2, 0, 0), Q1);
    solver.addCharge(vec3(2, 0, 0), Q2);

    const result = solver.solveElectrostatic();
    expect(result.iterations).toBeGreaterThan(0);
    expect(result.residual).toBeLessThan(1);

    const field = result.field;
    expect(field.dataX.length).toBeGreaterThan(0);
    expect(field.dataY.length).toBeGreaterThan(0);

    const midPoint = vec3(0, 0, 0);
    const fieldAtMid = solver.getFieldAtPosition(result.field, midPoint);
    const fieldMag = Math.sqrt(fieldAtMid.x ** 2 + fieldAtMid.y ** 2);
    expect(fieldMag).toBeGreaterThan(0);
  });

  it('should measure field strength at sensor positions', () => {
    const solver = new ElectromagneticsSolver({
      dimensions: vec3(20, 20, 1),
      resolution: vec3(48, 48, 1),
      origin: vec3(-10, -10, 0),
      use3D: false,
      maxIterations: 5000,
      tolerance: 1e-4,
    });

    solver.addCharge(vec3(0, 0, 0), 1e-9);
    const result = solver.solveElectrostatic();

    const sensorPositions = [
      vec3(1, 0, 0),
      vec3(2, 0, 0),
      vec3(3, 0, 0),
      vec3(4, 0, 0),
    ];

    const measurements = sensorPositions.map(pos => {
      const field = solver.getFieldAtPosition(result.field, pos);
      return {
        r: Math.sqrt(pos.x ** 2 + pos.y ** 2),
        eMag: Math.sqrt(field.x ** 2 + field.y ** 2),
      };
    });

    const validMeasurements = measurements.filter(m => m.eMag > 0.01);
    expect(validMeasurements.length).toBeGreaterThan(0);

    const nearField = measurements[0].eMag;
    const farField = measurements[3].eMag;
    if (nearField > 0 && farField > 0) {
      expect(nearField).toBeGreaterThan(farField);
    }
  });

  it('should generate field data suitable for visualization', () => {
    const solver = new ElectromagneticsSolver({
      dimensions: vec3(10, 10, 1),
      resolution: vec3(32, 32, 1),
      origin: vec3(-5, -5, 0),
      use3D: false,
      maxIterations: 3000,
      tolerance: 1e-3,
    });

    solver.addCharge(vec3(0, 0, 0), 1e-9);
    const result = solver.solveElectrostatic();

    expect(result.potential).toBeDefined();
    expect(result.potential!.data.length).toBeGreaterThan(0);
    expect(result.field.dataX.length).toBeGreaterThan(0);

    const grid = solver.getGrid();
    expect(grid.cellSize.x).toBeGreaterThan(0);
    expect(grid.cellSize.y).toBeGreaterThan(0);
  });
});
