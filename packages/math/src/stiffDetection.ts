import { StateVector, DerivativeFunction } from './integrators';

export interface StiffnessAnalysisResult {
  isStiff: boolean;
  maxEigenvalue: number;
  minEigenvalue: number;
  stiffnessRatio: number;
  recommendedIntegrator: 'explicit' | 'implicit' | 'bdf';
  recommendedTimeStep: number;
}

export function estimateJacobian(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  epsilon: number = 1e-8
): number[][] {
  const n = y.length;
  const J = new Array(n).fill(0).map(() => new Array(n).fill(0));
  const f0 = f(t, y);

  for (let j = 0; j < n; j++) {
    const yPerturbed = [...y];
    yPerturbed[j] += epsilon;
    const fPerturbed = f(t, yPerturbed);
    
    for (let i = 0; i < n; i++) {
      J[i][j] = (fPerturbed[i] - f0[i]) / epsilon;
    }
  }

  return J;
}

export function estimateEigenvalues(
  J: number[][]
): { maxReal: number; minReal: number; eigenvalues: number[] } {
  const n = J.length;
  const eigenvalues: number[] = new Array(n).fill(0);
  
  for (let i = 0; i < n; i++) {
    let rowSum = 0;
    for (let j = 0; j < n; j++) {
      rowSum += Math.abs(J[i][j]);
    }
    eigenvalues[i] = J[i][i] + Math.sign(J[i][i]) * (rowSum - Math.abs(J[i][i]));
  }

  let maxReal = -Infinity;
  let minReal = Infinity;
  for (let i = 0; i < n; i++) {
    maxReal = Math.max(maxReal, eigenvalues[i]);
    minReal = Math.min(minReal, eigenvalues[i]);
  }

  return { maxReal, minReal, eigenvalues };
}

export function powerIteration(
  J: number[][],
  tolerance: number = 1e-10,
  maxIterations: number = 1000
): { eigenvalue: number; eigenvector: number[] } {
  const n = J.length;
  let b = new Array(n).fill(1 / Math.sqrt(n));
  let eigenvalue = 0;

  for (let iter = 0; iter < maxIterations; iter++) {
    const bNew = new Array(n).fill(0);
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) {
        bNew[i] += J[i][j] * b[j];
      }
    }

    let norm = 0;
    for (let i = 0; i < n; i++) {
      norm += bNew[i] * bNew[i];
    }
    norm = Math.sqrt(norm);

    for (let i = 0; i < n; i++) {
      bNew[i] /= norm;
    }

    let newEigenvalue = 0;
    for (let i = 0; i < n; i++) {
      newEigenvalue += b[i] * bNew[i];
    }

    if (Math.abs(newEigenvalue - eigenvalue) < tolerance) {
      eigenvalue = newEigenvalue;
      b = bNew;
      break;
    }

    eigenvalue = newEigenvalue;
    b = bNew;
  }

  return { eigenvalue, eigenvector: b };
}

export function analyzeStiffness(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  currentDt: number,
  tolerance: number = 1e-6
): StiffnessAnalysisResult {
  const J = estimateJacobian(f, t, y);
  const { maxReal, minReal } = estimateEigenvalues(J);
  
  const maxAbsEigenvalue = Math.max(Math.abs(maxReal), Math.abs(minReal));
  const minAbsEigenvalue = Math.max(Math.min(Math.abs(maxReal), Math.abs(minReal)), 1e-15);
  const stiffnessRatio = maxAbsEigenvalue / minAbsEigenvalue;
  
  const explicitStableDt = 2 / maxAbsEigenvalue;
  
  let isStiff = false;
  let recommendedIntegrator: 'explicit' | 'implicit' | 'bdf' = 'explicit';
  let recommendedTimeStep = currentDt;

  if (stiffnessRatio > 1000 || explicitStableDt < currentDt * 0.01) {
    isStiff = true;
    if (stiffnessRatio > 1e6) {
      recommendedIntegrator = 'bdf';
    } else {
      recommendedIntegrator = 'implicit';
    }
    recommendedTimeStep = Math.min(currentDt * 10, 0.1);
  } else {
    recommendedTimeStep = Math.min(currentDt * 1.1, explicitStableDt * 0.5);
  }

  return {
    isStiff,
    maxEigenvalue: maxReal,
    minEigenvalue: minReal,
    stiffnessRatio,
    recommendedIntegrator,
    recommendedTimeStep,
  };
}

export function monitorStiffness(
  f: DerivativeFunction,
  t: number,
  y: StateVector,
  currentDt: number,
  history: StiffnessAnalysisResult[] = [],
  windowSize: number = 5
): { analysis: StiffnessAnalysisResult; shouldSwitch: boolean } {
  const analysis = analyzeStiffness(f, t, y, currentDt);
  
  history.push(analysis);
  if (history.length > windowSize) {
    history.shift();
  }

  const stiffCount = history.filter(h => h.isStiff).length;
  const shouldSwitch = stiffCount >= Math.ceil(windowSize * 0.6);

  return { analysis, shouldSwitch };
}

export const StiffDetection = {
  estimateJacobian,
  estimateEigenvalues,
  powerIteration,
  analyzeStiffness,
  monitorStiffness,
};
