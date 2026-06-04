export type RootFunction = (x: number) => number;
export type RootFunctionVec = (x: number[]) => number[];

export function bisection(
  f: RootFunction,
  a: number,
  b: number,
  tolerance: number = 1e-10,
  maxIterations: number = 1000
): { root: number; iterations: number; converged: boolean } {
  let fa = f(a);
  let fb = f(b);

  if (fa * fb > 0) {
    return { root: NaN, iterations: 0, converged: false };
  }

  if (Math.abs(fa) < tolerance) {
    return { root: a, iterations: 0, converged: true };
  }
  if (Math.abs(fb) < tolerance) {
    return { root: b, iterations: 0, converged: true };
  }

  for (let i = 0; i < maxIterations; i++) {
    const c = (a + b) / 2;
    const fc = f(c);

    if (Math.abs(fc) < tolerance || (b - a) / 2 < tolerance) {
      return { root: c, iterations: i + 1, converged: true };
    }

    if (fa * fc < 0) {
      b = c;
      fb = fc;
    } else {
      a = c;
      fa = fc;
    }
  }

  return { root: (a + b) / 2, iterations: maxIterations, converged: false };
}

export function newtonRaphson(
  f: RootFunction,
  df: RootFunction,
  x0: number,
  tolerance: number = 1e-10,
  maxIterations: number = 1000
): { root: number; iterations: number; converged: boolean } {
  let x = x0;

  for (let i = 0; i < maxIterations; i++) {
    const fx = f(x);
    if (Math.abs(fx) < tolerance) {
      return { root: x, iterations: i + 1, converged: true };
    }

    const dfx = df(x);
    if (Math.abs(dfx) < 1e-15) {
      return { root: x, iterations: i + 1, converged: false };
    }

    const newX = x - fx / dfx;
    if (Math.abs(newX - x) < tolerance) {
      return { root: newX, iterations: i + 1, converged: true };
    }

    x = newX;
  }

  return { root: x, iterations: maxIterations, converged: false };
}

export function secant(
  f: RootFunction,
  x0: number,
  x1: number,
  tolerance: number = 1e-10,
  maxIterations: number = 1000
): { root: number; iterations: number; converged: boolean } {
  let xPrev = x0;
  let x = x1;
  let fPrev = f(x0);
  let fX = f(x1);

  for (let i = 0; i < maxIterations; i++) {
    if (Math.abs(fX) < tolerance) {
      return { root: x, iterations: i + 1, converged: true };
    }

    const denominator = fX - fPrev;
    if (Math.abs(denominator) < 1e-15) {
      return { root: x, iterations: i + 1, converged: false };
    }

    const newX = x - fX * (x - xPrev) / denominator;
    if (Math.abs(newX - x) < tolerance) {
      return { root: newX, iterations: i + 1, converged: true };
    }

    xPrev = x;
    fPrev = fX;
    x = newX;
    fX = f(x);
  }

  return { root: x, iterations: maxIterations, converged: false };
}

export function regulaFalsi(
  f: RootFunction,
  a: number,
  b: number,
  tolerance: number = 1e-10,
  maxIterations: number = 1000
): { root: number; iterations: number; converged: boolean } {
  let fa = f(a);
  let fb = f(b);

  if (fa * fb > 0) {
    return { root: NaN, iterations: 0, converged: false };
  }

  for (let i = 0; i < maxIterations; i++) {
    const c = (a * fb - b * fa) / (fb - fa);
    const fc = f(c);

    if (Math.abs(fc) < tolerance || Math.abs(b - a) < tolerance) {
      return { root: c, iterations: i + 1, converged: true };
    }

    if (fa * fc < 0) {
      b = c;
      fb = fc;
    } else {
      a = c;
      fa = fc;
    }
  }

  return { root: (a * fb - b * fa) / (fb - fa), iterations: maxIterations, converged: false };
}

export function newtonRaphsonSystem(
  f: RootFunctionVec,
  jacobian: (x: number[]) => number[][],
  x0: number[],
  tolerance: number = 1e-10,
  maxIterations: number = 1000
): { root: number[]; iterations: number; converged: boolean } {
  const n = x0.length;
  let x = [...x0];

  for (let iter = 0; iter < maxIterations; iter++) {
    const fx = f(x);
    const error = Math.sqrt(fx.reduce((s, v) => s + v * v, 0));
    
    if (error < tolerance) {
      return { root: x, iterations: iter + 1, converged: true };
    }

    const J = jacobian(x);
    const delta = solveLinearSystem(J, fx.map(v => -v));
    
    if (!delta) {
      return { root: x, iterations: iter + 1, converged: false };
    }

    let maxDelta = 0;
    for (let i = 0; i < n; i++) {
      x[i] += delta[i];
      maxDelta = Math.max(maxDelta, Math.abs(delta[i]));
    }

    if (maxDelta < tolerance) {
      return { root: x, iterations: iter + 1, converged: true };
    }
  }

  return { root: x, iterations: maxIterations, converged: false };
}

function solveLinearSystem(A: number[][], b: number[]): number[] | null {
  const n = b.length;
  const augmented = new Array(n);
  
  for (let i = 0; i < n; i++) {
    augmented[i] = [...A[i], b[i]];
  }

  for (let col = 0; col < n; col++) {
    let maxRow = col;
    for (let row = col + 1; row < n; row++) {
      if (Math.abs(augmented[row][col]) > Math.abs(augmented[maxRow][col])) {
        maxRow = row;
      }
    }
    
    if (Math.abs(augmented[maxRow][col]) < 1e-15) return null;
    
    [augmented[col], augmented[maxRow]] = [augmented[maxRow], augmented[col]];

    const pivot = augmented[col][col];
    for (let row = col; row < n + 1; row++) {
      augmented[col][row] /= pivot;
    }

    for (let row = 0; row < n; row++) {
      if (row !== col && Math.abs(augmented[row][col]) > 1e-15) {
        const factor = augmented[row][col];
        for (let col2 = col; col2 < n + 1; col2++) {
          augmented[row][col2] -= factor * augmented[col][col2];
        }
      }
    }
  }

  const result = new Array(n);
  for (let i = 0; i < n; i++) {
    result[i] = augmented[i][n];
  }
  return result;
}

export function findRootsPolynomial(coeffs: number[]): number[] {
  const degree = coeffs.length - 1;
  const roots: number[] = [];
  
  let remaining = [...coeffs];
  
  for (let d = degree; d >= 1; d--) {
    if (remaining.length <= 1) break;
    
    let x0 = 0;
    for (let attempt = 0; attempt < 10; attempt++) {
      x0 = Math.random() * 2 - 1;
      const result = newtonRaphson(
        x => evaluatePolynomial(remaining, x),
        x => evaluatePolynomialDerivative(remaining, x),
        x0,
        1e-10,
        1000
      );
      
      if (result.converged) {
        roots.push(result.root);
        remaining = deflatePolynomial(remaining, result.root);
        break;
      }
    }
  }
  
  return roots;
}

function evaluatePolynomial(coeffs: number[], x: number): number {
  let result = 0;
  for (let i = 0; i < coeffs.length; i++) {
    result = result * x + coeffs[i];
  }
  return result;
}

function evaluatePolynomialDerivative(coeffs: number[], x: number): number {
  let result = 0;
  for (let i = 0; i < coeffs.length - 1; i++) {
    result = result * x + coeffs[i] * (coeffs.length - 1 - i);
  }
  return result;
}

function deflatePolynomial(coeffs: number[], root: number): number[] {
  const n = coeffs.length;
  const result = new Array(n - 1);
  result[0] = coeffs[0];
  for (let i = 1; i < n - 1; i++) {
    result[i] = coeffs[i] + root * result[i - 1];
  }
  return result;
}

export const RootFinding = {
  bisection,
  newtonRaphson,
  secant,
  regulaFalsi,
  newtonRaphsonSystem,
  findRootsPolynomial,
};
