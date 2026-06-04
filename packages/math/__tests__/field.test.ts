import { UniformGrid } from '../src/grid';
import {
  ScalarField,
  VectorField,
  TensorField,
  addFields,
  subtractFields,
  multiplyFieldByScalar,
  gradient,
  divergence,
  curl,
} from '../src/field';

describe('ScalarField', () => {
  let grid: UniformGrid;
  let scalarField: ScalarField;

  beforeEach(() => {
    grid = UniformGrid.create2D(10, 10, 11, 11, [0, 0, 0], 'node');
    scalarField = new ScalarField(grid);
  });

  test('should initialize with zeros', () => {
    expect(scalarField.type).toBe('scalar');
    expect(scalarField.components).toBe(1);
    expect(scalarField.grid.totalNodes).toBe(grid.totalNodes);
    expect(scalarField.data.length).toBe(grid.totalNodes);
    expect(scalarField.data.every((v: number) => v === 0)).toBe(true);
  });

  test('should set and get values correctly', () => {
    scalarField.setScalar(42, 5, 5, 0);
    expect(scalarField.getScalar(5, 5, 0)).toBe(42);

    scalarField.set(99, 3, 4, 0);
    expect(scalarField.get(3, 4, 0)).toBe(99);
  });

  test('should add values', () => {
    scalarField.setScalar(10, 2, 3, 0);
    scalarField.add(5, 2, 3, 0);
    expect(scalarField.getScalar(2, 3, 0)).toBe(15);
  });

  test('should multiply values', () => {
    scalarField.setScalar(10, 1, 1, 0);
    scalarField.multiply(3, 1, 1, 0);
    expect(scalarField.getScalar(1, 1, 0)).toBe(30);
  });

  test('should multiply entire field when no indices provided', () => {
    scalarField.setScalar(1, 0, 0, 0);
    scalarField.setScalar(2, 1, 0, 0);
    scalarField.setScalar(3, 0, 1, 0);
    scalarField.multiply(2);
    expect(scalarField.getScalar(0, 0, 0)).toBe(2);
    expect(scalarField.getScalar(1, 0, 0)).toBe(4);
    expect(scalarField.getScalar(0, 1, 0)).toBe(6);
  });

  test('should interpolate values (nearest)', () => {
    scalarField.setScalar(0, 0, 0, 0);
    scalarField.setScalar(10, 10, 0, 0);
    scalarField.setScalar(20, 0, 10, 0);
    scalarField.setScalar(30, 10, 10, 0);

    expect(scalarField.interpolateScalar(0.2, 0.3, 0, 'nearest')).toBe(0);
    expect(scalarField.interpolateScalar(9.8, 0.1, 0, 'nearest')).toBe(10);
  });

  test('should interpolate values (linear)', () => {
    scalarField.setScalar(0, 0, 0, 0);
    scalarField.setScalar(10, 10, 0, 0);
    scalarField.setScalar(20, 0, 10, 0);
    scalarField.setScalar(30, 10, 10, 0);

    expect(scalarField.interpolateScalar(5, 0, 0)).toBeCloseTo(5);
    expect(scalarField.interpolateScalar(0, 5, 0)).toBeCloseTo(10);
    expect(scalarField.interpolateScalar(5, 5, 0)).toBeCloseTo(15);
    expect(scalarField.interpolateScalar(10, 10, 0)).toBeCloseTo(30);
  });

  test('should iterate with forEach', () => {
    scalarField.setScalar(1, 0, 0, 0);
    scalarField.setScalar(2, 1, 0, 0);

    const values: Array<{ value: number; i: number; j: number; k: number }> = [];
    scalarField.forEach((value, idx, i, j, k) => {
      if ((value as number) > 0) {
        values.push({ value: value as number, i, j, k });
      }
    });

    expect(values).toHaveLength(2);
    expect(values[0]).toEqual({ value: 1, i: 0, j: 0, k: 0 });
    expect(values[1]).toEqual({ value: 2, i: 1, j: 0, k: 0 });
  });

  test('should clone properly', () => {
    scalarField.setScalar(100, 5, 5, 0);
    const cloned = scalarField.clone();

    expect(cloned).not.toBe(scalarField);
    expect(cloned.data).not.toBe(scalarField.data);
    expect(cloned.getScalar(5, 5, 0)).toBe(100);

    cloned.setScalar(200, 5, 5, 0);
    expect(scalarField.getScalar(5, 5, 0)).toBe(100);
  });

  test('should support cell-centered grid', () => {
    const cellGrid = UniformGrid.create2D(10, 10, 10, 10, [0, 0, 0], 'cell-center');
    const cellField = new ScalarField(cellGrid);

    cellField.setScalar(5, 0, 0, 0);
    expect(cellField.interpolateScalar(0.5, 0.5, 0)).toBe(5);
  });
});

describe('VectorField', () => {
  let grid: UniformGrid;
  let vectorField: VectorField;

  beforeEach(() => {
    grid = UniformGrid.create2D(10, 10, 11, 11, [0, 0, 0], 'node');
    vectorField = new VectorField(grid, 2);
  });

  test('should initialize correctly', () => {
    expect(vectorField.type).toBe('vector');
    expect(vectorField.components).toBe(2);
    expect(vectorField.data.length).toBe(grid.totalNodes * 2);
    expect(vectorField.componentNames).toEqual(['x', 'y']);
  });

  test('should set and get vectors', () => {
    vectorField.setVector([3, 4], 2, 3, 0);
    expect(vectorField.getVector(2, 3, 0)).toEqual([3, 4]);

    vectorField.set([5, 6], 7, 8, 0);
    expect(vectorField.get(7, 8, 0)).toEqual([5, 6]);
  });

  test('should add vectors', () => {
    vectorField.setVector([1, 2], 0, 0, 0);
    vectorField.add([3, 4], 0, 0, 0);
    expect(vectorField.getVector(0, 0, 0)).toEqual([4, 6]);
  });

  test('should interpolate vectors linearly', () => {
    vectorField.setVector([0, 0], 0, 0, 0);
    vectorField.setVector([10, 0], 10, 0, 0);
    vectorField.setVector([0, 10], 0, 10, 0);
    vectorField.setVector([10, 10], 10, 10, 0);

    const interpolated = vectorField.interpolateVector(5, 5, 0);
    expect(interpolated[0]).toBeCloseTo(5);
    expect(interpolated[1]).toBeCloseTo(5);
  });

  test('should compute magnitude', () => {
    vectorField.setVector([3, 4], 0, 0, 0);
    vectorField.setVector([0, 0], 1, 0, 0);
    vectorField.setVector([5, 12], 2, 0, 0);

    const magnitude = vectorField.magnitude();
    expect(magnitude.getScalar(0, 0, 0)).toBeCloseTo(5);
    expect(magnitude.getScalar(1, 0, 0)).toBe(0);
    expect(magnitude.getScalar(2, 0, 0)).toBeCloseTo(13);
  });

  test('should support 3 component vectors', () => {
    const vf3 = new VectorField(grid, 3);
    expect(vf3.components).toBe(3);
    expect(vf3.data.length).toBe(grid.totalNodes * 3);
    expect(vf3.componentNames).toEqual(['x', 'y', 'z']);

    vf3.setVector([1, 2, 3], 5, 5, 0);
    expect(vf3.getVector(5, 5, 0)).toEqual([1, 2, 3]);
  });
});

describe('Field Operations', () => {
  let grid: UniformGrid;

  beforeEach(() => {
    grid = UniformGrid.create2D(10, 10, 11, 11, [0, 0, 0], 'node');
  });

  test('addFields should add two scalar fields', () => {
    const a = new ScalarField(grid);
    const b = new ScalarField(grid);
    a.setScalar(1, 0, 0, 0);
    a.setScalar(2, 1, 0, 0);
    b.setScalar(3, 0, 0, 0);
    b.setScalar(4, 1, 0, 0);

    const result = addFields(a, b);
    expect(result.getScalar(0, 0, 0)).toBe(4);
    expect(result.getScalar(1, 0, 0)).toBe(6);
  });

  test('subtractFields should subtract two scalar fields', () => {
    const a = new ScalarField(grid);
    const b = new ScalarField(grid);
    a.setScalar(5, 0, 0, 0);
    b.setScalar(3, 0, 0, 0);

    const result = subtractFields(a, b);
    expect(result.getScalar(0, 0, 0)).toBe(2);
  });

  test('multiplyFieldByScalar should multiply by scalar', () => {
    const f = new ScalarField(grid);
    f.setScalar(10, 0, 0, 0);
    f.setScalar(5, 1, 0, 0);

    const result = multiplyFieldByScalar(f, 2);
    expect(result.getScalar(0, 0, 0)).toBe(20);
    expect(result.getScalar(1, 0, 0)).toBe(10);
  });

  test('gradient should compute gradient of scalar field', () => {
    const f = new ScalarField(grid);
    for (let j = 0; j < 11; j++) {
      for (let i = 0; i < 11; i++) {
        f.setScalar(i, i, j, 0);
      }
    }

    const grad = gradient(f);
    expect(grad.type).toBe('vector');
    expect(grad.components).toBe(3);

    const g = grad.getVector(5, 5, 0);
    expect(g[0]).toBeCloseTo(1, 0.001);
    expect(g[1]).toBeCloseTo(0, 0.001);
  });

  test('divergence should compute divergence of vector field', () => {
    const vf = new VectorField(grid, 2);
    for (let j = 0; j < 11; j++) {
      for (let i = 0; i < 11; i++) {
        vf.setVector([i, j], i, j, 0);
      }
    }

    const div = divergence(vf);
    const d = div.getScalar(5, 5, 0);
    expect(d).toBeCloseTo(2, 0.001);
  });

  test('curl should compute curl of vector field in 3D', () => {
    const grid3D = UniformGrid.create3D(10, 10, 10, 11, 11, 11, [0, 0, 0], 'node');
    const vf = new VectorField(grid3D, 3);

    for (let k = 0; k < 11; k++) {
      for (let j = 0; j < 11; j++) {
        for (let i = 0; i < 11; i++) {
          vf.setVector([-j, i, 0], i, j, k);
        }
      }
    }

    const c = curl(vf);
    const curlVal = c.getVector(5, 5, 5);
    expect(curlVal[2]).toBeCloseTo(2, 0.001);
  });
});

describe('TensorField', () => {
  test('should create tensor field with 9 components', () => {
    const grid = UniformGrid.create2D(10, 10, 5, 5, [0, 0, 0], 'node');
    const tensor = new TensorField(grid);

    expect(tensor.type).toBe('tensor');
    expect(tensor.components).toBe(9);
    expect(tensor.data.length).toBe(25 * 9);
    expect(tensor.componentNames).toEqual(['xx', 'xy', 'xz', 'yx', 'yy', 'yz', 'zx', 'zy', 'zz']);
  });
});
