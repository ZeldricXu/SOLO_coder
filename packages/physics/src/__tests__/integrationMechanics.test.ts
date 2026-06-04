import { MechanicsSolver, SimulationEngine } from '../index';
import { vec3, PhysicsObject, SimulationConfig, DEFAULT_SIMULATION_CONFIG, Sensor } from '@physics-sim/shared';
import { computeFFT, leastSquaresLinear } from '@physics-sim/math';

function makeIncline(id: string, pos: any, angle: number): PhysicsObject {
  return {
    id,
    name: id,
    type: 'incline',
    objectType: 'incline',
    domain: ['mechanics'],
    position: pos,
    rotation: vec3(0, 0, angle),
    isStatic: true,
    materialId: 'steel',
    mechanics: { restitution: 0.3, friction: 0.1, momentOfInertia: vec3(0, 0, 0) },
    geometry: { type: 'incline', width: 5, height: 0.2, depth: 3, angle },
  } as any;
}

function makeSlider(id: string, pos: any, mass: number = 1): PhysicsObject {
  return {
    id,
    name: id,
    type: 'box',
    objectType: 'box',
    domain: ['mechanics'],
    position: pos,
    rotation: vec3(0, 0, 0),
    isStatic: false,
    materialId: 'steel',
    mechanics: { mass, restitution: 0.3, friction: 0.1, momentOfInertia: vec3(0, 0, 0) },
    geometry: { type: 'box', width: 0.5, height: 0.5, depth: 0.5 },
  } as any;
}

describe('Integration: Complete mechanics experiment pipeline', () => {
  it('should complete incline+slider experiment and collect sensor data', () => {
    const angle = Math.PI / 6;
    const g = 9.81;
    const dt = 0.001;
    const totalTime = 2.0;
    const steps = Math.floor(totalTime / dt);

    const solver = new MechanicsSolver({
      gravity: vec3(0, -g, 0),
      dt,
      substeps: 2,
      useVerlet: true,
      usePBD: true,
      solverIterations: 10,
    });

    const incline = makeIncline('incline', vec3(0, 0, 0), angle);
    const slider = makeSlider('slider', vec3(-1, 2, 0), 1.0);
    solver.addPhysicsObject(incline);
    solver.addPhysicsObject(slider, vec3(0, 0, 0));

    const sensorData: { time: number; displacement: number; velocity: number }[] = [];
    const initialPos = { ...solver.getBody('slider')!.position };

    for (let i = 0; i < steps; i++) {
      solver.step(dt);
      const body = solver.getBody('slider')!;
      if (i % 100 === 0) {
        const displacement = Math.sqrt(
          (body.position.x - initialPos.x) ** 2 +
          (body.position.y - initialPos.y) ** 2
        );
        sensorData.push({
          time: i * dt,
          displacement,
          velocity: Math.sqrt(body.velocity.x ** 2 + body.velocity.y ** 2),
        });
      }
    }

    expect(sensorData.length).toBeGreaterThan(0);
    expect(sensorData[sensorData.length - 1].time).toBeCloseTo(totalTime, 0);

    const finalBody = solver.getBody('slider')!;
    const totalDisplacement = Math.sqrt(
      (finalBody.position.x - initialPos.x) ** 2 +
      (finalBody.position.y - initialPos.y) ** 2
    );
    expect(totalDisplacement).toBeGreaterThan(0);
  });

  it('should export sensor data as CSV format', () => {
    const solver = new MechanicsSolver({
      gravity: vec3(0, -9.81, 0),
      dt: 0.001,
      substeps: 1,
      useVerlet: true,
      usePBD: false,
    });

    const ball = makeSlider('ball', vec3(0, 10, 0), 1.0);
    solver.addPhysicsObject(ball, vec3(0, 0, 0));

    const records: { time: number; y: number; vy: number }[] = [];
    for (let i = 0; i < 1000; i++) {
      solver.step(0.001);
      const body = solver.getBody('ball')!;
      if (i % 100 === 0) {
        records.push({ time: i * 0.001, y: body.position.y, vy: body.velocity.y });
      }
    }

    const csvHeader = 'time,y,vy';
    const csvRows = records.map(r => `${r.time},${r.y},${r.vy}`);
    const csv = [csvHeader, ...csvRows].join('\n');

    expect(csv).toContain('time,y,vy');
    expect(csv.split('\n').length).toBe(records.length + 1);

    const parsedRows = csv.split('\n').slice(1).map(row => {
      const [t, y, vy] = row.split(',').map(Number);
      return { t, y, vy };
    });
    expect(parsedRows[0].t).toBeCloseTo(0, 5);
  });

  it('should perform FFT on sensor time series data', () => {
    const sampleRate = 100;
    const duration = 2;
    const frequency = 5;
    const numSamples = sampleRate * duration;

    const signal: number[] = [];
    for (let i = 0; i < numSamples; i++) {
      const t = i / sampleRate;
      signal.push(Math.sin(2 * Math.PI * frequency * t));
    }

    const result = computeFFT(signal, sampleRate);
    expect(result).toBeDefined();
    expect(result.frequencies.length).toBeGreaterThan(0);
    expect(result.magnitudes.length).toBeGreaterThan(0);

    const peakIdx = result.magnitudes.indexOf(Math.max(...result.magnitudes));
    const peakFreq = result.frequencies[peakIdx];
    expect(Math.abs(peakFreq - frequency)).toBeLessThan(1);
  });

  it('should perform curve fitting on sensor data', () => {
    const x: number[] = [];
    const y: number[] = [];
    for (let i = 0; i < 20; i++) {
      const xi = i * 0.5;
      x.push(xi);
      y.push(2.5 * xi + 1.2 + (Math.random() - 0.5) * 0.01);
    }

    const result = leastSquaresLinear(x, y);
    expect(result).toBeDefined();
    expect(result.a).toBeCloseTo(2.5, 0);
    expect(result.b).toBeCloseTo(1.2, 0);
    expect(result.rSquared).toBeGreaterThan(0.99);
  });
});
