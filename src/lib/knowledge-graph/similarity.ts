import type { DocumentVector, SimilarityResult, SparseVector } from './types';

export function dotProduct(vec1: SparseVector, vec2: SparseVector): number {
  if (vec1.dimension !== vec2.dimension) {
    throw new Error('Vectors must have the same dimension');
  }

  let result = 0;
  let i = 0;
  let j = 0;

  while (i < vec1.indices.length && j < vec2.indices.length) {
    const idx1 = vec1.indices[i];
    const idx2 = vec2.indices[j];

    if (idx1 === idx2) {
      result += vec1.values[i] * vec2.values[j];
      i++;
      j++;
    } else if (idx1 < idx2) {
      i++;
    } else {
      j++;
    }
  }

  return result;
}

export function dotProductDense(vec1: number[], vec2: number[]): number {
  if (vec1.length !== vec2.length) {
    throw new Error('Vectors must have the same length');
  }

  return vec1.reduce((sum, val, idx) => sum + val * vec2[idx], 0);
}

export function normalizeVector(vec: SparseVector): SparseVector {
  const magnitude = vectorMagnitude(vec);
  
  if (magnitude === 0) {
    return {
      indices: [],
      values: [],
      dimension: vec.dimension,
    };
  }

  return {
    indices: vec.indices,
    values: vec.values.map((v) => v / magnitude),
    dimension: vec.dimension,
  };
}

export function normalizeVectorDense(vec: number[]): number[] {
  const magnitude = vectorMagnitudeDense(vec);
  
  if (magnitude === 0) {
    return new Array(vec.length).fill(0);
  }

  return vec.map((v) => v / magnitude);
}

export function vectorMagnitude(vec: SparseVector): number {
  const sumOfSquares = vec.values.reduce((sum, v) => sum + v * v, 0);
  return Math.sqrt(sumOfSquares);
}

export function vectorMagnitudeDense(vec: number[]): number {
  const sumOfSquares = vec.reduce((sum, v) => sum + v * v, 0);
  return Math.sqrt(sumOfSquares);
}

export function cosineSimilarity(vec1: SparseVector, vec2: SparseVector): number {
  const dot = dotProduct(vec1, vec2);
  const mag1 = vectorMagnitude(vec1);
  const mag2 = vectorMagnitude(vec2);

  if (mag1 === 0 || mag2 === 0) {
    return 0;
  }

  return dot / (mag1 * mag2);
}

export function cosineSimilarityDense(vec1: number[], vec2: number[]): number {
  const dot = dotProductDense(vec1, vec2);
  const mag1 = vectorMagnitudeDense(vec1);
  const mag2 = vectorMagnitudeDense(vec2);

  if (mag1 === 0 || mag2 === 0) {
    return 0;
  }

  return dot / (mag1 * mag2);
}

export function euclideanDistance(vec1: SparseVector, vec2: SparseVector): number {
  if (vec1.dimension !== vec2.dimension) {
    throw new Error('Vectors must have the same dimension');
  }

  let sumOfSquares = 0;
  let i = 0;
  let j = 0;

  while (i < vec1.indices.length || j < vec2.indices.length) {
    const idx1 = i < vec1.indices.length ? vec1.indices[i] : Infinity;
    const idx2 = j < vec2.indices.length ? vec2.indices[j] : Infinity;

    if (idx1 === idx2) {
      const diff = vec1.values[i] - vec2.values[j];
      sumOfSquares += diff * diff;
      i++;
      j++;
    } else if (idx1 < idx2) {
      sumOfSquares += vec1.values[i] * vec1.values[i];
      i++;
    } else {
      sumOfSquares += vec2.values[j] * vec2.values[j];
      j++;
    }
  }

  return Math.sqrt(sumOfSquares);
}

export function euclideanDistanceDense(vec1: number[], vec2: number[]): number {
  if (vec1.length !== vec2.length) {
    throw new Error('Vectors must have the same length');
  }

  const sumOfSquares = vec1.reduce((sum, val, idx) => {
    const diff = val - vec2[idx];
    return sum + diff * diff;
  }, 0);

  return Math.sqrt(sumOfSquares);
}

export function manhattanDistance(vec1: SparseVector, vec2: SparseVector): number {
  if (vec1.dimension !== vec2.dimension) {
    throw new Error('Vectors must have the same dimension');
  }

  let sum = 0;
  let i = 0;
  let j = 0;

  while (i < vec1.indices.length || j < vec2.indices.length) {
    const idx1 = i < vec1.indices.length ? vec1.indices[i] : Infinity;
    const idx2 = j < vec2.indices.length ? vec2.indices[j] : Infinity;

    if (idx1 === idx2) {
      sum += Math.abs(vec1.values[i] - vec2.values[j]);
      i++;
      j++;
    } else if (idx1 < idx2) {
      sum += Math.abs(vec1.values[i]);
      i++;
    } else {
      sum += Math.abs(vec2.values[j]);
      j++;
    }
  }

  return sum;
}

export function jaccardSimilarity(set1: string[], set2: string[]): number {
  const a = new Set(set1);
  const b = new Set(set2);
  
  const intersection = new Set([...a].filter((x) => b.has(x)));
  const union = new Set([...a, ...b]);

  if (union.size === 0) return 0;
  
  return intersection.size / union.size;
}

export function findMostSimilar(
  targetVector: DocumentVector,
  allVectors: DocumentVector[],
  topN = 10,
  threshold = 0
): SimilarityResult[] {
  const similarities: SimilarityResult[] = [];

  for (const vec of allVectors) {
    if (vec.documentId === targetVector.documentId) continue;

    const similarity = cosineSimilarity(targetVector.vector, vec.vector);
    
    if (similarity >= threshold) {
      similarities.push({
        documentId: vec.documentId,
        similarity,
        rank: 0,
      });
    }
  }

  similarities.sort((a, b) => b.similarity - a.similarity);
  
  return similarities
    .slice(0, topN)
    .map((item, index) => ({
      ...item,
      rank: index + 1,
    }));
}

export function findMostSimilarWithVectors(
  targetVector: SparseVector,
  vectors: Array<{ documentId: string; vector: SparseVector }>,
  topN = 10,
  threshold = 0
): SimilarityResult[] {
  const similarities: SimilarityResult[] = [];

  for (const { documentId, vector } of vectors) {
    const similarity = cosineSimilarity(targetVector, vector);
    
    if (similarity >= threshold) {
      similarities.push({
        documentId,
        similarity,
        rank: 0,
      });
    }
  }

  similarities.sort((a, b) => b.similarity - a.similarity);
  
  return similarities
    .slice(0, topN)
    .map((item, index) => ({
      ...item,
      rank: index + 1,
    }));
}

export function pearsonCorrelation(vec1: number[], vec2: number[]): number {
  if (vec1.length !== vec2.length) {
    throw new Error('Vectors must have the same length');
  }

  const n = vec1.length;
  const mean1 = vec1.reduce((sum, v) => sum + v, 0) / n;
  const mean2 = vec2.reduce((sum, v) => sum + v, 0) / n;

  let numerator = 0;
  let denom1 = 0;
  let denom2 = 0;

  for (let i = 0; i < n; i++) {
    const diff1 = vec1[i] - mean1;
    const diff2 = vec2[i] - mean2;
    numerator += diff1 * diff2;
    denom1 += diff1 * diff1;
    denom2 += diff2 * diff2;
  }

  const denominator = Math.sqrt(denom1) * Math.sqrt(denom2);
  
  if (denominator === 0) return 0;
  
  return numerator / denominator;
}

export function sparseToDense(vec: SparseVector): number[] {
  const dense = new Array(vec.dimension).fill(0);
  for (let i = 0; i < vec.indices.length; i++) {
    dense[vec.indices[i]] = vec.values[i];
  }
  return dense;
}

export function denseToSparse(vec: number[]): SparseVector {
  const indices: number[] = [];
  const values: number[] = [];

  for (let i = 0; i < vec.length; i++) {
    if (vec[i] !== 0) {
      indices.push(i);
      values.push(vec[i]);
    }
  }

  return {
    indices,
    values,
    dimension: vec.length,
  };
}
