import { computeStreamlines, generateStreamlineSeedPoints, ParticleSystem, computeCrossSection } from '../fieldVisualization';
import { vec3, VectorField, ScalarField, FieldGrid, CrossSectionPlane, ParticleSystemConfig } from '@physics-sim/shared';

function makeUniformVectorField(): VectorField {
  const grid: FieldGrid = {
    dimensions: vec3(10, 10, 0),
    resolution: vec3(20, 20, 1),
    cellSize: vec3(0.5, 0.5, 1),
    origin: vec3(0, 0, 0),
  };
  const size = 20 * 20;
  return {
    id: 'test-vf',
    type: 'velocity',
    grid,
    dataX: new Float32Array(size).fill(1.0),
    dataY: new Float32Array(size).fill(0.0),
    dataZ: new Float32Array(size).fill(0.0),
    time: 0,
  };
}

function makeZeroVectorField(): VectorField {
  const grid: FieldGrid = {
    dimensions: vec3(10, 10, 0),
    resolution: vec3(20, 20, 1),
    cellSize: vec3(0.5, 0.5, 1),
    origin: vec3(0, 0, 0),
  };
  const size = 20 * 20;
  return {
    id: 'test-zero-vf',
    type: 'velocity',
    grid,
    dataX: new Float32Array(size).fill(0.0),
    dataY: new Float32Array(size).fill(0.0),
    dataZ: new Float32Array(size).fill(0.0),
    time: 0,
  };
}

function makeScalarField(): ScalarField {
  const grid: FieldGrid = {
    dimensions: vec3(10, 10, 0),
    resolution: vec3(20, 20, 1),
    cellSize: vec3(0.5, 0.5, 1),
    origin: vec3(0, 0, 0),
  };
  const size = 20 * 20;
  const data = new Float32Array(size);
  for (let i = 0; i < size; i++) {
    data[i] = i / size;
  }
  return {
    id: 'test-sf',
    type: 'thermal',
    grid,
    data,
    time: 0,
  };
}

function makeParticleSystemConfig(fieldId: string): ParticleSystemConfig {
  return {
    fieldId,
    emitRate: 10,
    maxParticles: 100,
    particleLifetime: 5,
    colorBy: 'velocity',
    particleSize: 1,
    speedScale: 1,
  };
}

describe('fieldVisualization', () => {
  describe('computeStreamlines', () => {
    it('should produce streamlines following the field direction with uniform velocity field', () => {
      const field = makeUniformVectorField();
      const seedPoints = [vec3(2.5, 2.5, 0)];
      const streamlines = computeStreamlines(field, seedPoints);

      expect(streamlines.length).toBe(1);
      const sl = streamlines[0];
      expect(sl.points.length).toBeGreaterThan(1);
      expect(sl.fieldId).toBe('test-vf');

      for (const pt of sl.points) {
        expect(pt.direction.x).toBeCloseTo(1.0, 5);
        expect(pt.direction.y).toBeCloseTo(0.0, 5);
        expect(pt.direction.z).toBeCloseTo(0.0, 5);
      }

      for (let i = 1; i < sl.points.length; i++) {
        expect(sl.points[i].position.x).toBeGreaterThan(sl.points[i - 1].position.x);
      }
    });

    it('should return empty array with zero field', () => {
      const field = makeZeroVectorField();
      const seedPoints = [vec3(2.5, 2.5, 0)];
      const streamlines = computeStreamlines(field, seedPoints);

      expect(streamlines).toEqual([]);
    });
  });

  describe('generateStreamlineSeedPoints', () => {
    it('should generate points within field bounds', () => {
      const field = makeUniformVectorField();
      const seeds = generateStreamlineSeedPoints(field, 5);

      expect(seeds.length).toBeGreaterThan(0);

      const grid = field.grid;
      for (const s of seeds) {
        expect(s.x).toBeGreaterThanOrEqual(grid.origin.x);
        expect(s.x).toBeLessThanOrEqual(grid.origin.x + grid.dimensions.x);
        expect(s.y).toBeGreaterThanOrEqual(grid.origin.y);
        expect(s.y).toBeLessThanOrEqual(grid.origin.y + grid.dimensions.y);
      }
    });
  });

  describe('ParticleSystem', () => {
    it('should initialize and update particles with uniform field', () => {
      const field = makeUniformVectorField();
      const config = makeParticleSystemConfig(field.id);
      const ps = new ParticleSystem(config);

      const particles = ps.update(field, 1.0);

      expect(particles.length).toBeGreaterThan(0);

      for (const p of particles) {
        if (p.age > 1.0) {
          expect(p.position.x).toBeGreaterThan(p.previousPosition.x);
        }
      }
    });

    it('should have no particles after clear', () => {
      const field = makeUniformVectorField();
      const config = makeParticleSystemConfig(field.id);
      const ps = new ParticleSystem(config);

      ps.update(field, 1.0);
      expect(ps.getParticles().length).toBeGreaterThan(0);

      ps.clear();
      expect(ps.getParticles()).toEqual([]);
    });
  });

  describe('computeCrossSection', () => {
    it('should return a valid ScalarField for scalar field on XY plane', () => {
      const scalarField = makeScalarField();
      const plane: CrossSectionPlane = {
        id: 'test-plane',
        normal: vec3(0, 0, 1),
        position: vec3(5, 5, 0),
        fieldId: scalarField.id,
        width: 10,
        height: 10,
        resolution: vec3(10, 10, 1),
        colormap: 'viridis',
      };

      const result = computeCrossSection(scalarField, plane);

      expect(result.id).toBeDefined();
      expect(result.type).toBe('thermal');
      expect(result.grid).toBeDefined();
      expect(result.data).toBeInstanceOf(Float32Array);
      expect(result.data.length).toBe(10 * 10);
      expect(result.time).toBe(0);
    });
  });
});
