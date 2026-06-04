import { IGrid, TypedArray, ScalarFieldData, VectorFieldData, TensorFieldData } from '@physics-sim/shared';
declare abstract class BaseField<T extends TypedArray> {
    readonly id: string;
    readonly name: string;
    readonly type: 'scalar' | 'vector' | 'tensor';
    readonly components: number;
    readonly grid: IGrid;
    readonly data: T;
    readonly time: number;
    readonly componentNames: string[];
    constructor(type: 'scalar' | 'vector' | 'tensor', components: number, grid: IGrid, data: T, name?: string, componentNames?: string[], time?: number);
    private defaultComponentNames;
    get(i: number, j?: number, k?: number): number | number[];
    set(value: number | number[], i: number, j?: number, k?: number): void;
    add(value: number | number[], i: number, j?: number, k?: number): void;
    multiply(value: number, i?: number, j?: number, k?: number): void;
    interpolate(x: number, y?: number, z?: number, method?: 'nearest' | 'linear'): number | number[];
    forEach(callback: (value: number | number[], index: number, i: number, j: number, k: number) => void): void;
    toJSON(): {
        id: string;
        name: string;
        type: "scalar" | "vector" | "tensor";
        components: number;
        componentNames: string[];
        time: number;
        grid: any;
        data: number[];
    };
}
export declare class ScalarField extends BaseField<Float32Array> implements ScalarFieldData {
    constructor(grid: IGrid, data?: Float32Array, name?: string, time?: number);
    getScalar(i: number, j?: number, k?: number): number;
    setScalar(value: number, i: number, j?: number, k?: number): void;
    interpolateScalar(x: number, y?: number, z?: number, method?: 'nearest' | 'linear'): number;
    clone(): ScalarField;
}
export declare class VectorField extends BaseField<Float32Array> implements VectorFieldData {
    constructor(grid: IGrid, components?: number, data?: Float32Array, name?: string, time?: number);
    getVector(i: number, j?: number, k?: number): number[];
    setVector(value: number[], i: number, j?: number, k?: number): void;
    interpolateVector(x: number, y?: number, z?: number, method?: 'nearest' | 'linear'): number[];
    clone(): VectorField;
    magnitude(field?: ScalarField): ScalarField;
}
export declare class TensorField extends BaseField<Float32Array> implements TensorFieldData {
    constructor(grid: IGrid, components?: number, data?: Float32Array, name?: string, time?: number);
    clone(): TensorField;
}
export declare function addFields<T extends ScalarField | VectorField | TensorField>(a: T, b: T, result?: T): T;
export declare function subtractFields<T extends ScalarField | VectorField | TensorField>(a: T, b: T, result?: T): T;
export declare function multiplyFieldByScalar<T extends ScalarField | VectorField | TensorField>(field: T, scalar: number, result?: T): T;
export declare function gradient(field: ScalarField, result?: VectorField): VectorField;
export declare function divergence(field: VectorField, result?: ScalarField): ScalarField;
export declare function curl(field: VectorField, result?: VectorField): VectorField;
export {};
//# sourceMappingURL=field.d.ts.map