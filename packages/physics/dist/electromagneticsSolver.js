import { vec3 } from '@physics-sim/shared';
import { generateId } from '@physics-sim/shared';
import { conjugateGradient, createMultigridPreconditioner } from '@physics-sim/math';
import { Vec3Ops } from '@physics-sim/math';
import { UniformGrid, ScalarField, VectorField } from '@physics-sim/math';
export const DEFAULT_FDM_CONFIG = {
    dimensions: vec3(10, 10, 10),
    resolution: vec3(32, 32, 32),
    origin: vec3(-5, -5, -5),
    maxIterations: 10000,
    tolerance: 1e-6,
    relaxationFactor: 1.5,
    use3D: true,
    solver: 'gauss-seidel',
    useMultigrid: false,
    multigridLevels: 3,
};
export class ElectromagneticsSolver {
    constructor(config = {}) {
        this.lastPotentialField = null;
        this.lastElectricField = null;
        this.config = { ...DEFAULT_FDM_CONFIG, ...config };
        this.uniformGrid = this.createUniformGrid();
        this.grid = this.createLegacyGrid();
        this.charges = [];
        this.currents = [];
        this.magnets = [];
        this.boundaryConditions = new Map();
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
    addCharge(position, charge) {
        const c = { position: { ...position }, charge, id: generateId() };
        this.charges.push(c);
        return c;
    }
    removeCharge(id) {
        const index = this.charges.findIndex(c => c.id === id);
        if (index !== -1) {
            this.charges.splice(index, 1);
            return true;
        }
        return false;
    }
    addCurrent(position, direction, magnitude) {
        const c = {
            position: { ...position },
            direction: Vec3Ops.normalize(direction),
            magnitude,
            id: generateId(),
        };
        this.currents.push(c);
        return c;
    }
    removeCurrent(id) {
        const index = this.currents.findIndex(c => c.id === id);
        if (index !== -1) {
            this.currents.splice(index, 1);
            return true;
        }
        return false;
    }
    addMagnet(position, moment) {
        const m = { position: { ...position }, moment: { ...moment }, id: generateId() };
        this.magnets.push(m);
        return m;
    }
    removeMagnet(id) {
        const index = this.magnets.findIndex(m => m.id === id);
        if (index !== -1) {
            this.magnets.splice(index, 1);
            return true;
        }
        return false;
    }
    setBoundaryCondition(fieldName, conditions) {
        this.boundaryConditions.set(fieldName, conditions);
    }
    solveElectrostatic(time = 0) {
        const startTime = performance.now();
        const [nx, ny, nz] = this.uniformGrid.resolution;
        const potentialField = new ScalarField(this.uniformGrid, undefined, 'potential', time);
        const chargeDensityField = new ScalarField(this.uniformGrid);
        this.buildChargeDensity(chargeDensityField, nx, ny, nz);
        this.applyPotentialBoundaryConditions(potentialField.data, nx, ny, nz);
        const { iterations, residual, converged } = this.solvePoissonEquation(potentialField.data, chargeDensityField.data, nx, ny, nz);
        const potentialFieldResult = {
            id: generateId(),
            type: 'electric',
            grid: this.grid,
            data: new Float32Array(potentialField.data),
            time,
        };
        const electricField = this.computeElectricField(potentialField, nx, ny, nz, time);
        this.lastPotentialField = potentialField.clone();
        this.lastElectricField = this.toUnifiedVectorField(electricField, time);
        const endTime = performance.now();
        return {
            potential: potentialFieldResult,
            field: electricField,
            iterations,
            residual,
            solveTime: endTime - startTime,
            converged,
            solver: this.config.solver,
        };
    }
    toUnifiedVectorField(legacyField, time) {
        const field = new VectorField(this.uniformGrid, 3, undefined, legacyField.type, time);
        const n = this.uniformGrid.totalNodes;
        for (let i = 0; i < n; i++) {
            const [ix, iy, iz] = this.uniformGrid.getIndices(i);
            field.setVector([legacyField.dataX[i], legacyField.dataY[i], legacyField.dataZ[i]], ix, iy, iz);
        }
        return field;
    }
    getPotentialFieldData() {
        if (!this.lastPotentialField) {
            throw new Error('No potential field computed yet. Call solveElectrostatic() first.');
        }
        return this.lastPotentialField.clone();
    }
    getElectricFieldData() {
        if (!this.lastElectricField) {
            throw new Error('No electric field computed yet. Call solveElectrostatic() first.');
        }
        return this.lastElectricField.clone();
    }
    getTotalCharge() {
        return this.charges.reduce((sum, c) => sum + c.charge, 0);
    }
    solveMagnetostatic(time = 0) {
        const startTime = performance.now();
        const [nx, ny, nz] = this.uniformGrid.resolution;
        const vectorPotential = new VectorField(this.uniformGrid, 3, undefined, 'vectorPotential', time);
        const currentDensity = new VectorField(this.uniformGrid, 3, undefined, 'currentDensity', time);
        this.buildCurrentDensity(currentDensity, nx, ny, nz);
        let totalIterations = 0;
        let totalResidual = 0;
        const components = this.config.use3D ? 3 : 2;
        const results = [];
        for (let c = 0; c < components; c++) {
            const compData = new Float32Array(this.uniformGrid.totalNodes);
            const sourceData = new Float32Array(this.uniformGrid.totalNodes);
            for (let i = 0; i < this.uniformGrid.totalNodes; i++) {
                sourceData[i] = currentDensity.data[i * 3 + c];
            }
            const result = this.solvePoissonEquation(compData, sourceData, nx, ny, nz);
            results.push(result);
            for (let i = 0; i < this.uniformGrid.totalNodes; i++) {
                vectorPotential.data[i * 3 + c] = compData[i];
            }
        }
        totalIterations = Math.max(...results.map(r => r.iterations));
        totalResidual = Math.max(...results.map(r => r.residual));
        const allConverged = results.every(r => r.converged);
        const magneticField = this.computeMagneticField(vectorPotential, nx, ny, nz, time);
        this.addMagneticDipoleField(magneticField, nx, ny, nz);
        const endTime = performance.now();
        return {
            potential: {
                id: generateId(),
                type: 'magnetic',
                grid: this.grid,
                data: new Float32Array(this.uniformGrid.totalNodes),
                time,
            },
            field: this.toLegacyVectorField(magneticField, time),
            iterations: totalIterations,
            residual: totalResidual,
            solveTime: endTime - startTime,
            converged: allConverged,
            solver: this.config.solver,
        };
    }
    toLegacyVectorField(field, time) {
        const n = this.uniformGrid.totalNodes;
        const dataX = new Float32Array(n);
        const dataY = new Float32Array(n);
        const dataZ = new Float32Array(n);
        for (let i = 0; i < n; i++) {
            dataX[i] = field.data[i * 3 + 0];
            dataY[i] = field.data[i * 3 + 1];
            dataZ[i] = field.data[i * 3 + 2];
        }
        const fieldType = field.name === 'magnetic' ? 'magnetic' : 'electric';
        return {
            id: generateId(),
            type: fieldType,
            grid: this.grid,
            dataX,
            dataY,
            dataZ,
            time,
        };
    }
    buildChargeDensity(chargeDensity, nx, ny, nz) {
        const epsilon0 = 8.854e-12;
        for (const charge of this.charges) {
            const gridPos = this.worldToGrid(charge.position);
            const ix = Math.floor(gridPos.x);
            const iy = Math.floor(gridPos.y);
            const iz = this.config.use3D ? Math.floor(gridPos.z) : 0;
            if (ix >= 0 && ix < nx && iy >= 0 && iy < ny && iz >= 0 && iz < nz) {
                const idx = this.uniformGrid.getIndex(ix, iy, iz);
                const cellVolume = this.grid.cellSize.x * this.grid.cellSize.y *
                    (this.config.use3D ? this.grid.cellSize.z : 1);
                chargeDensity.data[idx] += charge.charge / (epsilon0 * cellVolume);
            }
        }
    }
    buildCurrentDensity(currentDensity, nx, ny, nz) {
        const mu0 = 4 * Math.PI * 1e-7;
        for (const current of this.currents) {
            const gridPos = this.worldToGrid(current.position);
            const ix = Math.floor(gridPos.x);
            const iy = Math.floor(gridPos.y);
            const iz = this.config.use3D ? Math.floor(gridPos.z) : 0;
            if (ix >= 0 && ix < nx && iy >= 0 && iy < ny && iz >= 0 && iz < nz) {
                const idx = this.uniformGrid.getIndex(ix, iy, iz);
                const cellVolume = this.grid.cellSize.x * this.grid.cellSize.y *
                    (this.config.use3D ? this.grid.cellSize.z : 1);
                const scaledCurrent = current.magnitude / cellVolume;
                const baseIdx = idx * 3;
                currentDensity.data[baseIdx + 0] += mu0 * scaledCurrent * current.direction.x;
                currentDensity.data[baseIdx + 1] += mu0 * scaledCurrent * current.direction.y;
                currentDensity.data[baseIdx + 2] += mu0 * scaledCurrent * current.direction.z;
            }
        }
    }
    applyPotentialBoundaryConditions(potential, nx, ny, nz) {
        const conditions = this.boundaryConditions.get('potential') || [];
        for (const condition of conditions) {
            if (condition.faceIndex !== undefined) {
                this.applyFaceBoundaryCondition(potential, nx, ny, nz, condition);
            }
        }
    }
    applyFaceBoundaryCondition(data, nx, ny, nz, condition) {
        const value = typeof condition.value === 'number' ? condition.value : 0;
        const face = condition.faceIndex || 0;
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
                            data[idx] = value;
                        }
                    }
                }
            }
        }
    }
    solvePoissonEquation(phi, rho, nx, ny, nz) {
        const dx = this.grid.cellSize.x;
        const dy = this.grid.cellSize.y;
        const dz = this.config.use3D ? this.grid.cellSize.z : 1;
        const dx2 = dx * dx;
        const dy2 = dy * dy;
        const dz2 = dz * dz;
        const hx = 1 / dx2;
        const hy = 1 / dy2;
        const hz = this.config.use3D ? 1 / dz2 : 0;
        const diagonal = 2 * (hx + hy + (this.config.use3D ? hz : 0));
        if (this.config.solver === 'gauss-seidel') {
            let residual = Infinity;
            let iterations = 0;
            const omega = this.config.relaxationFactor;
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
                            const laplacian = hx * (phi[idxIp] + phi[idxIm]) +
                                hy * (phi[idxJp] + phi[idxJm]) +
                                (this.config.use3D ? hz * (phi[idxKp] + phi[idxKm]) : 0);
                            const newValue = (laplacian - rho[idx]) / diagonal;
                            const delta = newValue - phi[idx];
                            phi[idx] += omega * delta;
                            residual += delta * delta;
                        }
                    }
                }
                residual = Math.sqrt(residual / (nx * ny * nz));
                iterations++;
            }
            return { iterations, residual, converged: residual <= this.config.tolerance };
        }
        const isBoundary = new Uint8Array(nx * ny * nz);
        for (let i = 0; i < nx; i++) {
            for (let j = 0; j < ny; j++) {
                for (let k = 0; k < nz; k++) {
                    const idx = this.uniformGrid.getIndex(i, j, k);
                    if (i === 0 || i === nx - 1 || j === 0 || j === ny - 1 || (this.config.use3D && (k === 0 || k === nz - 1))) {
                        isBoundary[idx] = 1;
                    }
                }
            }
        }
        this.boundaryConditions.forEach((conditions) => {
            for (const cond of conditions) {
                const condAny = cond;
                if (condAny.region) {
                    for (let i = 0; i < nx; i++) {
                        for (let j = 0; j < ny; j++) {
                            for (let k = 0; k < nz; k++) {
                                const coord = this.uniformGrid.getCoordinate(i, j, k);
                                const pos = vec3(coord[0], coord[1], coord[2]);
                                if (this.isInRegion(pos, condAny.region)) {
                                    const idx = this.uniformGrid.getIndex(i, j, k);
                                    isBoundary[idx] = 1;
                                }
                            }
                        }
                    }
                }
            }
        });
        const applyLaplacian = (x) => {
            const result = new Float32Array(x.length);
            for (let i = 0; i < nx; i++) {
                for (let j = 0; j < ny; j++) {
                    for (let k = 0; k < nz; k++) {
                        const idx = this.uniformGrid.getIndex(i, j, k);
                        if (isBoundary[idx]) {
                            result[idx] = x[idx] - phi[idx];
                            continue;
                        }
                        const idxIp = this.uniformGrid.getIndex(i + 1, j, k);
                        const idxIm = this.uniformGrid.getIndex(i - 1, j, k);
                        const idxJp = this.uniformGrid.getIndex(i, j + 1, k);
                        const idxJm = this.uniformGrid.getIndex(i, j - 1, k);
                        const idxKp = this.config.use3D ? this.uniformGrid.getIndex(i, j, k + 1) : idx;
                        const idxKm = this.config.use3D ? this.uniformGrid.getIndex(i, j, k - 1) : idx;
                        let laplacian = hx * (x[idxIp] + x[idxIm]) +
                            hy * (x[idxJp] + x[idxJm]);
                        if (this.config.use3D) {
                            laplacian += hz * (x[idxKp] + x[idxKm]);
                        }
                        result[idx] = diagonal * x[idx] - laplacian;
                    }
                }
            }
            return result;
        };
        const b = new Float32Array(rho.length);
        for (let i = 0; i < b.length; i++) {
            if (isBoundary[i]) {
                b[i] = phi[i];
            }
            else {
                b[i] = -rho[i];
            }
        }
        const x0 = new Float32Array(phi);
        let cgResult;
        if (this.config.solver === 'cg-multigrid' && this.config.useMultigrid) {
            const preconditioner = createMultigridPreconditioner(nx, ny, nz, dx, dy, dz, this.config.use3D, this.config.multigridLevels);
            cgResult = conjugateGradient(applyLaplacian, b, x0, this.config.tolerance, this.config.maxIterations, preconditioner);
        }
        else {
            cgResult = conjugateGradient(applyLaplacian, b, x0, this.config.tolerance, this.config.maxIterations);
        }
        for (let i = 0; i < phi.length; i++) {
            if (!isBoundary[i]) {
                phi[i] = cgResult.x[i];
            }
        }
        if (!cgResult.converged) {
            console.warn(`[ElectromagneticsSolver] Poisson equation did not converge after ${cgResult.iterations} iterations. ` +
                `Residual: ${cgResult.residual.toExponential(4)}. ` +
                `Please check boundary conditions or increase maxIterations.`);
        }
        return {
            iterations: cgResult.iterations,
            residual: cgResult.residual,
            converged: cgResult.converged
        };
    }
    isInRegion(pos, region) {
        return pos.x >= region.min.x && pos.x <= region.max.x &&
            pos.y >= region.min.y && pos.y <= region.max.y &&
            pos.z >= region.min.z && pos.z <= region.max.z;
    }
    computeElectricField(potential, nx, ny, nz, time) {
        const result = new VectorField(this.uniformGrid, 3, undefined, 'electric', time);
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
                    const ex = -(potential.data[idxIp] - potential.data[idxIm]) / (2 * dx);
                    const ey = -(potential.data[idxJp] - potential.data[idxJm]) / (2 * dy);
                    const ez = this.config.use3D ? -(potential.data[idxKp] - potential.data[idxKm]) / (2 * dz) : 0;
                    result.setVector([ex, ey, ez], i, j, k);
                }
            }
        }
        return this.toLegacyVectorField(result, time);
    }
    computeMagneticField(vectorPotential, nx, ny, nz, time) {
        const result = new VectorField(this.uniformGrid, 3, undefined, 'magnetic', time);
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
                    const idxJp = this.uniformGrid.getIndex(i, jPlus, k);
                    const idxJm = this.uniformGrid.getIndex(i, jMinus, k);
                    const idxKp = this.uniformGrid.getIndex(i, j, kPlus);
                    const idxKm = this.uniformGrid.getIndex(i, j, kMinus);
                    const idxIp = this.uniformGrid.getIndex(iPlus, j, k);
                    const idxIm = this.uniformGrid.getIndex(iMinus, j, k);
                    const baseIdx = idx * 3;
                    const baseJp = idxJp * 3;
                    const baseJm = idxJm * 3;
                    const baseKp = idxKp * 3;
                    const baseKm = idxKm * 3;
                    const baseIp = idxIp * 3;
                    const baseIm = idxIm * 3;
                    const dAzDy = (vectorPotential.data[baseJp + 2] - vectorPotential.data[baseJm + 2]) / (2 * dy);
                    const dAyDz = this.config.use3D ? (vectorPotential.data[baseKp + 1] - vectorPotential.data[baseKm + 1]) / (2 * dz) : 0;
                    const dAxDz = this.config.use3D ? (vectorPotential.data[baseKp + 0] - vectorPotential.data[baseKm + 0]) / (2 * dz) : 0;
                    const dAzDx = (vectorPotential.data[baseIp + 2] - vectorPotential.data[baseIm + 2]) / (2 * dx);
                    const dAyDx = (vectorPotential.data[baseIp + 1] - vectorPotential.data[baseIm + 1]) / (2 * dx);
                    const dAxDy = (vectorPotential.data[baseJp + 0] - vectorPotential.data[baseJm + 0]) / (2 * dy);
                    const bx = dAzDy - dAyDz;
                    const by = dAxDz - dAzDx;
                    const bz = dAyDx - dAxDy;
                    result.setVector([bx, by, bz], i, j, k);
                }
            }
        }
        return result;
    }
    addMagneticDipoleField(magneticField, nx, ny, nz) {
        const mu0 = 4 * Math.PI * 1e-7;
        for (const magnet of this.magnets) {
            for (let k = 0; k < nz; k++) {
                for (let j = 0; j < ny; j++) {
                    for (let i = 0; i < nx; i++) {
                        const gridPos = this.gridToWorld(i, j, k);
                        const r = Vec3Ops.sub(gridPos, magnet.position);
                        const rMag = Vec3Ops.length(r);
                        if (rMag > 0.1) {
                            const idx = this.uniformGrid.getIndex(i, j, k);
                            const rNorm = Vec3Ops.normalize(r);
                            const mDotR = Vec3Ops.dot(magnet.moment, rNorm);
                            const factor = mu0 / (4 * Math.PI * Math.pow(rMag, 3));
                            const dipoleField = Vec3Ops.sub(Vec3Ops.mul(rNorm, 3 * mDotR), magnet.moment);
                            const baseIdx = idx * 3;
                            magneticField.data[baseIdx + 0] += factor * dipoleField.x;
                            magneticField.data[baseIdx + 1] += factor * dipoleField.y;
                            magneticField.data[baseIdx + 2] += factor * dipoleField.z;
                        }
                    }
                }
            }
        }
    }
    getFieldAtPosition(field, position) {
        const components = 3;
        const n = this.uniformGrid.totalNodes;
        const unifiedField = new VectorField(this.uniformGrid, components);
        for (let i = 0; i < n; i++) {
            unifiedField.data[i * 3 + 0] = field.dataX[i];
            unifiedField.data[i * 3 + 1] = field.dataY[i];
            unifiedField.data[i * 3 + 2] = field.dataZ[i];
        }
        const interpolated = unifiedField.interpolateVector(position.x, position.y, position.z);
        return vec3(interpolated[0], interpolated[1], interpolated[2]);
    }
    worldToGrid(worldPos) {
        return vec3((worldPos.x - this.grid.origin.x) / this.grid.cellSize.x, (worldPos.y - this.grid.origin.y) / this.grid.cellSize.y, this.config.use3D ? (worldPos.z - this.grid.origin.z) / this.grid.cellSize.z : 0);
    }
    gridToWorld(ix, iy, iz) {
        const coord = this.uniformGrid.getCoordinate(ix, iy, iz);
        return vec3(coord[0], coord[1], coord[2]);
    }
    getUniformGrid() {
        return this.uniformGrid.clone();
    }
    getGrid() {
        return { ...this.grid };
    }
    setConfig(config) {
        this.config = { ...this.config, ...config };
        this.uniformGrid = this.createUniformGrid();
        this.grid = this.createLegacyGrid();
    }
    getConfig() {
        return { ...this.config };
    }
    reset() {
        this.charges = [];
        this.currents = [];
        this.magnets = [];
        this.boundaryConditions.clear();
    }
}
export const ElectromagneticsSolverOps = {
    ElectromagneticsSolver,
    DEFAULT_FDM_CONFIG,
};
//# sourceMappingURL=electromagneticsSolver.js.map