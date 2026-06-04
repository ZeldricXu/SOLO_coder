import { IGrid, GridDimension, DataLocation, UniformGridParams, NonUniformGridParams } from '@physics-sim/shared';
export declare class UniformGrid implements IGrid {
    readonly dimension: GridDimension;
    readonly spacing: 'uniform';
    readonly dataLocation: DataLocation;
    readonly origin: [number, number, number];
    readonly dimensions: [number, number, number];
    readonly resolution: [number, number, number];
    readonly cellSize: [number, number, number];
    readonly totalNodes: number;
    constructor(params: Omit<UniformGridParams, 'spacing'>);
    static create2D(width: number, height: number, nx: number, ny: number, origin?: [number, number, number], dataLocation?: DataLocation): UniformGrid;
    static create3D(width: number, height: number, depth: number, nx: number, ny: number, nz: number, origin?: [number, number, number], dataLocation?: DataLocation): UniformGrid;
    getIndex(i: number, j?: number, k?: number): number;
    getIndices(index: number): [number, number, number];
    getCoordinate(i: number, j?: number, k?: number): [number, number, number];
    getCellCenter(i: number, j?: number, k?: number): [number, number, number];
    findIndex(x: number, y?: number, z?: number): [number, number, number];
    isInside(x: number, y?: number, z?: number): boolean;
    clone(): UniformGrid;
    toJSON(): {
        type: string;
        dimension: GridDimension;
        spacing: "uniform";
        dataLocation: DataLocation;
        origin: [number, number, number];
        resolution: [number, number, number];
        cellSize: [number, number, number];
        dimensions: [number, number, number];
    };
}
export declare class NonUniformGrid implements IGrid {
    readonly dimension: GridDimension;
    readonly spacing: 'non-uniform';
    readonly dataLocation: DataLocation;
    readonly origin: [number, number, number];
    readonly dimensions: [number, number, number];
    readonly resolution: [number, number, number];
    readonly cellSize: [number, number, number];
    readonly totalNodes: number;
    readonly coordinates: [number[], number[], number[]];
    constructor(params: Omit<NonUniformGridParams, 'spacing'>);
    getIndex(i: number, j?: number, k?: number): number;
    getIndices(index: number): [number, number, number];
    getCoordinate(i: number, j?: number, k?: number): [number, number, number];
    getCellCenter(i: number, j?: number, k?: number): [number, number, number];
    findIndex(x: number, y?: number, z?: number): [number, number, number];
    private binarySearch;
    isInside(x: number, y?: number, z?: number): boolean;
    clone(): NonUniformGrid;
    toJSON(): {
        type: string;
        dimension: GridDimension;
        spacing: "non-uniform";
        dataLocation: DataLocation;
        origin: [number, number, number];
        resolution: [number, number, number];
        coordinates: [number[], number[], number[]];
    };
}
//# sourceMappingURL=grid.d.ts.map