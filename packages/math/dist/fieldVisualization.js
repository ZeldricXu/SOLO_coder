import { vec3 } from '@physics-sim/shared';
import { generateId } from '@physics-sim/shared';
export function computeStreamlines(field, seedPoints, config = {}) {
    const maxSteps = config.maxSteps || 500;
    const stepSize = config.stepSize || 0.5;
    const maxLength = config.maxLength || 100;
    const grid = field.grid;
    const streamlines = [];
    for (const seed of seedPoints) {
        const points = [];
        let pos = { ...seed };
        for (let step = 0; step < maxSteps; step++) {
            const fieldValue = interpolateVectorField(field, pos, grid);
            const magnitude = Math.sqrt(fieldValue.x ** 2 + fieldValue.y ** 2 + fieldValue.z ** 2);
            if (magnitude < 1e-10)
                break;
            const direction = {
                x: fieldValue.x / magnitude,
                y: fieldValue.y / magnitude,
                z: fieldValue.z / magnitude,
            };
            points.push({
                position: { ...pos },
                direction,
                magnitude,
            });
            pos = {
                x: pos.x + direction.x * stepSize,
                y: pos.y + direction.y * stepSize,
                z: pos.z + direction.z * stepSize,
            };
            if (pos.x < grid.origin.x ||
                pos.x > grid.origin.x + grid.dimensions.x ||
                pos.y < grid.origin.y ||
                pos.y > grid.origin.y + grid.dimensions.y ||
                pos.z < grid.origin.z ||
                pos.z > grid.origin.z + grid.dimensions.z) {
                break;
            }
            if (points.length >= maxLength)
                break;
        }
        if (points.length > 1) {
            streamlines.push({
                id: generateId(),
                fieldId: field.id,
                points,
                color: '#4ecdc4',
            });
        }
    }
    return streamlines;
}
export function generateStreamlineSeedPoints(field, density = 10) {
    const grid = field.grid;
    const seeds = [];
    const nx = Math.floor(grid.resolution.x);
    const ny = Math.floor(grid.resolution.y);
    const stepX = Math.max(1, Math.floor(nx / density));
    const stepY = Math.max(1, Math.floor(ny / density));
    for (let iy = 0; iy < ny; iy += stepY) {
        for (let ix = 0; ix < nx; ix += stepX) {
            const x = grid.origin.x + (ix + 0.5) * grid.cellSize.x;
            const y = grid.origin.y + (iy + 0.5) * grid.cellSize.y;
            const z = grid.origin.z;
            const fieldValue = interpolateVectorField(field, vec3(x, y, z), grid);
            const mag = Math.sqrt(fieldValue.x ** 2 + fieldValue.y ** 2 + fieldValue.z ** 2);
            if (mag > 1e-10) {
                seeds.push(vec3(x, y, z));
            }
        }
    }
    return seeds;
}
function interpolateVectorField(field, pos, grid) {
    const lx = (pos.x - grid.origin.x) / grid.cellSize.x;
    const ly = (pos.y - grid.origin.y) / grid.cellSize.y;
    const lz = (pos.z - grid.origin.z) / grid.cellSize.z;
    const ix = Math.floor(lx);
    const iy = Math.floor(ly);
    const iz = Math.floor(lz);
    const fx = lx - ix;
    const fy = ly - iy;
    const nx = Math.floor(grid.resolution.x);
    const ny = Math.floor(grid.resolution.y);
    if (ix < 0 || ix + 1 >= nx || iy < 0 || iy + 1 >= ny) {
        return vec3(0, 0, 0);
    }
    const idx00 = iy * nx + ix;
    const idx10 = iy * nx + (ix + 1);
    const idx01 = (iy + 1) * nx + ix;
    const idx11 = (iy + 1) * nx + (ix + 1);
    const bilinearInterp = (data) => {
        const v00 = data[idx00] || 0;
        const v10 = data[idx10] || 0;
        const v01 = data[idx01] || 0;
        const v11 = data[idx11] || 0;
        const x0 = v00 * (1 - fx) + v10 * fx;
        const x1 = v01 * (1 - fx) + v11 * fx;
        return x0 * (1 - fy) + x1 * fy;
    };
    return vec3(bilinearInterp(field.dataX), bilinearInterp(field.dataY), bilinearInterp(field.dataZ));
}
function interpolateScalarField(field, pos, grid) {
    const lx = (pos.x - grid.origin.x) / grid.cellSize.x;
    const ly = (pos.y - grid.origin.y) / grid.cellSize.y;
    const ix = Math.floor(lx);
    const iy = Math.floor(ly);
    const fx = lx - ix;
    const fy = ly - iy;
    const nx = Math.floor(grid.resolution.x);
    const ny = Math.floor(grid.resolution.y);
    if (ix < 0 || ix + 1 >= nx || iy < 0 || iy + 1 >= ny) {
        return 0;
    }
    const v00 = field.data[iy * nx + ix] || 0;
    const v10 = field.data[iy * nx + (ix + 1)] || 0;
    const v01 = field.data[(iy + 1) * nx + ix] || 0;
    const v11 = field.data[(iy + 1) * nx + (ix + 1)] || 0;
    const x0 = v00 * (1 - fx) + v10 * fx;
    const x1 = v01 * (1 - fx) + v11 * fx;
    return x0 * (1 - fy) + x1 * fy;
}
export class ParticleSystem {
    constructor(config) {
        this.particles = [];
        this.nextId = 0;
        this.config = config;
    }
    update(field, dt) {
        const vectorField = field;
        const grid = vectorField.grid;
        for (const p of this.particles) {
            p.previousPosition = { ...p.position };
            p.age += dt;
            if (vectorField.dataX && vectorField.dataY) {
                const vel = interpolateVectorField(vectorField, p.position, grid);
                const speedScale = this.config.speedScale || 1.0;
                p.position = {
                    x: p.position.x + vel.x * dt * speedScale,
                    y: p.position.y + vel.y * dt * speedScale,
                    z: p.position.z + vel.z * dt * speedScale,
                };
                const speed = Math.sqrt(vel.x ** 2 + vel.y ** 2 + vel.z ** 2);
                p.color = this.colorByValue(speed, 0, 1);
            }
        }
        this.particles = this.particles.filter(p => p.age < p.maxAge);
        const emitCount = Math.floor(this.config.emitRate * dt);
        for (let i = 0; i < emitCount && this.particles.length < this.config.maxParticles; i++) {
            this.particles.push(this.emitParticle(grid));
        }
        return this.particles;
    }
    emitParticle(grid) {
        const x = grid.origin.x + Math.random() * grid.dimensions.x;
        const y = grid.origin.y + Math.random() * grid.dimensions.y;
        const z = grid.origin.z;
        return {
            id: `p${this.nextId++}`,
            position: vec3(x, y, z),
            previousPosition: vec3(x, y, z),
            age: 0,
            maxAge: this.config.particleLifetime,
            color: vec3(0, 1, 1),
            size: this.config.particleSize,
        };
    }
    colorByValue(value, min, max) {
        const t = Math.min(1, Math.max(0, (value - min) / (max - min + 1e-10)));
        if (t < 0.25) {
            return vec3(0, t * 4, 1);
        }
        else if (t < 0.5) {
            return vec3(0, 1, 1 - (t - 0.25) * 4);
        }
        else if (t < 0.75) {
            return vec3((t - 0.5) * 4, 1, 0);
        }
        else {
            return vec3(1, 1 - (t - 0.75) * 4, 0);
        }
    }
    getParticles() {
        return this.particles;
    }
    clear() {
        this.particles = [];
    }
    setEmitRate(rate) {
        this.config.emitRate = rate;
    }
}
export function computeCrossSection(field, plane) {
    const isScalar = 'data' in field;
    const grid = field.grid;
    const resX = Math.floor(plane.resolution.x);
    const resY = Math.floor(plane.resolution.y);
    const normal = plane.normal;
    const nLen = Math.sqrt(normal.x ** 2 + normal.y ** 2 + normal.z ** 2);
    const nn = { x: normal.x / nLen, y: normal.y / nLen, z: normal.z / nLen };
    let u, v;
    if (Math.abs(nn.y) < 0.9) {
        const up = vec3(0, 1, 0);
        u = {
            x: nn.y * up.z - nn.z * up.y,
            y: nn.z * up.x - nn.x * up.z,
            z: nn.x * up.y - nn.y * up.x,
        };
    }
    else {
        const up = vec3(1, 0, 0);
        u = {
            x: nn.y * up.z - nn.z * up.y,
            y: nn.z * up.x - nn.x * up.z,
            z: nn.x * up.y - nn.y * up.x,
        };
    }
    const uLen = Math.sqrt(u.x ** 2 + u.y ** 2 + u.z ** 2);
    u = { x: u.x / uLen, y: u.y / uLen, z: u.z / uLen };
    v = {
        x: nn.y * u.z - nn.z * u.y,
        y: nn.z * u.x - nn.x * u.z,
        z: nn.x * u.y - nn.y * u.x,
    };
    const data = new Float32Array(resX * resY);
    for (let iy = 0; iy < resY; iy++) {
        for (let ix = 0; ix < resX; ix++) {
            const su = (ix / (resX - 1) - 0.5) * plane.width;
            const sv = (iy / (resY - 1) - 0.5) * plane.height;
            const worldPos = {
                x: plane.position.x + u.x * su + v.x * sv,
                y: plane.position.y + u.y * su + v.y * sv,
                z: plane.position.z + u.z * su + v.z * sv,
            };
            if (isScalar) {
                data[iy * resX + ix] = interpolateScalarField(field, worldPos, grid);
            }
            else {
                const vf = field;
                const vel = interpolateVectorField(vf, worldPos, grid);
                data[iy * resX + ix] = Math.sqrt(vel.x ** 2 + vel.y ** 2 + vel.z ** 2);
            }
        }
    }
    const csGrid = {
        dimensions: vec3(plane.width, plane.height, 0),
        resolution: vec3(resX, resY, 1),
        cellSize: vec3(plane.width / resX, plane.height / resY, 1),
        origin: vec3(plane.position.x - plane.width / 2, plane.position.y - plane.height / 2, plane.position.z),
    };
    return {
        id: generateId(),
        type: isScalar ? field.type : 'velocity',
        grid: csGrid,
        data,
        time: field.time,
    };
}
//# sourceMappingURL=fieldVisualization.js.map