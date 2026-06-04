import { vec3 } from '@physics-sim/shared';
import { generateId } from '@physics-sim/shared';
import { UniformGrid, ScalarField, VectorField } from '@physics-sim/math';
const D2Q9_WEIGHTS = [4 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 36, 1 / 36, 1 / 36, 1 / 36];
const D2Q9_CX = [0, 1, 0, -1, 0, 1, -1, -1, 1];
const D2Q9_CY = [0, 0, 1, 0, -1, 1, 1, -1, -1];
const D2Q9_OPPOSITE = [0, 3, 4, 1, 2, 7, 8, 5, 6];
export const DEFAULT_LBM_CONFIG = {
    width: 200,
    height: 80,
    viscosity: 0.02,
    inletVelocity: vec3(0.1, 0, 0),
    dt: 1.0,
    dx: 1.0,
    maxIterations: 10000,
    boundaryConditions: [],
};
export class FluidDynamicsSolver {
    constructor(config) {
        this.config = { ...DEFAULT_LBM_CONFIG, ...config };
        this.nx = this.config.width;
        this.ny = this.config.height;
        this.obstacles = [];
        this.parsedObstaclePolygons = [];
        this.uniformGrid = UniformGrid.create2D(this.nx * this.config.dx, this.ny * this.config.dx, this.nx, this.ny, [0, 0, 0], 'node');
        const n = this.nx * this.ny;
        this.f = new Float64Array(n * 9);
        this.fTemp = new Float64Array(n * 9);
        this.rho = new Float64Array(n);
        this.ux = new Float64Array(n);
        this.uy = new Float64Array(n);
        this.obstacle = new Uint8Array(n);
        this.cs2 = 1.0 / 3.0;
        this.tau = this.config.viscosity / this.cs2 + 0.5;
        this.omega = 1.0 / this.tau;
        this.iteration = 0;
        this.initialize();
    }
    initialize() {
        const n = this.nx * this.ny;
        const u0 = this.config.inletVelocity;
        const rho0 = 1.0;
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                this.rho[idx] = rho0;
                this.ux[idx] = u0.x;
                this.uy[idx] = u0.y;
                for (let i = 0; i < 9; i++) {
                    const cu = D2Q9_CX[i] * u0.x + D2Q9_CY[i] * u0.y;
                    const usq = u0.x * u0.x + u0.y * u0.y;
                    this.f[idx * 9 + i] = D2Q9_WEIGHTS[i] * rho0 * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * usq);
                }
            }
        }
        for (let y = 0; y < this.ny; y++) {
            this.obstacle[y * this.nx + 0] = 1;
            this.obstacle[y * this.nx + (this.nx - 1)] = 1;
        }
        for (let x = 0; x < this.nx; x++) {
            this.obstacle[0 * this.nx + x] = 1;
            this.obstacle[(this.ny - 1) * this.nx + x] = 1;
        }
        for (const bc of this.config.boundaryConditions) {
            if (bc.type === 'bounce-back') {
                this.obstacle[0 * this.nx] = 1;
            }
        }
    }
    addObstacle(obstacle) {
        this.obstacles.push(obstacle);
        this.rasterizeObstacle(obstacle);
    }
    rasterizeObstacle(obstacle) {
        const verts = obstacle.vertices;
        if (verts.length < 3)
            return;
        const xs = verts.map(v => v.x);
        const ys = verts.map(v => v.y);
        const minX = Math.floor(Math.min(...xs));
        const maxX = Math.ceil(Math.max(...xs));
        const minY = Math.floor(Math.min(...ys));
        const maxY = Math.ceil(Math.max(...ys));
        for (let y = Math.max(0, minY); y < Math.min(this.ny, maxY); y++) {
            for (let x = Math.max(0, minX); x < Math.min(this.nx, maxX); x++) {
                if (this.pointInPolygon(x, y, verts)) {
                    const idx = y * this.nx + x;
                    this.obstacle[idx] = 1;
                    this.rho[idx] = 0;
                    this.ux[idx] = 0;
                    this.uy[idx] = 0;
                }
            }
        }
    }
    pointInPolygon(px, py, vertices) {
        let inside = false;
        const n = vertices.length;
        for (let i = 0, j = n - 1; i < n; j = i++) {
            const xi = vertices[i].x, yi = vertices[i].y;
            const xj = vertices[j].x, yj = vertices[j].y;
            if (((yi > py) !== (yj > py)) &&
                (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }
    collide() {
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                if (this.obstacle[idx])
                    continue;
                let rho = 0, ux = 0, uy = 0;
                for (let i = 0; i < 9; i++) {
                    const fi = this.f[idx * 9 + i];
                    rho += fi;
                    ux += D2Q9_CX[i] * fi;
                    uy += D2Q9_CY[i] * fi;
                }
                if (rho <= 0)
                    rho = 1e-10;
                ux /= rho;
                uy /= rho;
                this.rho[idx] = rho;
                this.ux[idx] = ux;
                this.uy[idx] = uy;
                for (let i = 0; i < 9; i++) {
                    const cu = D2Q9_CX[i] * ux + D2Q9_CY[i] * uy;
                    const usq = ux * ux + uy * uy;
                    const feq = D2Q9_WEIGHTS[i] * rho * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * usq);
                    this.f[idx * 9 + i] += this.omega * (feq - this.f[idx * 9 + i]);
                }
            }
        }
    }
    stream() {
        this.fTemp.set(this.f);
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                for (let i = 0; i < 9; i++) {
                    const nx2 = x + D2Q9_CX[i];
                    const ny2 = y + D2Q9_CY[i];
                    if (nx2 >= 0 && nx2 < this.nx && ny2 >= 0 && ny2 < this.ny) {
                        const nIdx = ny2 * this.nx + nx2;
                        this.fTemp[nIdx * 9 + i] = this.f[idx * 9 + i];
                    }
                }
            }
        }
        const tmp = this.f;
        this.f = this.fTemp;
        this.fTemp = tmp;
    }
    bounceBack() {
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                if (!this.obstacle[idx])
                    continue;
                for (let i = 0; i < 9; i++) {
                    const nx2 = x + D2Q9_CX[i];
                    const ny2 = y + D2Q9_CY[i];
                    if (nx2 >= 0 && nx2 < this.nx && ny2 >= 0 && ny2 < this.ny) {
                        const nIdx = ny2 * this.nx + nx2;
                        if (!this.obstacle[nIdx]) {
                            this.f[nIdx * 9 + D2Q9_OPPOSITE[i]] = this.f[nIdx * 9 + i];
                        }
                    }
                }
            }
        }
    }
    applyVelocityInlet() {
        const uIn = this.config.inletVelocity;
        const rho0 = 1.0;
        const x = 0;
        for (let y = 1; y < this.ny - 1; y++) {
            const idx = y * this.nx + x;
            this.rho[idx] = rho0;
            this.ux[idx] = uIn.x;
            this.uy[idx] = uIn.y;
            for (let i = 0; i < 9; i++) {
                const cu = D2Q9_CX[i] * uIn.x + D2Q9_CY[i] * uIn.y;
                const usq = uIn.x * uIn.x + uIn.y * uIn.y;
                this.f[idx * 9 + i] = D2Q9_WEIGHTS[i] * rho0 * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * usq);
            }
        }
    }
    applyPressureOutlet() {
        const rhoOut = 1.0;
        const x = this.nx - 1;
        for (let y = 1; y < this.ny - 1; y++) {
            const idxIn = y * this.nx + (x - 1);
            const idxOut = y * this.nx + x;
            const rhoIn = this.rho[idxIn];
            const uxIn = this.ux[idxIn];
            const uyIn = this.uy[idxIn];
            const rhoNew = rhoOut;
            const uxNew = uxIn * rhoIn / rhoNew;
            const uyNew = uyIn * rhoIn / rhoNew;
            this.rho[idxOut] = rhoNew;
            this.ux[idxOut] = uxNew;
            this.uy[idxOut] = uyNew;
            for (let i = 0; i < 9; i++) {
                const cu = D2Q9_CX[i] * uxNew + D2Q9_CY[i] * uyNew;
                const usq = uxNew * uxNew + uyNew * uyNew;
                this.f[idxOut * 9 + i] = D2Q9_WEIGHTS[i] * rhoNew * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * usq);
            }
        }
    }
    step(steps = 1) {
        const startTime = performance.now();
        for (let s = 0; s < steps; s++) {
            this.collide();
            this.stream();
            this.bounceBack();
            this.applyVelocityInlet();
            this.applyPressureOutlet();
            this.iteration++;
        }
        this.computeMacroscopic();
        const solveTime = performance.now() - startTime;
        return this.buildResult(solveTime);
    }
    computeMacroscopic() {
        let maxVel = 0;
        let sumRho = 0;
        let count = 0;
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                if (this.obstacle[idx])
                    continue;
                let rho = 0, ux = 0, uy = 0;
                for (let i = 0; i < 9; i++) {
                    const fi = this.f[idx * 9 + i];
                    rho += fi;
                    ux += D2Q9_CX[i] * fi;
                    uy += D2Q9_CY[i] * fi;
                }
                if (rho <= 0)
                    rho = 1e-10;
                ux /= rho;
                uy /= rho;
                this.rho[idx] = rho;
                this.ux[idx] = ux;
                this.uy[idx] = uy;
                const vel = Math.sqrt(ux * ux + uy * uy);
                if (vel > maxVel)
                    maxVel = vel;
                sumRho += rho;
                count++;
            }
        }
    }
    toUnifiedIndex(fluidIdx) {
        const y = Math.floor(fluidIdx / this.nx);
        const x = fluidIdx % this.nx;
        return this.uniformGrid.getIndex(x, y, 0);
    }
    buildResult(solveTime) {
        const grid = {
            dimensions: vec3(this.nx * this.config.dx, this.ny * this.config.dx, 1),
            resolution: vec3(this.nx, this.ny, 1),
            cellSize: vec3(this.config.dx, this.config.dx, this.config.dx),
            origin: vec3(0, 0, 0),
        };
        const densityData = new Float32Array(this.nx * this.ny);
        const velXData = new Float32Array(this.nx * this.ny);
        const velYData = new Float32Array(this.nx * this.ny);
        const velZData = new Float32Array(this.nx * this.ny);
        const pressureData = new Float32Array(this.nx * this.ny);
        let maxVel = 0;
        let avgDensity = 0;
        let count = 0;
        for (let i = 0; i < this.nx * this.ny; i++) {
            densityData[i] = this.rho[i];
            velXData[i] = this.ux[i];
            velYData[i] = this.uy[i];
            velZData[i] = 0;
            pressureData[i] = this.rho[i] * this.cs2;
            if (!this.obstacle[i]) {
                const vel = Math.sqrt(this.ux[i] ** 2 + this.uy[i] ** 2);
                if (vel > maxVel)
                    maxVel = vel;
                avgDensity += this.rho[i];
                count++;
            }
        }
        if (count > 0)
            avgDensity /= count;
        const density = {
            id: generateId(),
            type: 'density',
            grid,
            data: densityData,
            time: this.iteration,
        };
        const velocity = {
            id: generateId(),
            type: 'velocity',
            grid,
            dataX: velXData,
            dataY: velYData,
            dataZ: velZData,
            time: this.iteration,
        };
        const pressure = {
            id: generateId(),
            type: 'pressure',
            grid,
            data: pressureData,
            time: this.iteration,
        };
        return {
            density,
            velocity,
            pressure,
            iteration: this.iteration,
            maxVelocity: maxVel,
            avgDensity,
            solveTime,
        };
    }
    getDensityField() {
        const field = new ScalarField(this.uniformGrid, undefined, 'density', this.iteration);
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                field.setScalar(this.rho[idx], x, y, 0);
            }
        }
        return field;
    }
    getVelocityField() {
        const field = new VectorField(this.uniformGrid, 2, undefined, 'velocity', this.iteration);
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                field.setVector([this.ux[idx], this.uy[idx], 0], x, y, 0);
            }
        }
        return field;
    }
    getPressureField() {
        const field = new ScalarField(this.uniformGrid, undefined, 'pressure', this.iteration);
        for (let y = 0; y < this.ny; y++) {
            for (let x = 0; x < this.nx; x++) {
                const idx = y * this.nx + x;
                field.setScalar(this.rho[idx] * this.cs2, x, y, 0);
            }
        }
        return field;
    }
    getDensityAt(x, y) {
        const ix = Math.floor(x);
        const iy = Math.floor(y);
        if (ix < 0 || ix >= this.nx || iy < 0 || iy >= this.ny)
            return 0;
        return this.rho[iy * this.nx + ix];
    }
    getVelocityAt(x, y) {
        const ix = Math.floor(x);
        const iy = Math.floor(y);
        if (ix < 0 || ix >= this.nx || iy < 0 || iy >= this.ny)
            return { x: 0, y: 0 };
        return { x: this.ux[iy * this.nx + ix], y: this.uy[iy * this.nx + ix] };
    }
    getPressureAt(x, y) {
        return this.getDensityAt(x, y) * this.cs2;
    }
    isObstacle(x, y) {
        const ix = Math.floor(x);
        const iy = Math.floor(y);
        if (ix < 0 || ix >= this.nx || iy < 0 || iy >= this.ny)
            return true;
        return this.obstacle[iy * this.nx + ix] === 1;
    }
    getUniformGrid() {
        return this.uniformGrid.clone();
    }
    getGridInfo() {
        return {
            nx: this.nx,
            ny: this.ny,
            dx: this.config.dx,
            tau: this.tau,
            omega: this.omega,
        };
    }
    getIteration() {
        return this.iteration;
    }
    getConfig() {
        return { ...this.config };
    }
    setViscosity(viscosity) {
        this.config.viscosity = viscosity;
        this.tau = viscosity / this.cs2 + 0.5;
        this.omega = 1.0 / this.tau;
    }
    setInletVelocity(velocity) {
        this.config.inletVelocity = { ...velocity };
    }
    reset() {
        this.iteration = 0;
        this.obstacle.fill(0);
        this.initialize();
        for (const obs of this.obstacles) {
            this.rasterizeObstacle(obs);
        }
    }
}
//# sourceMappingURL=fluidDynamicsSolver.js.map