export function createMatrix(rows, cols, value = 0) {
    return Array.from({ length: rows }, () => Array(cols).fill(value));
}
export function createVector(size, value = 0) {
    return Array(size).fill(value);
}
export function identityMatrix(size) {
    const m = createMatrix(size, size);
    for (let i = 0; i < size; i++)
        m[i][i] = 1;
    return m;
}
export function matAdd(a, b) {
    const rows = a.length;
    const cols = a[0].length;
    const result = createMatrix(rows, cols);
    for (let i = 0; i < rows; i++) {
        for (let j = 0; j < cols; j++) {
            result[i][j] = a[i][j] + b[i][j];
        }
    }
    return result;
}
export function matSub(a, b) {
    const rows = a.length;
    const cols = a[0].length;
    const result = createMatrix(rows, cols);
    for (let i = 0; i < rows; i++) {
        for (let j = 0; j < cols; j++) {
            result[i][j] = a[i][j] - b[i][j];
        }
    }
    return result;
}
export function matMul(a, b) {
    const rowsA = a.length;
    const colsA = a[0].length;
    const colsB = b[0].length;
    const result = createMatrix(rowsA, colsB);
    for (let i = 0; i < rowsA; i++) {
        for (let j = 0; j < colsB; j++) {
            let sum = 0;
            for (let k = 0; k < colsA; k++) {
                sum += a[i][k] * b[k][j];
            }
            result[i][j] = sum;
        }
    }
    return result;
}
export function matMulScalar(m, s) {
    const rows = m.length;
    const cols = m[0].length;
    const result = createMatrix(rows, cols);
    for (let i = 0; i < rows; i++) {
        for (let j = 0; j < cols; j++) {
            result[i][j] = m[i][j] * s;
        }
    }
    return result;
}
export function matMulVector(m, v) {
    const rows = m.length;
    const cols = m[0].length;
    const result = createVector(rows);
    for (let i = 0; i < rows; i++) {
        let sum = 0;
        for (let j = 0; j < cols; j++) {
            sum += m[i][j] * v[j];
        }
        result[i] = sum;
    }
    return result;
}
export function vecAdd(a, b) {
    return a.map((v, i) => v + b[i]);
}
export function vecSub(a, b) {
    return a.map((v, i) => v - b[i]);
}
export function vecMul(v, s) {
    return v.map(x => x * s);
}
export function vecDot(a, b) {
    return a.reduce((sum, v, i) => sum + v * b[i], 0);
}
export function vecNorm(v) {
    return Math.sqrt(vecDot(v, v));
}
export function vecNormalize(v) {
    const n = vecNorm(v);
    return n > 0 ? vecMul(v, 1 / n) : v;
}
export function solveGaussSeidel(A, b, x0, tolerance = 1e-10, maxIterations = 1000) {
    const n = b.length;
    let x = [...x0];
    for (let iter = 0; iter < maxIterations; iter++) {
        let maxDiff = 0;
        for (let i = 0; i < n; i++) {
            let sum = b[i];
            for (let j = 0; j < n; j++) {
                if (j !== i) {
                    sum -= A[i][j] * x[j];
                }
            }
            const newX = sum / A[i][i];
            maxDiff = Math.max(maxDiff, Math.abs(newX - x[i]));
            x[i] = newX;
        }
        if (maxDiff < tolerance)
            break;
    }
    return x;
}
export function solveJacobi(A, b, x0, tolerance = 1e-10, maxIterations = 1000) {
    const n = b.length;
    let x = [...x0];
    for (let iter = 0; iter < maxIterations; iter++) {
        const xNew = [...x];
        let maxDiff = 0;
        for (let i = 0; i < n; i++) {
            let sum = b[i];
            for (let j = 0; j < n; j++) {
                if (j !== i) {
                    sum -= A[i][j] * x[j];
                }
            }
            xNew[i] = sum / A[i][i];
            maxDiff = Math.max(maxDiff, Math.abs(xNew[i] - x[i]));
        }
        x = xNew;
        if (maxDiff < tolerance)
            break;
    }
    return x;
}
export function tridiagonalSolve(a, b, c, d) {
    const n = d.length;
    const cPrime = new Array(n).fill(0);
    const dPrime = new Array(n).fill(0);
    const x = new Array(n).fill(0);
    cPrime[0] = c[0] / b[0];
    dPrime[0] = d[0] / b[0];
    for (let i = 1; i < n; i++) {
        const m = b[i] - a[i - 1] * cPrime[i - 1];
        cPrime[i] = c[i] / m;
        dPrime[i] = (d[i] - a[i - 1] * dPrime[i - 1]) / m;
    }
    x[n - 1] = dPrime[n - 1];
    for (let i = n - 2; i >= 0; i--) {
        x[i] = dPrime[i] - cPrime[i] * x[i + 1];
    }
    return x;
}
export function luDecomposition(A) {
    const n = A.length;
    const L = identityMatrix(n);
    const U = createMatrix(n, n);
    for (let i = 0; i < n; i++) {
        for (let j = i; j < n; j++) {
            let sum = 0;
            for (let k = 0; k < i; k++) {
                sum += L[i][k] * U[k][j];
            }
            U[i][j] = A[i][j] - sum;
        }
        if (Math.abs(U[i][i]) < 1e-10)
            return null;
        for (let j = i + 1; j < n; j++) {
            let sum = 0;
            for (let k = 0; k < i; k++) {
                sum += L[j][k] * U[k][i];
            }
            L[j][i] = (A[j][i] - sum) / U[i][i];
        }
    }
    return { L, U };
}
export function luSolve(L, U, b) {
    const n = b.length;
    const y = createVector(n);
    const x = createVector(n);
    for (let i = 0; i < n; i++) {
        let sum = 0;
        for (let j = 0; j < i; j++) {
            sum += L[i][j] * y[j];
        }
        y[i] = b[i] - sum;
    }
    for (let i = n - 1; i >= 0; i--) {
        let sum = 0;
        for (let j = i + 1; j < n; j++) {
            sum += U[i][j] * x[j];
        }
        x[i] = (y[i] - sum) / U[i][i];
    }
    return x;
}
export function crossProduct(a, b) {
    return {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    };
}
export function scalarTripleProduct(a, b, c) {
    return a.x * (b.y * c.z - b.z * c.y) +
        a.y * (b.z * c.x - b.x * c.z) +
        a.z * (b.x * c.y - b.y * c.x);
}
export const LinearAlgebra = {
    createMatrix, createVector, identityMatrix,
    matAdd, matSub, matMul, matMulScalar, matMulVector,
    vecAdd, vecSub, vecMul, vecDot, vecNorm, vecNormalize,
    solveGaussSeidel, solveJacobi, tridiagonalSolve,
    luDecomposition, luSolve, crossProduct, scalarTripleProduct
};
//# sourceMappingURL=linearAlgebra.js.map