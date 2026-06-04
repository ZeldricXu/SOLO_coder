export type GridDimension = 1 | 2 | 3;

export type GridSpacing = 'uniform' | 'non-uniform';

export type DataLocation = 'node' | 'cell-center';

export type TypedArray = Float32Array | Float64Array | Uint8Array | Int32Array;

export interface GridCoordinate {
  x: number[];
  y: number[];
  z: number[];
}

export interface UniformGridParams {
  origin: [number, number, number];
  cellSize: [number, number, number];
  resolution: [number, number, number];
  spacing: 'uniform';
  dataLocation: DataLocation;
  dimension: GridDimension;
}

export interface NonUniformGridParams {
  coordinates: [number[], number[], number[]];
  origin: [number, number, number];
  spacing: 'non-uniform';
  dataLocation: DataLocation;
  dimension: GridDimension;
}

export type GridParams = UniformGridParams | NonUniformGridParams;

export interface IGrid {
  readonly dimension: GridDimension;
  readonly spacing: GridSpacing;
  readonly dataLocation: DataLocation;
  readonly origin: [number, number, number];
  readonly dimensions: [number, number, number];
  readonly resolution: [number, number, number];
  readonly cellSize: [number, number, number];
  readonly totalNodes: number;

  getIndex(i: number, j?: number, k?: number): number;
  getIndices(index: number): [number, number, number];
  getCoordinate(i: number, j?: number, k?: number): [number, number, number];
  getCellCenter(i: number, j?: number, k?: number): [number, number, number];
  findIndex(x: number, y?: number, z?: number): [number, number, number];
  isInside(x: number, y?: number, z?: number): boolean;
  clone(): IGrid;
  toJSON(): any;
}

export interface IField<T extends TypedArray> {
  readonly id: string;
  readonly name: string;
  readonly type: 'scalar' | 'vector' | 'tensor';
  readonly components: number;
  readonly grid: IGrid;
  readonly data: T;
  readonly time: number;
  readonly componentNames: string[];

  get(i: number, j?: number, k?: number): number | number[];
  set(value: number | number[], i: number, j?: number, k?: number): void;
  add(value: number | number[], i: number, j?: number, k?: number): void;
  multiply(value: number, i?: number, j?: number, k?: number): void;
  interpolate(x: number, y?: number, z?: number, method?: 'nearest' | 'linear'): number | number[];
  clone(): IField<T>;
  forEach(callback: (value: number | number[], index: number, i: number, j: number, k: number) => void): void;
  toJSON(): any;
}

export type ScalarFieldData = IField<Float32Array>;
export type VectorFieldData = IField<Float32Array>;
export type TensorFieldData = IField<Float32Array>;
