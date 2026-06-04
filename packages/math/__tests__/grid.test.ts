import { UniformGrid, NonUniformGrid } from '../src/grid';

describe('UniformGrid', () => {
  describe('2D Grid', () => {
    let grid2D: UniformGrid;

    beforeEach(() => {
      grid2D = UniformGrid.create2D(10, 8, 11, 9, [0, 0, 0], 'node');
    });

    test('should have correct resolution and cell size', () => {
      expect(grid2D.dimension).toBe(2);
      expect(grid2D.spacing).toBe('uniform');
      expect(grid2D.dataLocation).toBe('node');
      expect(grid2D.resolution).toEqual([11, 9, 1]);
      expect(grid2D.totalNodes).toBe(99);
      expect(grid2D.dimensions[0]).toBeCloseTo(10);
      expect(grid2D.dimensions[1]).toBeCloseTo(8);
      expect(grid2D.cellSize[0]).toBeCloseTo(1);
      expect(grid2D.cellSize[1]).toBeCloseTo(1);
    });

    test('getIndex should map (i,j,k) to correct 1D index', () => {
      expect(grid2D.getIndex(0, 0, 0)).toBe(0);
      expect(grid2D.getIndex(10, 0, 0)).toBe(10);
      expect(grid2D.getIndex(0, 1, 0)).toBe(11);
      expect(grid2D.getIndex(5, 4, 0)).toBe(5 + 4 * 11);
      expect(grid2D.getIndex(10, 8, 0)).toBe(10 + 8 * 11);
    });

    test('getIndices should reverse getIndex correctly', () => {
      const idx = grid2D.getIndex(5, 3, 0);
      expect(grid2D.getIndices(idx)).toEqual([5, 3, 0]);
    });

    test('getCoordinate should return correct world position for nodes', () => {
      expect(grid2D.getCoordinate(0, 0, 0)).toEqual([0, 0, 0]);
      expect(grid2D.getCoordinate(10, 0, 0)).toEqual([10, 0, 0]);
      expect(grid2D.getCoordinate(0, 8, 0)).toEqual([0, 8, 0]);
      expect(grid2D.getCoordinate(10, 8, 0)).toEqual([10, 8, 0]);
      expect(grid2D.getCoordinate(5, 4, 0)).toEqual([5, 4, 0]);
    });

    test('getCellCenter should return cell centers', () => {
      expect(grid2D.getCellCenter(0, 0, 0)).toEqual([0.5, 0.5, 0.5]);
      expect(grid2D.getCellCenter(5, 3, 0)).toEqual([5.5, 3.5, 0.5]);
    });

    test('findIndex should find correct grid index from coordinates', () => {
      expect(grid2D.findIndex(0, 0, 0)).toEqual([0, 0, 0]);
      expect(grid2D.findIndex(10, 8, 0)).toEqual([10, 8, 0]);
      expect(grid2D.findIndex(5.2, 3.7, 0)).toEqual([5, 4, 0]);
      expect(grid2D.findIndex(5.6, 3.2, 0)).toEqual([6, 3, 0]);
    });

    test('isInside should correctly identify points inside the grid', () => {
      expect(grid2D.isInside(0, 0, 0)).toBe(true);
      expect(grid2D.isInside(10, 8, 0)).toBe(true);
      expect(grid2D.isInside(5, 4, 0)).toBe(true);
      expect(grid2D.isInside(-1, 0, 0)).toBe(false);
      expect(grid2D.isInside(0, -1, 0)).toBe(false);
      expect(grid2D.isInside(11, 0, 0)).toBe(false);
      expect(grid2D.isInside(0, 9, 0)).toBe(false);
    });

    test('cell-centered grid should have correct coordinates', () => {
      const cellGrid = UniformGrid.create2D(10, 8, 10, 8, [0, 0, 0], 'cell-center');
      expect(cellGrid.dataLocation).toBe('cell-center');
      expect(cellGrid.getCoordinate(0, 0, 0)).toEqual([0.5, 0.5, 0.5]);
      expect(cellGrid.getCoordinate(9, 7, 0)).toEqual([9.5, 7.5, 0.5]);
      expect(cellGrid.cellSize[0]).toBeCloseTo(1);
      expect(cellGrid.cellSize[1]).toBeCloseTo(1);
    });
  });

  describe('3D Grid', () => {
    let grid3D: UniformGrid;

    beforeEach(() => {
      grid3D = UniformGrid.create3D(10, 10, 10, 5, 5, 5, [0, 0, 0], 'node');
    });

    test('should have correct 3D resolution', () => {
      expect(grid3D.dimension).toBe(3);
      expect(grid3D.resolution).toEqual([5, 5, 5]);
      expect(grid3D.totalNodes).toBe(125);
    });

    test('getIndex should work for 3D', () => {
      expect(grid3D.getIndex(0, 0, 0)).toBe(0);
      expect(grid3D.getIndex(4, 0, 0)).toBe(4);
      expect(grid3D.getIndex(0, 1, 0)).toBe(5);
      expect(grid3D.getIndex(0, 0, 1)).toBe(25);
      expect(grid3D.getIndex(2, 3, 4)).toBe(2 + 3 * 5 + 4 * 5 * 5);
    });

    test('getIndices should reverse getIndex in 3D', () => {
      const idx = grid3D.getIndex(2, 3, 4);
      expect(grid3D.getIndices(idx)).toEqual([2, 3, 4]);
    });

    test('getCoordinate should work in 3D', () => {
      expect(grid3D.getCoordinate(0, 0, 0)).toEqual([0, 0, 0]);
      expect(grid3D.getCoordinate(4, 4, 4)).toEqual([10, 10, 10]);
      expect(grid3D.getCoordinate(2, 2, 2)).toEqual([5, 5, 5]);
    });

    test('clone should create independent copy', () => {
      const cloned = grid3D.clone();
      expect(cloned).not.toBe(grid3D);
      expect(cloned.resolution).toEqual(grid3D.resolution);
      expect(cloned.origin).toEqual(grid3D.origin);
      expect(cloned.cellSize).toEqual(grid3D.cellSize);
    });
  });
});

describe('NonUniformGrid', () => {
  let nonUniformGrid: NonUniformGrid;
  let xCoords: number[];
  let yCoords: number[];

  beforeEach(() => {
    xCoords = [0, 1, 3, 6, 10];
    yCoords = [0, 2, 5, 9];
    nonUniformGrid = new NonUniformGrid({
      dimension: 2,
      dataLocation: 'node',
      origin: [0, 0, 0],
      coordinates: [xCoords, yCoords, [0]],
    });
  });

  test('should have correct properties', () => {
    expect(nonUniformGrid.spacing).toBe('non-uniform');
    expect(nonUniformGrid.dimension).toBe(2);
    expect(nonUniformGrid.resolution).toEqual([5, 4, 1]);
    expect(nonUniformGrid.totalNodes).toBe(20);
    expect(nonUniformGrid.dimensions[0]).toBe(10);
    expect(nonUniformGrid.dimensions[1]).toBe(9);
  });

  test('getCoordinate should return the actual coordinate from array', () => {
    expect(nonUniformGrid.getCoordinate(0, 0, 0)).toEqual([0, 0, 0]);
    expect(nonUniformGrid.getCoordinate(2, 1, 0)).toEqual([3, 2, 0]);
    expect(nonUniformGrid.getCoordinate(4, 3, 0)).toEqual([10, 9, 0]);
  });

  test('findIndex should use binary search', () => {
    expect(nonUniformGrid.findIndex(0, 0, 0)).toEqual([0, 0, 0]);
    expect(nonUniformGrid.findIndex(0.6, 0, 0)).toEqual([0, 0, 0]);
    expect(nonUniformGrid.findIndex(1.5, 0, 0)).toEqual([1, 0, 0]);
    expect(nonUniformGrid.findIndex(4, 0, 0)).toEqual([2, 0, 0]);
    expect(nonUniformGrid.findIndex(8, 0, 0)).toEqual([3, 0, 0]);
    expect(nonUniformGrid.findIndex(10, 0, 0)).toEqual([4, 0, 0]);
    expect(nonUniformGrid.findIndex(5, 7, 0)).toEqual([2, 3, 0]);
  });

  test('isInside should work correctly', () => {
    expect(nonUniformGrid.isInside(0, 0, 0)).toBe(true);
    expect(nonUniformGrid.isInside(10, 9, 0)).toBe(true);
    expect(nonUniformGrid.isInside(5, 4, 0)).toBe(true);
    expect(nonUniformGrid.isInside(-1, 0, 0)).toBe(false);
    expect(nonUniformGrid.isInside(0, -1, 0)).toBe(false);
    expect(nonUniformGrid.isInside(11, 0, 0)).toBe(false);
    expect(nonUniformGrid.isInside(0, 10, 0)).toBe(false);
  });
});
