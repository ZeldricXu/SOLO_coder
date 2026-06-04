export function dotProduct(a, b) {
    let result = 0;
    const n = a.length;
    for (let i = 0; i < n; i++) {
        result += a[i] * b[i];
    }
    return result;
}
export function conjugateGradient(A, b, x0, tolerance = 1e-6, maxIterations = 10000, preconditioner) {
    const n = b.length;
    const x = new Float32Array(x0);
    const r = new Float32Array(n);
    const p = new Float32Array(n);
    const z = new Float32Array(n);
    const Ap = new Float32Array(n);
    const Ax = A(x);
    for (let i = 0; i < n; i++) {
        r[i] = b[i] - Ax[i];
    }
    if (preconditioner) {
        const precondR = preconditioner(r);
        z.set(precondR);
    }
    else {
        z.set(r);
    }
    p.set(z);
    let rsOld = preconditioner ? dotProduct(r, z) : dotProduct(r, r);
    if (Math.sqrt(rsOld) < tolerance) {
        return { x, iterations: 0, residual: Math.sqrt(rsOld), converged: true };
    }
    for (let k = 0; k < maxIterations; k++) {
        const ApResult = A(p);
        Ap.set(ApResult);
        const pAp = dotProduct(p, Ap);
        if (Math.abs(pAp) < 1e-15) {
            break;
        }
        const alpha = rsOld / pAp;
        for (let i = 0; i < n; i++) {
            x[i] += alpha * p[i];
            r[i] -= alpha * Ap[i];
        }
        if (preconditioner) {
            const precondR = preconditioner(r);
            z.set(precondR);
        }
        const rsNew = preconditioner ? dotProduct(r, z) : dotProduct(r, r);
        const residualNorm = Math.sqrt(rsNew);
        if (residualNorm < tolerance) {
            return { x, iterations: k + 1, residual: residualNorm, converged: true };
        }
        const beta = rsNew / rsOld;
        for (let i = 0; i < n; i++) {
            p[i] = (preconditioner ? z[i] : r[i]) + beta * p[i];
        }
        rsOld = rsNew;
    }
    return {
        x,
        iterations: maxIterations,
        residual: Math.sqrt(preconditioner ? dotProduct(r, r) : rsOld),
        converged: false
    };
}
export function createMultigridPreconditioner(nx, ny, nz, dx, dy, dz, use3D, numLevels = 3) {
    const levels = [];
    let currentNx = nx;
    let currentNy = ny;
    let currentNz = nz;
    for (let level = 0; level < numLevels; level++) {
        levels.push({
            nx: currentNx,
            ny: currentNy,
            nz: currentNz,
            data: new Float32Array(currentNx * currentNy * currentNz)
        });
        currentNx = Math.max(2, Math.floor(currentNx / 2));
        currentNy = Math.max(2, Math.floor(currentNy / 2));
        currentNz = use3D ? Math.max(2, Math.floor(currentNz / 2)) : 1;
    }
    function getIndex(i, j, k, level) {
        const lvl = levels[level];
        return i + j * lvl.nx + k * lvl.nx * lvl.ny;
    }
    function restrict(fine, fineNx, fineNy, fineNz, coarse, coarseNx, coarseNy, coarseNz) {
        for (let k = 0; k < coarseNz; k++) {
            for (let j = 0; j < coarseNy; j++) {
                for (let i = 0; i < coarseNx; i++) {
                    const fi = i * 2;
                    const fj = j * 2;
                    const fk = k * 2;
                    let sum = 0;
                    let count = 0;
                    for (let dk = 0; dk < 2 && fk + dk < fineNz; dk++) {
                        for (let dj = 0; dj < 2 && fj + dj < fineNy; dj++) {
                            for (let di = 0; di < 2 && fi + di < fineNx; di++) {
                                const fineIdx = (fi + di) + (fj + dj) * fineNx + (fk + dk) * fineNx * fineNy;
                                sum += fine[fineIdx];
                                count++;
                            }
                        }
                    }
                    const coarseIdx = i + j * coarseNx + k * coarseNx * coarseNy;
                    coarse[coarseIdx] = sum / count;
                }
            }
        }
    }
    function prolongate(coarse, coarseNx, coarseNy, coarseNz, fine, fineNx, fineNy, fineNz) {
        for (let k = 0; k < fineNz; k++) {
            for (let j = 0; j < fineNy; j++) {
                for (let i = 0; i < fineNx; i++) {
                    const ci = Math.min(Math.floor(i / 2), coarseNx - 1);
                    const cj = Math.min(Math.floor(j / 2), coarseNy - 1);
                    const ck = Math.min(Math.floor(k / 2), coarseNz - 1);
                    const coarseIdx = ci + cj * coarseNx + ck * coarseNx * coarseNy;
                    const fineIdx = i + j * fineNx + k * fineNx * fineNy;
                    fine[fineIdx] = coarse[coarseIdx];
                }
            }
        }
    }
    function smoothJacobi(r, result, level, iterations = 3) {
        const lvl = levels[level];
        const hx2 = dx * dx * Math.pow(2, level);
        const hy2 = dy * dy * Math.pow(2, level);
        const hz2 = dz * dz * Math.pow(2, level);
        const diag = 2 * (1 / hx2 + 1 / hy2 + (use3D ? 1 / hz2 : 0));
        const kStart = use3D ? 1 : 0;
        const kEnd = use3D ? lvl.nz - 1 : 1;
        for (let iter = 0; iter < iterations; iter++) {
            for (let k = kStart; k < kEnd; k++) {
                for (let j = 1; j < lvl.ny - 1; j++) {
                    for (let i = 1; i < lvl.nx - 1; i++) {
                        const idx = getIndex(i, j, k, level);
                        const idxIp = getIndex(i + 1, j, k, level);
                        const idxIm = getIndex(i - 1, j, k, level);
                        const idxJp = getIndex(i, j + 1, k, level);
                        const idxJm = getIndex(i, j - 1, k, level);
                        const idxKp = use3D ? getIndex(i, j, k + 1, level) : idx;
                        const idxKm = use3D ? getIndex(i, j, k - 1, level) : idx;
                        let laplacian = (result[idxIp] + result[idxIm]) / hx2 +
                            (result[idxJp] + result[idxJm]) / hy2;
                        if (use3D) {
                            laplacian += (result[idxKp] + result[idxKm]) / hz2;
                        }
                        result[idx] = (r[idx] + laplacian) / diag;
                    }
                }
            }
        }
    }
    return function multigridPreconditioner(r) {
        const result = new Float32Array(r);
        levels[0].data.set(r);
        smoothJacobi(r, result, 0, 2);
        for (let level = 1; level < numLevels; level++) {
            restrict(levels[level - 1].data, levels[level - 1].nx, levels[level - 1].ny, levels[level - 1].nz, levels[level].data, levels[level].nx, levels[level].ny, levels[level].nz);
            smoothJacobi(levels[level].data, levels[level].data, level, 3);
        }
        for (let level = numLevels - 2; level >= 0; level--) {
            prolongate(levels[level + 1].data, levels[level + 1].nx, levels[level + 1].ny, levels[level + 1].nz, levels[level].data, levels[level].nx, levels[level].ny, levels[level].nz);
            smoothJacobi(levels[level].data, levels[level].data, level, 2);
        }
        return levels[0].data;
    };
}
//# sourceMappingURL=conjugateGradient.js.map