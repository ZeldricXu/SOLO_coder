import { IGrid, TypedArray, ScalarFieldData, VectorFieldData, TensorFieldData } from '@physics-sim/shared';
import { generateId } from '@physics-sim/shared';
import { UniformGrid } from './grid';

abstract class BaseField<T extends TypedArray> {
  readonly id: string;
  readonly name: string;
  readonly type: 'scalar' | 'vector' | 'tensor';
  readonly components: number;
  readonly grid: IGrid;
  readonly data: T;
  readonly time: number;
  readonly componentNames: string[];

  constructor(
    type: 'scalar' | 'vector' | 'tensor',
    components: number,
    grid: IGrid,
    data: T,
    name?: string,
    componentNames?: string[],
    time: number = 0
  ) {
    this.id = generateId();
    this.name = name || '';
    this.type = type;
    this.components = components;
    this.grid = grid;
    this.data = data;
    this.time = time;
    this.componentNames = componentNames || this.defaultComponentNames(type, components);
  }

  private defaultComponentNames(type: string, components: number): string[] {
    if (type === 'scalar') return ['value'];
    if (type === 'vector') return ['x', 'y', 'z'].slice(0, components);
    if (type === 'tensor') return ['xx', 'xy', 'xz', 'yx', 'yy', 'yz', 'zx', 'zy', 'zz'].slice(0, components);
    return new Array(components).fill('').map((_, i) => `c${i}`);
  }

  get(i: number, j: number = 0, k: number = 0): number | number[] {
    const idx = this.grid.getIndex(i, j, k);
    if (this.components === 1) {
      return this.data[idx];
    }
    const result: number[] = new Array(this.components);
    for (let c = 0; c < this.components; c++) {
      result[c] = this.data[idx * this.components + c];
    }
    return result;
  }

  set(value: number | number[], i: number, j: number = 0, k: number = 0): void {
    const idx = this.grid.getIndex(i, j, k);
    if (this.components === 1 && typeof value === 'number') {
      this.data[idx] = value;
    } else if (Array.isArray(value)) {
      for (let c = 0; c < this.components && c < value.length; c++) {
        this.data[idx * this.components + c] = value[c];
      }
    } else if (this.components === 1) {
      this.data[idx] = value as number;
    }
  }

  add(value: number | number[], i: number, j: number = 0, k: number = 0): void {
    const idx = this.grid.getIndex(i, j, k);
    if (this.components === 1 && typeof value === 'number') {
      this.data[idx] += value;
    } else if (Array.isArray(value)) {
      for (let c = 0; c < this.components && c < value.length; c++) {
        this.data[idx * this.components + c] += value[c];
      }
    } else if (this.components === 1) {
      this.data[idx] += value as number;
    }
  }

  multiply(value: number, i?: number, j?: number, k?: number): void {
    if (i !== undefined && j !== undefined && k !== undefined) {
      const idx = this.grid.getIndex(i, j, k);
      if (this.components === 1) {
        this.data[idx] *= value;
      } else {
        for (let c = 0; c < this.components; c++) {
          this.data[idx * this.components + c] *= value;
        }
      }
    } else {
      for (let p = 0; p < this.data.length; p++) {
        this.data[p] *= value;
      }
    }
  }

  interpolate(x: number, y: number = 0, z: number = 0, method: 'nearest' | 'linear' = 'linear'): number | number[] {
    if (method === 'nearest') {
      const [i, j, k] = this.grid.findIndex(x, y, z);
      const [nx, ny, nz] = this.grid.resolution;
      const ci = Math.max(0, Math.min(nx - 1, Math.round(i)));
      const cj = Math.max(0, Math.min(ny - 1, Math.round(j)));
      const ck = Math.max(0, Math.min(nz - 1, Math.round(k)));
      return this.get(ci, cj, ck);
    }

    const [nx, ny, nz] = this.grid.resolution;
    const is2D = this.grid.dimension === 2 || nz === 1;
    const offset = this.grid.dataLocation === 'cell-center' ? 0.5 : 0;
    const lx = (x - this.grid.origin[0]) / this.grid.cellSize[0] - offset;
    const ly = (y - this.grid.origin[1]) / this.grid.cellSize[1] - offset;
    const lz = (z - this.grid.origin[2]) / this.grid.cellSize[2] - offset;

    const ix = Math.max(0, Math.min(nx - 2, Math.floor(lx)));
    const iy = Math.max(0, Math.min(ny - 2, Math.floor(ly)));
    const iz = is2D ? 0 : Math.max(0, Math.min(nz - 2, Math.floor(lz)));

    const fx = Math.max(0, Math.min(1, lx - ix));
    const fy = Math.max(0, Math.min(1, ly - iy));
    const fz = is2D ? 0 : Math.max(0, Math.min(1, lz - iz));

    if (this.components === 1) {
      const v000 = (this.data as Float32Array)[this.grid.getIndex(ix, iy, iz)];
      const v100 = (this.data as Float32Array)[this.grid.getIndex(ix + 1, iy, iz)];
      const v010 = (this.data as Float32Array)[this.grid.getIndex(ix, iy + 1, iz)];
      const v110 = (this.data as Float32Array)[this.grid.getIndex(ix + 1, iy + 1, iz)];

      const x0 = v000 * (1 - fx) + v100 * fx;
      const x1 = v010 * (1 - fx) + v110 * fx;
      const y0 = x0 * (1 - fy) + x1 * fy;

      if (is2D) {
        return y0;
      }

      const v001 = (this.data as Float32Array)[this.grid.getIndex(ix, iy, iz + 1)];
      const v101 = (this.data as Float32Array)[this.grid.getIndex(ix + 1, iy, iz + 1)];
      const v011 = (this.data as Float32Array)[this.grid.getIndex(ix, iy + 1, iz + 1)];
      const v111 = (this.data as Float32Array)[this.grid.getIndex(ix + 1, iy + 1, iz + 1)];

      const x2 = v001 * (1 - fx) + v101 * fx;
      const x3 = v011 * (1 - fx) + v111 * fx;
      const y1 = x2 * (1 - fy) + x3 * fy;
      return y0 * (1 - fz) + y1 * fz;
    }

    const result: number[] = new Array(this.components);
    for (let c = 0; c < this.components; c++) {
      const stride = this.components;
      const base000 = this.grid.getIndex(ix, iy, iz) * stride + c;
      const base100 = this.grid.getIndex(ix + 1, iy, iz) * stride + c;
      const base010 = this.grid.getIndex(ix, iy + 1, iz) * stride + c;
      const base110 = this.grid.getIndex(ix + 1, iy + 1, iz) * stride + c;

      const v000 = this.data[base000];
      const v100 = this.data[base100];
      const v010 = this.data[base010];
      const v110 = this.data[base110];

      const x0 = v000 * (1 - fx) + v100 * fx;
      const x1 = v010 * (1 - fx) + v110 * fx;
      const y0 = x0 * (1 - fy) + x1 * fy;

      if (is2D) {
        result[c] = y0;
        continue;
      }

      const base001 = this.grid.getIndex(ix, iy, iz + 1) * stride + c;
      const base101 = this.grid.getIndex(ix + 1, iy, iz + 1) * stride + c;
      const base011 = this.grid.getIndex(ix, iy + 1, iz + 1) * stride + c;
      const base111 = this.grid.getIndex(ix + 1, iy + 1, iz + 1) * stride + c;

      const v001 = this.data[base001];
      const v101 = this.data[base101];
      const v011 = this.data[base011];
      const v111 = this.data[base111];

      const x2 = v001 * (1 - fx) + v101 * fx;
      const x3 = v011 * (1 - fx) + v111 * fx;
      const y1 = x2 * (1 - fy) + x3 * fy;
      result[c] = y0 * (1 - fz) + y1 * fz;
    }
    return result;
  }

  forEach(callback: (value: number | number[], index: number, i: number, j: number, k: number) => void): void {
    const [nx, ny, nz] = this.grid.resolution;
    for (let k = 0; k < nz; k++) {
      for (let j = 0; j < ny; j++) {
        for (let i = 0; i < nx; i++) {
          const idx = this.grid.getIndex(i, j, k);
          const val = this.get(i, j, k);
          callback(val, idx, i, j, k);
        }
      }
    }
  }

  toJSON() {
    return {
      id: this.id,
      name: this.name,
      type: this.type,
      components: this.components,
      componentNames: this.componentNames,
      time: this.time,
      grid: this.grid.toJSON(),
      data: Array.from(this.data),
    };
  }
}

export class ScalarField extends BaseField<Float32Array> implements ScalarFieldData {
  constructor(grid: IGrid, data?: Float32Array, name?: string, time: number = 0) {
    super(
      'scalar',
      1,
      grid,
      data || new Float32Array(grid.totalNodes),
      name,
      undefined,
      time
    );
  }

  getScalar(i: number, j?: number, k?: number): number {
    return super.get(i, j, k) as number;
  }

  setScalar(value: number, i: number, j?: number, k?: number): void {
    super.set(value, i, j, k);
  }

  interpolateScalar(x: number, y?: number, z?: number, method?: 'nearest' | 'linear'): number {
    return super.interpolate(x, y, z, method) as number;
  }

  clone(): ScalarField {
    return new ScalarField(
      this.grid.clone(),
      new Float32Array(this.data),
      this.name,
      this.time
    );
  }
}

export class VectorField extends BaseField<Float32Array> implements VectorFieldData {
  constructor(grid: IGrid, components: number = 3, data?: Float32Array, name?: string, time: number = 0) {
    super(
      'vector',
      components,
      grid,
      data || new Float32Array(grid.totalNodes * components),
      name,
      undefined,
      time
    );
  }

  getVector(i: number, j?: number, k?: number): number[] {
    return super.get(i, j, k) as number[];
  }

  setVector(value: number[], i: number, j?: number, k?: number): void {
    super.set(value, i, j, k);
  }

  interpolateVector(x: number, y?: number, z?: number, method?: 'nearest' | 'linear'): number[] {
    return super.interpolate(x, y, z, method) as number[];
  }

  clone(): VectorField {
    return new VectorField(
      this.grid.clone(),
      this.components,
      new Float32Array(this.data),
      this.name,
      this.time
    );
  }

  magnitude(field: ScalarField = new ScalarField(this.grid)): ScalarField {
    const [nx, ny, nz] = this.grid.resolution;
    for (let k = 0; k < nz; k++) {
      for (let j = 0; j < ny; j++) {
        for (let i = 0; i < nx; i++) {
          const [vx, vy, vz] = this.getVector(i, j, k);
          const mag = Math.sqrt(vx * vx + vy * vy + vz * vz);
          field.setScalar(mag, i, j, k);
        }
      }
    }
    return field;
  }
}

export class TensorField extends BaseField<Float32Array> implements TensorFieldData {
  constructor(grid: IGrid, components: number = 9, data?: Float32Array, name?: string, time: number = 0) {
    super(
      'tensor',
      components,
      grid,
      data || new Float32Array(grid.totalNodes * components),
      name,
      undefined,
      time
    );
  }

  clone(): TensorField {
    return new TensorField(
      this.grid.clone(),
      this.components,
      new Float32Array(this.data),
      this.name,
      this.time
    );
  }
}

export function addFields<T extends ScalarField | VectorField | TensorField>(
  a: T,
  b: T,
  result?: T
): T {
  if (a.grid.totalNodes !== b.grid.totalNodes || a.components !== b.components) {
    throw new Error('Fields must have compatible grids and components');
  }
  const out = result || a.clone() as T;
  for (let i = 0; i < a.data.length; i++) {
    (out.data as Float32Array)[i] = a.data[i] + b.data[i];
  }
  return out;
}

export function subtractFields<T extends ScalarField | VectorField | TensorField>(
  a: T,
  b: T,
  result?: T
): T {
  if (a.grid.totalNodes !== b.grid.totalNodes || a.components !== b.components) {
    throw new Error('Fields must have compatible grids and components');
  }
  const out = result || a.clone() as T;
  for (let i = 0; i < a.data.length; i++) {
    (out.data as Float32Array)[i] = a.data[i] - b.data[i];
  }
  return out;
}

export function multiplyFieldByScalar<T extends ScalarField | VectorField | TensorField>(
  field: T,
  scalar: number,
  result?: T
): T {
  const out = result || field.clone() as T;
  for (let i = 0; i < field.data.length; i++) {
    (out.data as Float32Array)[i] = field.data[i] * scalar;
  }
  return out;
}

export function gradient(field: ScalarField, result: VectorField = new VectorField(field.grid)): VectorField {
  const [nx, ny, nz] = field.grid.resolution;
  const dx = field.grid.cellSize[0];
  const dy = field.grid.cellSize[1];
  const dz = field.grid.cellSize[2];

  for (let k = 0; k < nz; k++) {
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const ip = Math.min(nx - 1, i + 1);
        const im = Math.max(0, i - 1);
        const jp = Math.min(ny - 1, j + 1);
        const jm = Math.max(0, j - 1);
        const kp = Math.min(nz - 1, k + 1);
        const km = Math.max(0, k - 1);

        const dIdx = (field.getScalar(ip, j, k) - field.getScalar(im, j, k)) / (2 * dx);
        const dIdy = (field.getScalar(i, jp, k) - field.getScalar(i, jm, k)) / (2 * dy);
        const dIdz = (field.getScalar(i, j, kp) - field.getScalar(i, j, km)) / (2 * dz);

        result.setVector([dIdx, dIdy, dIdz], i, j, k);
      }
    }
  }
  return result;
}

export function divergence(field: VectorField, result: ScalarField = new ScalarField(field.grid)): ScalarField {
  const [nx, ny, nz] = field.grid.resolution;
  const dx = field.grid.cellSize[0];
  const dy = field.grid.cellSize[1];
  const dz = field.grid.cellSize[2];

  for (let k = 0; k < nz; k++) {
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const ip = Math.min(nx - 1, i + 1);
        const im = Math.max(0, i - 1);
        const jp = Math.min(ny - 1, j + 1);
        const jm = Math.max(0, j - 1);
        const kp = Math.min(nz - 1, k + 1);
        const km = Math.max(0, k - 1);

        const vx_p = field.getVector(ip, j, k)[0];
        const vx_m = field.getVector(im, j, k)[0];
        const vy_p = field.getVector(i, jp, k)[1];
        const vy_m = field.getVector(i, jm, k)[1];
        const vz_p = field.getVector(i, j, kp)[2];
        const vz_m = field.getVector(i, j, km)[2];

        const div = (vx_p - vx_m) / (2 * dx) + (vy_p - vy_m) / (2 * dy) + (vz_p - vz_m) / (2 * dz);
        result.setScalar(div, i, j, k);
      }
    }
  }
  return result;
}

export function curl(field: VectorField, result: VectorField = new VectorField(field.grid)): VectorField {
  const [nx, ny, nz] = field.grid.resolution;
  const dx = field.grid.cellSize[0];
  const dy = field.grid.cellSize[1];
  const dz = field.grid.cellSize[2];

  for (let k = 0; k < nz; k++) {
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const ip = Math.min(nx - 1, i + 1);
        const im = Math.max(0, i - 1);
        const jp = Math.min(ny - 1, j + 1);
        const jm = Math.max(0, j - 1);
        const kp = Math.min(nz - 1, k + 1);
        const km = Math.max(0, k - 1);

        const vz_py = field.getVector(i, jp, k)[2];
        const vz_my = field.getVector(i, jm, k)[2];
        const vy_pz = field.getVector(i, j, kp)[1];
        const vy_mz = field.getVector(i, j, km)[1];
        const vx_pz = field.getVector(i, j, kp)[0];
        const vx_mz = field.getVector(i, j, km)[0];
        const vz_px = field.getVector(ip, j, k)[2];
        const vz_mx = field.getVector(im, j, k)[2];
        const vy_px = field.getVector(ip, j, k)[1];
        const vy_mx = field.getVector(im, j, k)[1];
        const vx_py = field.getVector(i, jp, k)[0];
        const vx_my = field.getVector(i, jm, k)[0];

        const curl_x = (vz_py - vz_my) / (2 * dy) - (vy_pz - vy_mz) / (2 * dz);
        const curl_y = (vx_pz - vx_mz) / (2 * dz) - (vz_px - vz_mx) / (2 * dx);
        const curl_z = (vy_px - vy_mx) / (2 * dx) - (vx_py - vx_my) / (2 * dy);

        result.setVector([curl_x, curl_y, curl_z], i, j, k);
      }
    }
  }
  return result;
}
