import { CurveFitType, CurveFitResult } from '@physics-sim/shared';

export function leastSquaresLinear(x: number[], y: number[]): { a: number; b: number; rSquared: number } {
  const n = x.length;
  if (n < 2) return { a: 0, b: 0, rSquared: 0 };

  let sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
  for (let i = 0; i < n; i++) {
    sumX += x[i];
    sumY += y[i];
    sumXY += x[i] * y[i];
    sumXX += x[i] * x[i];
  }

  const denominator = n * sumXX - sumX * sumX;
  const a = (n * sumXY - sumX * sumY) / denominator;
  const b = (sumY - a * sumX) / n;

  const yMean = sumY / n;
  let ssTotal = 0, ssResidual = 0;
  for (let i = 0; i < n; i++) {
    const yPred = a * x[i] + b;
    ssTotal += (y[i] - yMean) ** 2;
    ssResidual += (y[i] - yPred) ** 2;
  }
  const rSquared = 1 - (ssResidual / ssTotal);

  return { a, b, rSquared };
}

export function leastSquaresQuadratic(x: number[], y: number[]): { a: number; b: number; c: number; rSquared: number } {
  const n = x.length;
  if (n < 3) return { a: 0, b: 0, c: 0, rSquared: 0 };

  let sumX = 0, sumY = 0, sumX2 = 0, sumX3 = 0, sumX4 = 0;
  let sumXY = 0, sumX2Y = 0;

  for (let i = 0; i < n; i++) {
    const xi = x[i], yi = y[i];
    const xi2 = xi * xi, xi3 = xi2 * xi, xi4 = xi3 * xi;
    sumX += xi;
    sumY += yi;
    sumX2 += xi2;
    sumX3 += xi3;
    sumX4 += xi4;
    sumXY += xi * yi;
    sumX2Y += xi2 * yi;
  }

  const A = [
    [n, sumX, sumX2],
    [sumX, sumX2, sumX3],
    [sumX2, sumX3, sumX4]
  ];
  const B = [sumY, sumXY, sumX2Y];

  const result = solveLinearSystem(A, B);
  if (!result) return { a: 0, b: 0, c: 0, rSquared: 0 };

  const [c, b, a] = result;

  const yMean = sumY / n;
  let ssTotal = 0, ssResidual = 0;
  for (let i = 0; i < n; i++) {
    const yPred = a * x[i] * x[i] + b * x[i] + c;
    ssTotal += (y[i] - yMean) ** 2;
    ssResidual += (y[i] - yPred) ** 2;
  }
  const rSquared = 1 - (ssResidual / ssTotal);

  return { a, b, c, rSquared };
}

export function leastSquaresExponential(x: number[], y: number[]): { a: number; b: number; rSquared: number } {
  const n = x.length;
  if (n < 2) return { a: 0, b: 0, rSquared: 0 };

  const logY = y.map(yi => Math.log(Math.max(yi, 1e-15)));
  
  const result = leastSquaresLinear(x, logY);
  const a = Math.exp(result.b);
  const b = result.a;

  const yMean = y.reduce((s, yi) => s + yi, 0) / n;
  let ssTotal = 0, ssResidual = 0;
  for (let i = 0; i < n; i++) {
    const yPred = a * Math.exp(b * x[i]);
    ssTotal += (y[i] - yMean) ** 2;
    ssResidual += (y[i] - yPred) ** 2;
  }
  const rSquared = 1 - (ssResidual / ssTotal);

  return { a, b, rSquared };
}

export function leastSquaresSine(x: number[], y: number[], initialFrequency?: number): { 
  amplitude: number; 
  frequency: number; 
  phase: number; 
  offset: number;
  rSquared: number 
} {
  const n = x.length;
  if (n < 4) return { amplitude: 0, frequency: 0, phase: 0, offset: 0, rSquared: 0 };

  let frequency = initialFrequency || estimateFrequency(x, y);
  let amplitude = 1, phase = 0, offset = 0;

  const maxIterations = 100;
  const tolerance = 1e-10;

  for (let iter = 0; iter < maxIterations; iter++) {
    let sumSin2 = 0, sumCos2 = 0, sumSinCos = 0;
    let sumYSin = 0, sumYCos = 0, sumSin = 0, sumCos = 0;
    let sumY = 0, sum1 = 0;

    for (let i = 0; i < n; i++) {
      const wt = frequency * x[i];
      const s = Math.sin(wt + phase);
      const c = Math.cos(wt + phase);
      
      sumSin2 += s * s;
      sumCos2 += c * c;
      sumSinCos += s * c;
      sumYSin += y[i] * s;
      sumYCos += y[i] * c;
      sumSin += s;
      sumCos += c;
      sumY += y[i];
      sum1 += 1;
    }

    const A = [
      [sumSin2, sumSinCos, sumSin],
      [sumSinCos, sumCos2, sumCos],
      [sumSin, sumCos, sum1]
    ];
    const B = [sumYSin, sumYCos, sumY];

    const result = solveLinearSystem(A, B);
    if (!result) break;

    const [newAmplitude, , newOffset] = result;
    
    if (Math.abs(newAmplitude - amplitude) < tolerance) {
      amplitude = newAmplitude;
      offset = newOffset;
      break;
    }
    
    amplitude = newAmplitude;
    offset = newOffset;
  }

  const yMean = y.reduce((s, yi) => s + yi, 0) / n;
  let ssTotal = 0, ssResidual = 0;
  for (let i = 0; i < n; i++) {
    const yPred = amplitude * Math.sin(frequency * x[i] + phase) + offset;
    ssTotal += (y[i] - yMean) ** 2;
    ssResidual += (y[i] - yPred) ** 2;
  }
  const rSquared = 1 - (ssResidual / ssTotal);

  return { amplitude, frequency, phase, offset, rSquared };
}

export function solveLinearSystem(A: number[][], b: number[]): number[] | null {
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

export function estimateFrequency(x: number[], y: number[]): number {
  const n = y.length;
  let maxAmp = 0;
  let bestFreq = 1;

  const minFreq = 1 / (x[n - 1] - x[0]);
  const maxFreq = n / (2 * (x[n - 1] - x[0]));
  const numFreqs = Math.min(100, n);

  for (let fi = 0; fi < numFreqs; fi++) {
    const freq = minFreq + (maxFreq - minFreq) * fi / numFreqs;
    let sumSin = 0, sumCos = 0;
    
    for (let i = 0; i < n; i++) {
      const t = 2 * Math.PI * freq * x[i];
      sumSin += y[i] * Math.sin(t);
      sumCos += y[i] * Math.cos(t);
    }
    
    const amp = Math.sqrt(sumSin * sumSin + sumCos * sumCos);
    if (amp > maxAmp) {
      maxAmp = amp;
      bestFreq = freq;
    }
  }

  return bestFreq * 2 * Math.PI;
}

export function fitCurve(
  type: CurveFitType,
  x: number[],
  y: number[]
): CurveFitResult {
  switch (type) {
    case 'linear': {
      const result = leastSquaresLinear(x, y);
      return {
        type,
        parameters: [result.a, result.b],
        rSquared: result.rSquared,
        equation: `y = ${result.a.toFixed(4)}x + ${result.b.toFixed(4)}`,
      };
    }
    case 'quadratic': {
      const result = leastSquaresQuadratic(x, y);
      return {
        type,
        parameters: [result.a, result.b, result.c],
        rSquared: result.rSquared,
        equation: `y = ${result.a.toFixed(4)}x² + ${result.b.toFixed(4)}x + ${result.c.toFixed(4)}`,
      };
    }
    case 'exponential': {
      const result = leastSquaresExponential(x, y);
      return {
        type,
        parameters: [result.a, result.b],
        rSquared: result.rSquared,
        equation: `y = ${result.a.toFixed(4)}e^(${result.b.toFixed(4)}x)`,
      };
    }
    case 'sine': {
      const result = leastSquaresSine(x, y);
      return {
        type,
        parameters: [result.amplitude, result.frequency, result.phase, result.offset],
        rSquared: result.rSquared,
        equation: `y = ${result.amplitude.toFixed(4)}sin(${result.frequency.toFixed(4)}x + ${result.phase.toFixed(4)}) + ${result.offset.toFixed(4)}`,
      };
    }
    default:
      return { type, parameters: [], rSquared: 0, equation: '' };
  }
}

export const CurveFitting = {
  leastSquaresLinear,
  leastSquaresQuadratic,
  leastSquaresExponential,
  leastSquaresSine,
  solveLinearSystem,
  estimateFrequency,
  fitCurve,
};
