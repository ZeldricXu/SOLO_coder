import { IGrid, GridDimension, DataLocation, UniformGridParams, NonUniformGridParams } from '@physics-sim/shared';

export class UniformGrid implements IGrid {
  readonly dimension: GridDimension;
  readonly spacing: 'uniform' = 'uniform';
  readonly dataLocation: DataLocation;
  readonly origin: [number, number, number];
  readonly dimensions: [number, number, number];
  readonly resolution: [number, number, number];
  readonly cellSize: [number, number, number];
  readonly totalNodes: number;

  constructor(params: Omit<UniformGridParams, 'spacing'>) {
    this.dimension = params.dimension;
    this.dataLocation = params.dataLocation;
    this.origin = [...params.origin] as [number, number, number];
    this.resolution = [...params.resolution] as [number, number, number];
    this.cellSize = [...params.cellSize] as [number, number, number];
    this.dimensions = [
      this.resolution[0] * this.cellSize[0],
      this.resolution[1] * this.cellSize[1],
      this.resolution[2] * this.cellSize[2],
    ];
    this.totalNodes = this.resolution[0] * this.resolution[1] * this.resolution[2];
  }

  static create2D(
    width: number,
    height: number,
    nx: number,
    ny: number,
    origin: [number, number, number] = [0, 0, 0],
    dataLocation: DataLocation = 'node'
  ): UniformGrid {
    return new UniformGrid({
      dimension: 2,
      dataLocation,
      origin,
      resolution: [nx, ny, 1],
      cellSize: [width / (dataLocation === 'node' ? nx - 1 : nx), height / (dataLocation === 'node' ? ny - 1 : ny), 1],
    });
  }

  static create3D(
    width: number,
    height: number,
    depth: number,
    nx: number,
    ny: number,
    nz: number,
    origin: [number, number, number] = [0, 0, 0],
    dataLocation: DataLocation = 'node'
  ): UniformGrid {
    return new UniformGrid({
      dimension: 3,
      dataLocation,
      origin,
      resolution: [nx, ny, nz],
      cellSize: [
        width / (dataLocation === 'node' ? nx - 1 : nx),
        height / (dataLocation === 'node' ? ny - 1 : ny),
        depth / (dataLocation === 'node' ? nz - 1 : nz),
      ],
    });
  }

  getIndex(i: number, j: number = 0, k: number = 0): number {
    const [nx, ny] = this.resolution;
    return i + j * nx + k * nx * ny;
  }

  getIndices(index: number): [number, number, number] {
    const [nx, ny] = this.resolution;
    const k = Math.floor(index / (nx * ny));
    const rem = index % (nx * ny);
    const j = Math.floor(rem / nx);
    const i = rem % nx;
    return [i, j, k];
  }

  getCoordinate(i: number, j: number = 0, k: number = 0): [number, number, number] {
    const offset = this.dataLocation === 'cell-center' ? 0.5 : 0;
    return [
      this.origin[0] + (i + offset) * this.cellSize[0],
      this.origin[1] + (j + offset) * this.cellSize[1],
      this.origin[2] + (k + offset) * this.cellSize[2],
    ];
  }

  getCellCenter(i: number, j: number = 0, k: number = 0): [number, number, number] {
    return [
      this.origin[0] + (i + 0.5) * this.cellSize[0],
      this.origin[1] + (j + 0.5) * this.cellSize[1],
      this.origin[2] + (k + 0.5) * this.cellSize[2],
    ];
  }

  findIndex(x: number, y: number = 0, z: number = 0): [number, number, number] {
    const offset = this.dataLocation === 'cell-center' ? 0.5 : 0;
    return [
      Math.floor((x - this.origin[0]) / this.cellSize[0] - offset + 0.5),
      Math.floor((y - this.origin[1]) / this.cellSize[1] - offset + 0.5),
      Math.floor((z - this.origin[2]) / this.cellSize[2] - offset + 0.5),
    ];
  }

  isInside(x: number, y: number = 0, z: number = 0): boolean {
    const [nx, ny, nz] = this.resolution;
    const [i, j, k] = this.findIndex(x, y, z);
    return i >= 0 && i < nx && j >= 0 && j < ny && k >= 0 && k < nz;
  }

  clone(): UniformGrid {
    return new UniformGrid({
      dimension: this.dimension,
      dataLocation: this.dataLocation,
      origin: [...this.origin] as [number, number, number],
      resolution: [...this.resolution] as [number, number, number],
      cellSize: [...this.cellSize] as [number, number, number],
    });
  }

  toJSON() {
    return {
      type: 'UniformGrid',
      dimension: this.dimension,
      spacing: this.spacing,
      dataLocation: this.dataLocation,
      origin: this.origin,
      resolution: this.resolution,
      cellSize: this.cellSize,
      dimensions: this.dimensions,
    };
  }
}

export class NonUniformGrid implements IGrid {
  readonly dimension: GridDimension;
  readonly spacing: 'non-uniform' = 'non-uniform';
  readonly dataLocation: DataLocation;
  readonly origin: [number, number, number];
  readonly dimensions: [number, number, number];
  readonly resolution: [number, number, number];
  readonly cellSize: [number, number, number];
  readonly totalNodes: number;
  readonly coordinates: [number[], number[], number[]];

  constructor(params: Omit<NonUniformGridParams, 'spacing'>) {
    this.dimension = params.dimension;
    this.dataLocation = params.dataLocation;
    this.origin = [...params.origin] as [number, number, number];
    this.coordinates = [
      [...params.coordinates[0]],
      [...params.coordinates[1]],
      [...params.coordinates[2]],
    ];
    this.resolution = [
      this.coordinates[0].length,
      this.coordinates[1].length,
      this.coordinates[2].length,
    ];
    this.dimensions = [
      this.coordinates[0][this.resolution[0] - 1] - this.coordinates[0][0],
      this.coordinates[1][this.resolution[1] - 1] - this.coordinates[1][0],
      this.coordinates[2][this.resolution[2] - 1] - this.coordinates[2][0],
    ];
    this.cellSize = [
      this.dimensions[0] / Math.max(1, this.resolution[0] - 1),
      this.dimensions[1] / Math.max(1, this.resolution[1] - 1),
      this.dimensions[2] / Math.max(1, this.resolution[2] - 1),
    ];
    this.totalNodes = this.resolution[0] * this.resolution[1] * this.resolution[2];
  }

  getIndex(i: number, j: number = 0, k: number = 0): number {
    const [nx, ny] = this.resolution;
    return i + j * nx + k * nx * ny;
  }

  getIndices(index: number): [number, number, number] {
    const [nx, ny] = this.resolution;
    const k = Math.floor(index / (nx * ny));
    const rem = index % (nx * ny);
    const j = Math.floor(rem / nx);
    const i = rem % nx;
    return [i, j, k];
  }

  getCoordinate(i: number, j: number = 0, k: number = 0): [number, number, number] {
    return [
      this.coordinates[0][i] + this.origin[0],
      this.coordinates[1][j] + this.origin[1],
      this.coordinates[2][k] + this.origin[2],
    ];
  }

  getCellCenter(i: number, j: number = 0, k: number = 0): [number, number, number] {
    const [nx, ny, nz] = this.resolution;
    const cx = i < nx - 1 ? (this.coordinates[0][i] + this.coordinates[0][i + 1]) / 2 : this.coordinates[0][i];
    const cy = j < ny - 1 ? (this.coordinates[1][j] + this.coordinates[1][j + 1]) / 2 : this.coordinates[1][j];
    const cz = k < nz - 1 ? (this.coordinates[2][k] + this.coordinates[2][k + 1]) / 2 : this.coordinates[2][k];
    return [cx + this.origin[0], cy + this.origin[1], cz + this.origin[2]];
  }

  findIndex(x: number, y: number = 0, z: number = 0): [number, number, number] {
    const localX = x - this.origin[0];
    const localY = y - this.origin[1];
    const localZ = z - this.origin[2];

    return [
      this.binarySearch(localX, this.coordinates[0]),
      this.binarySearch(localY, this.coordinates[1]),
      this.binarySearch(localZ, this.coordinates[2]),
    ];
  }

  private binarySearch(value: number, arr: number[]): number {
    if (value <= arr[0]) return 0;
    if (value >= arr[arr.length - 1]) return arr.length - 1;

    let lo = 0, hi = arr.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (arr[mid] < value) lo = mid + 1;
      else hi = mid;
    }
    return lo > 0 ? (value - arr[lo - 1] < arr[lo] - value ? lo - 1 : lo) : 0;
  }

  isInside(x: number, y: number = 0, z: number = 0): boolean {
    const localX = x - this.origin[0];
    const localY = y - this.origin[1];
    const localZ = z - this.origin[2];
    const [nx, ny, nz] = this.resolution;

    return localX >= this.coordinates[0][0] && localX <= this.coordinates[0][nx - 1] &&
           localY >= this.coordinates[1][0] && localY <= this.coordinates[1][ny - 1] &&
           localZ >= this.coordinates[2][0] && localZ <= this.coordinates[2][nz - 1];
  }

  clone(): NonUniformGrid {
    return new NonUniformGrid({
      dimension: this.dimension,
      dataLocation: this.dataLocation,
      origin: [...this.origin] as [number, number, number],
      coordinates: [
        [...this.coordinates[0]],
        [...this.coordinates[1]],
        [...this.coordinates[2]],
      ],
    });
  }

  toJSON() {
    return {
      type: 'NonUniformGrid',
      dimension: this.dimension,
      spacing: this.spacing,
      dataLocation: this.dataLocation,
      origin: this.origin,
      resolution: this.resolution,
      coordinates: this.coordinates,
    };
  }
}
