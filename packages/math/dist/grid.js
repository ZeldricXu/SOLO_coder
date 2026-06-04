export class UniformGrid {
    constructor(params) {
        this.spacing = 'uniform';
        this.dimension = params.dimension;
        this.dataLocation = params.dataLocation;
        this.origin = [...params.origin];
        this.resolution = [...params.resolution];
        this.cellSize = [...params.cellSize];
        this.dimensions = [
            this.resolution[0] * this.cellSize[0],
            this.resolution[1] * this.cellSize[1],
            this.resolution[2] * this.cellSize[2],
        ];
        this.totalNodes = this.resolution[0] * this.resolution[1] * this.resolution[2];
    }
    static create2D(width, height, nx, ny, origin = [0, 0, 0], dataLocation = 'node') {
        return new UniformGrid({
            dimension: 2,
            dataLocation,
            origin,
            resolution: [nx, ny, 1],
            cellSize: [width / (dataLocation === 'node' ? nx - 1 : nx), height / (dataLocation === 'node' ? ny - 1 : ny), 1],
        });
    }
    static create3D(width, height, depth, nx, ny, nz, origin = [0, 0, 0], dataLocation = 'node') {
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
    getIndex(i, j = 0, k = 0) {
        const [nx, ny] = this.resolution;
        return i + j * nx + k * nx * ny;
    }
    getIndices(index) {
        const [nx, ny] = this.resolution;
        const k = Math.floor(index / (nx * ny));
        const rem = index % (nx * ny);
        const j = Math.floor(rem / nx);
        const i = rem % nx;
        return [i, j, k];
    }
    getCoordinate(i, j = 0, k = 0) {
        const offset = this.dataLocation === 'cell-center' ? 0.5 : 0;
        return [
            this.origin[0] + (i + offset) * this.cellSize[0],
            this.origin[1] + (j + offset) * this.cellSize[1],
            this.origin[2] + (k + offset) * this.cellSize[2],
        ];
    }
    getCellCenter(i, j = 0, k = 0) {
        return [
            this.origin[0] + (i + 0.5) * this.cellSize[0],
            this.origin[1] + (j + 0.5) * this.cellSize[1],
            this.origin[2] + (k + 0.5) * this.cellSize[2],
        ];
    }
    findIndex(x, y = 0, z = 0) {
        const offset = this.dataLocation === 'cell-center' ? 0.5 : 0;
        return [
            Math.floor((x - this.origin[0]) / this.cellSize[0] - offset + 0.5),
            Math.floor((y - this.origin[1]) / this.cellSize[1] - offset + 0.5),
            Math.floor((z - this.origin[2]) / this.cellSize[2] - offset + 0.5),
        ];
    }
    isInside(x, y = 0, z = 0) {
        const [nx, ny, nz] = this.resolution;
        const [i, j, k] = this.findIndex(x, y, z);
        return i >= 0 && i < nx && j >= 0 && j < ny && k >= 0 && k < nz;
    }
    clone() {
        return new UniformGrid({
            dimension: this.dimension,
            dataLocation: this.dataLocation,
            origin: [...this.origin],
            resolution: [...this.resolution],
            cellSize: [...this.cellSize],
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
export class NonUniformGrid {
    constructor(params) {
        this.spacing = 'non-uniform';
        this.dimension = params.dimension;
        this.dataLocation = params.dataLocation;
        this.origin = [...params.origin];
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
    getIndex(i, j = 0, k = 0) {
        const [nx, ny] = this.resolution;
        return i + j * nx + k * nx * ny;
    }
    getIndices(index) {
        const [nx, ny] = this.resolution;
        const k = Math.floor(index / (nx * ny));
        const rem = index % (nx * ny);
        const j = Math.floor(rem / nx);
        const i = rem % nx;
        return [i, j, k];
    }
    getCoordinate(i, j = 0, k = 0) {
        return [
            this.coordinates[0][i] + this.origin[0],
            this.coordinates[1][j] + this.origin[1],
            this.coordinates[2][k] + this.origin[2],
        ];
    }
    getCellCenter(i, j = 0, k = 0) {
        const [nx, ny, nz] = this.resolution;
        const cx = i < nx - 1 ? (this.coordinates[0][i] + this.coordinates[0][i + 1]) / 2 : this.coordinates[0][i];
        const cy = j < ny - 1 ? (this.coordinates[1][j] + this.coordinates[1][j + 1]) / 2 : this.coordinates[1][j];
        const cz = k < nz - 1 ? (this.coordinates[2][k] + this.coordinates[2][k + 1]) / 2 : this.coordinates[2][k];
        return [cx + this.origin[0], cy + this.origin[1], cz + this.origin[2]];
    }
    findIndex(x, y = 0, z = 0) {
        const localX = x - this.origin[0];
        const localY = y - this.origin[1];
        const localZ = z - this.origin[2];
        return [
            this.binarySearch(localX, this.coordinates[0]),
            this.binarySearch(localY, this.coordinates[1]),
            this.binarySearch(localZ, this.coordinates[2]),
        ];
    }
    binarySearch(value, arr) {
        if (value <= arr[0])
            return 0;
        if (value >= arr[arr.length - 1])
            return arr.length - 1;
        let lo = 0, hi = arr.length - 1;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if (arr[mid] < value)
                lo = mid + 1;
            else
                hi = mid;
        }
        return lo > 0 ? (value - arr[lo - 1] < arr[lo] - value ? lo - 1 : lo) : 0;
    }
    isInside(x, y = 0, z = 0) {
        const localX = x - this.origin[0];
        const localY = y - this.origin[1];
        const localZ = z - this.origin[2];
        const [nx, ny, nz] = this.resolution;
        return localX >= this.coordinates[0][0] && localX <= this.coordinates[0][nx - 1] &&
            localY >= this.coordinates[1][0] && localY <= this.coordinates[1][ny - 1] &&
            localZ >= this.coordinates[2][0] && localZ <= this.coordinates[2][nz - 1];
    }
    clone() {
        return new NonUniformGrid({
            dimension: this.dimension,
            dataLocation: this.dataLocation,
            origin: [...this.origin],
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
//# sourceMappingURL=grid.js.map