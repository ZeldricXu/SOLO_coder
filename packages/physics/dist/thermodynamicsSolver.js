import { vec3 } from '@physics-sim/shared';
import { MATERIALS } from '@physics-sim/shared';
import { generateId } from '@physics-sim/shared';
import { Vec3Ops } from '@physics-sim/math';
import { UniformGrid, ScalarField, VectorField } from '@physics-sim/math';
export const DEFAULT_THERMAL_CONFIG = {
    dimensions: vec3(10, 10, 10),
    resolution: vec3(32, 32, 32),
    origin: vec3(-5, -5, -5),
    dt: 0.1,
    maxIterations: 1000,
    tolerance: 1e-6,
    use3D: true,
};
export class ThermodynamicsSolver {
    constructor(config = {}) {
        this.config = { ...DEFAULT_THERMAL_CONFIG, ...config };
        this.uniformGrid = this.createUniformGrid();
        this.grid = this.createLegacyGrid();
        this.heatSources = [];
        this.thermalBodies = [];
        this.boundaryConditions = [];
        this.time = 0;
        this.temperatureField = new ScalarField(this.uniformGrid);
        this.conductivityField = new ScalarField(this.uniformGrid);
        this.densityField = new ScalarField(this.uniformGrid);
        this.specificHeatField = new ScalarField(this.uniformGrid);
        this.initializeMaterialProperties();
    }
    createUniformGrid() {
        const nx = Math.floor(this.config.resolution.x);
        const ny = Math.floor(this.config.resolution.y);
        const nz = Math.floor(this.config.resolution.z);
        const origin = [this.config.origin.x, this.config.origin.y, this.config.origin.z];
        if (this.config.use3D) {
            return UniformGrid.create3D(this.config.dimensions.x, this.config.dimensions.y, this.config.dimensions.z, nx, ny, nz, origin, 'node');
        }
        else {
            return UniformGrid.create2D(this.config.dimensions.x, this.config.dimensions.y, nx, ny, origin, 'node');
        }
    }
    createLegacyGrid() {
        return {
            dimensions: { ...this.config.dimensions },
            resolution: { ...this.config.resolution },
            cellSize: {
                x: this.uniformGrid.cellSize[0],
                y: this.uniformGrid.cellSize[1],
                z: this.config.use3D ? this.uniformGrid.cellSize[2] : 0,
            },
            origin: { ...this.config.origin },
        };
    }
    getGridSize() {
        return this.uniformGrid.totalNodes;
    }
    initializeMaterialProperties() {
        const [nx, ny, nz] = this.uniformGrid.resolution;
        const defaultMaterial = MATERIALS.air || MATERIALS.aluminum;
        for (let k = 0; k < nz; k++) {
            for (let j = 0; j < ny; j++) {
                for (let i = 0; i < nx; i++) {
                    const worldPos = this.gridToWorld(i, j, k);
                    let material = defaultMaterial;
                    for (const body of this.thermalBodies) {
                        if (this.isPointInBox(worldPos, body.position, body.size)) {
                            material = MATERIALS[body.materialId] || defaultMaterial;
                            break;
                        }
                    }
                    this.conductivityField.setScalar(material.thermalConductivity, i, j, k);
                    this.densityField.setScalar(material.density, i, j, k);
                    this.specificHeatField.setScalar(material.specificHeat, i, j, k);
                }
            }
        }
    }
    isPointInBox(point, boxCenter, boxSize) {
        const halfSize = Vec3Ops.mul(boxSize, 0.5);
        return (point.x >= boxCenter.x - halfSize.x && point.x <= boxCenter.x + halfSize.x &&
            point.y >= boxCenter.y - halfSize.y && point.y <= boxCenter.y + halfSize.y &&
            (!this.config.use3D || (point.z >= boxCenter.z - halfSize.z && point.z <= boxCenter.z + halfSize.z)));
    }
    addHeatSource(position, power, radius = 0.5) {
        const source = { position: { ...position }, power, radius, id: generateId() };
        this.heatSources.push(source);
        return source;
    }
    removeHeatSource(id) {
        const index = this.heatSources.findIndex(s => s.id === id);
        if (index !== -1) {
            this.heatSources.splice(index, 1);
            return true;
        }
        return false;
    }
    addThermalBody(position, size, materialId, initialTemperature = 300) {
        const body = {
            position: { ...position },
            size: { ...size },
            materialId,
            initialTemperature,
            id: generateId(),
        };
        this.thermalBodies.push(body);
        this.initializeMaterialProperties();
        this.applyInitialTemperatures();
        return body;
    }
    removeThermalBody(id) {
        const index = this.thermalBodies.findIndex(b => b.id === id);
        if (index !== -1) {
            this.thermalBodies.splice(index, 1);
            this.initializeMaterialProperties();
            return true;
        }
        return false;
    }
    setBoundaryConditions(conditions) {
        this.boundaryConditions = [...conditions];
    }
    setInitialTemperature(temperature) {
        this.temperatureField.data.fill(temperature);
        this.applyInitialTemperatures();
    }
    applyInitialTemperatures() {
        const [nx, ny, nz] = this.uniformGrid.resolution;
        for (let k = 0; k < nz; k++) {
            for (let j = 0; j < ny; j++) {
                for (let i = 0; i < nx; i++) {
                    const worldPos = this.gridToWorld(i, j, k);
                    for (const body of this.thermalBodies) {
                        if (this.isPointInBox(worldPos, body.position, body.size)) {
                            this.temperatureField.setScalar(body.initialTemperature, i, j, k);
                            break;
                        }
                    }
                }
            }
        }
    }
    step(dt) {
        const startTime = performance.now();
        const actualDt = dt || this.config.dt;
        const [nx, ny, nz] = this.uniformGrid.resolution;
        this.applyBoundaryConditions(this.temperatureField.data, nx, ny, nz);
        const nextTemperature = new Float32Array(this.temperatureField.data);
        const heatSourceTerm = this.computeHeatSourceTerm(nx, ny, nz);
        const { iterations, residual } = this.solveCrankNicolson(this.temperatureField.data, nextTemperature, heatSourceTerm, actualDt, nx, ny, nz);
        this.applyBoundaryConditions(nextTemperature, nx, ny, nz);
        this.temperatureField = new ScalarField(this.uniformGrid, nextTemperature, 'temperature', this.time);
        this.time += actualDt;
        const temperatureField = {
            id: generateId(),
            type: 'thermal',
            grid: this.grid,
            data: new Float32Array(this.temperatureField.data),
            time: this.time,
        };
        const heatFlux = this.computeHeatFlux(nx, ny, nz);
        const endTime = performance.now();
        return {
            temperature: temperatureField,
            heatFlux,
            iterations,
            residual,
            solveTime: endTime - startTime,
        };
    }
    computeHeatSourceTerm(nx, ny, nz) {
        const sourceTerm = new Float32Array(nx * ny * nz);
        for (const source of this.heatSources) {
            for (let k = 0; k < nz; k++) {
                for (let j = 0; j < ny; j++) {
                    for (let i = 0; i < nx; i++) {
                        const idx = this.uniformGrid.getIndex(i, j, k);
                        const worldPos = this.gridToWorld(i, j, k);
                        const distance = Vec3Ops.length(Vec3Ops.sub(worldPos, source.position));
                        if (distance < source.radius) {
                            const cellVolume = this.grid.cellSize.x * this.grid.cellSize.y *
                                (this.config.use3D ? this.grid.cellSize.z : 1);
                            const gaussian = Math.exp(-(distance * distance) / (2 * source.radius * source.radius));
                            const densityVal = this.densityField.data[idx];
                            const specificHeatVal = this.specificHeatField.data[idx];
                            sourceTerm[idx] += (source.power * gaussian) / (cellVolume * densityVal * specificHeatVal);
                        }
                    }
                }
            }
        }
        return sourceTerm;
    }
    solveCrankNicolson(T_prev, T_next, source, dt, nx, ny, nz) {
        const dx = this.grid.cellSize.x;
        const dy = this.grid.cellSize.y;
        const dz = this.config.use3D ? this.grid.cellSize.z : 1;
        const dx2 = dx * dx;
        const dy2 = dy * dy;
        const dz2 = dz * dz;
        let residual = Infinity;
        let iterations = 0;
        while (residual > this.config.tolerance && iterations < this.config.maxIterations) {
            residual = 0;
            for (let i = 1; i < nx - 1; i++) {
                for (let j = 1; j < ny - 1; j++) {
                    for (let k = this.config.use3D ? 1 : 0; k < (this.config.use3D ? nz - 1 : 1); k++) {
                        const idx = this.uniformGrid.getIndex(i, j, k);
                        const idxIp = this.uniformGrid.getIndex(i + 1, j, k);
                        const idxIm = this.uniformGrid.getIndex(i - 1, j, k);
                        const idxJp = this.uniformGrid.getIndex(i, j + 1, k);
                        const idxJm = this.uniformGrid.getIndex(i, j - 1, k);
                        const idxKp = this.config.use3D ? this.uniformGrid.getIndex(i, j, k + 1) : idx;
                        const idxKm = this.config.use3D ? this.uniformGrid.getIndex(i, j, k - 1) : idx;
                        const alpha = this.conductivityField.data[idx] /
                            (this.densityField.data[idx] * this.specificHeatField.data[idx]);
                        const laplacian_prev = (T_prev[idxIp] - 2 * T_prev[idx] + T_prev[idxIm]) / dx2 +
                            (T_prev[idxJp] - 2 * T_prev[idx] + T_prev[idxJm]) / dy2 +
                            (this.config.use3D ? (T_prev[idxKp] - 2 * T_prev[idx] + T_prev[idxKm]) / dz2 : 0);
                        const laplacian_next = (T_next[idxIp] - 2 * T_next[idx] + T_next[idxIm]) / dx2 +
                            (T_next[idxJp] - 2 * T_next[idx] + T_next[idxJm]) / dy2 +
                            (this.config.use3D ? (T_next[idxKp] - 2 * T_next[idx] + T_next[idxKm]) / dz2 : 0);
                        const newValue = T_prev[idx] + (dt * alpha / 2) * (laplacian_prev + laplacian_next) + dt * source[idx];
                        const delta = newValue - T_next[idx];
                        T_next[idx] = newValue;
                        residual += delta * delta;
                    }
                }
            }
            residual = Math.sqrt(residual / (nx * ny * nz));
            iterations++;
        }
        return { iterations, residual };
    }
    applyBoundaryConditions(temperature, nx, ny, nz) {
        for (const condition of this.boundaryConditions) {
            if (condition.faceIndex === undefined)
                continue;
            const value = typeof condition.value === 'number' ? condition.value : 300;
            const face = condition.faceIndex;
            for (let i = 0; i < nx; i++) {
                for (let j = 0; j < ny; j++) {
                    for (let k = 0; k < nz; k++) {
                        let isBoundary = false;
                        switch (face) {
                            case 0:
                                isBoundary = i === 0;
                                break;
                            case 1:
                                isBoundary = i === nx - 1;
                                break;
                            case 2:
                                isBoundary = j === 0;
                                break;
                            case 3:
                                isBoundary = j === ny - 1;
                                break;
                            case 4:
                                isBoundary = this.config.use3D && k === 0;
                                break;
                            case 5:
                                isBoundary = this.config.use3D && k === nz - 1;
                                break;
                        }
                        if (isBoundary) {
                            const idx = this.uniformGrid.getIndex(i, j, k);
                            if (condition.type === 'dirichlet') {
                                temperature[idx] = value;
                            }
                            else if (condition.type === 'neumann') {
                                this.applyNeumannCondition(temperature, i, j, k, face, value, nx, ny, nz);
                            }
                        }
                    }
                }
            }
        }
    }
    applyNeumannCondition(temperature, i, j, k, face, flux, nx, ny, nz) {
        const dx = this.grid.cellSize.x;
        const dy = this.grid.cellSize.y;
        const dz = this.config.use3D ? this.grid.cellSize.z : 1;
        const idx = this.uniformGrid.getIndex(i, j, k);
        const k_conductivity = this.conductivityField.data[idx];
        let neighborIdx;
        let spacing;
        switch (face) {
            case 0:
                neighborIdx = this.uniformGrid.getIndex(1, j, k);
                spacing = dx;
                break;
            case 1:
                neighborIdx = this.uniformGrid.getIndex(nx - 2, j, k);
                spacing = dx;
                break;
            case 2:
                neighborIdx = this.uniformGrid.getIndex(i, 1, k);
                spacing = dy;
                break;
            case 3:
                neighborIdx = this.uniformGrid.getIndex(i, ny - 2, k);
                spacing = dy;
                break;
            case 4:
                neighborIdx = this.uniformGrid.getIndex(i, j, 1);
                spacing = dz;
                break;
            case 5:
                neighborIdx = this.uniformGrid.getIndex(i, j, nz - 2);
                spacing = dz;
                break;
            default:
                return;
        }
        temperature[idx] = temperature[neighborIdx] + (flux / k_conductivity) * spacing;
    }
    computeHeatFlux(nx, ny, nz) {
        const components = this.config.use3D ? 3 : 2;
        const result = new VectorField(this.uniformGrid, components, undefined, 'heatFlux', this.time);
        const dx = this.grid.cellSize.x;
        const dy = this.grid.cellSize.y;
        const dz = this.config.use3D ? this.grid.cellSize.z : 1;
        for (let k = 0; k < nz; k++) {
            for (let j = 0; j < ny; j++) {
                for (let i = 0; i < nx; i++) {
                    const idx = this.uniformGrid.getIndex(i, j, k);
                    const iPlus = Math.min(i + 1, nx - 1);
                    const iMinus = Math.max(i - 1, 0);
                    const jPlus = Math.min(j + 1, ny - 1);
                    const jMinus = Math.max(j - 1, 0);
                    const kPlus = Math.min(k + 1, nz - 1);
                    const kMinus = Math.max(k - 1, 0);
                    const idxIp = this.uniformGrid.getIndex(iPlus, j, k);
                    const idxIm = this.uniformGrid.getIndex(iMinus, j, k);
                    const idxJp = this.uniformGrid.getIndex(i, jPlus, k);
                    const idxJm = this.uniformGrid.getIndex(i, jMinus, k);
                    const idxKp = this.uniformGrid.getIndex(i, j, kPlus);
                    const idxKm = this.uniformGrid.getIndex(i, j, kMinus);
                    const k_cond = this.conductivityField.data[idx];
                    const T = this.temperatureField.data;
                    const fx = -k_cond * (T[idxIp] - T[idxIm]) / (2 * dx);
                    const fy = -k_cond * (T[idxJp] - T[idxJm]) / (2 * dy);
                    const fz = this.config.use3D ? -k_cond * (T[idxKp] - T[idxKm]) / (2 * dz) : 0;
                    result.setVector([fx, fy, fz], i, j, k);
                }
            }
        }
        return result;
    }
    getTemperatureAtPosition(position) {
        if (!this.uniformGrid.isInside(position.x, position.y, position.z)) {
            return 300;
        }
        return this.temperatureField.interpolateScalar(position.x, position.y, position.z);
    }
    worldToGrid(worldPos) {
        return vec3((worldPos.x - this.grid.origin.x) / this.grid.cellSize.x, (worldPos.y - this.grid.origin.y) / this.grid.cellSize.y, this.config.use3D ? (worldPos.z - this.grid.origin.z) / this.grid.cellSize.z : 0);
    }
    gridToWorld(ix, iy, iz) {
        const coord = this.uniformGrid.getCoordinate(ix, iy, iz);
        return vec3(coord[0], coord[1], coord[2]);
    }
    getTemperatureField(time = 0) {
        return {
            id: generateId(),
            type: 'thermal',
            grid: this.grid,
            data: new Float32Array(this.temperatureField.data),
            time: this.time,
        };
    }
    getTemperatureFieldData() {
        return this.temperatureField.clone();
    }
    getUniformGrid() {
        return this.uniformGrid.clone();
    }
    getGrid() {
        return { ...this.grid };
    }
    getTime() {
        return this.time;
    }
    setConfig(config) {
        this.config = { ...this.config, ...config };
        this.uniformGrid = this.createUniformGrid();
        this.grid = this.createLegacyGrid();
        this.temperatureField = new ScalarField(this.uniformGrid);
        this.conductivityField = new ScalarField(this.uniformGrid);
        this.densityField = new ScalarField(this.uniformGrid);
        this.specificHeatField = new ScalarField(this.uniformGrid);
        this.initializeMaterialProperties();
        this.time = 0;
    }
    getConfig() {
        return { ...this.config };
    }
    reset() {
        this.heatSources = [];
        this.thermalBodies = [];
        this.boundaryConditions = [];
        this.temperatureField.data.fill(300);
        this.time = 0;
        this.initializeMaterialProperties();
    }
}
export const ThermodynamicsSolverOps = {
    ThermodynamicsSolver,
    DEFAULT_THERMAL_CONFIG,
};
//# sourceMappingURL=thermodynamicsSolver.js.map