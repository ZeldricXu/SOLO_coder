import { Atom } from '../molecule-parser/types';

export interface BFactorSphere {
  atomIndex: number;
  position: [number, number, number];
  bFactor: number;
  normalizedValue: number;
  color: [number, number, number];
  radius: number;
  chainId?: string;
}

export function bFactorToColor(bFactor: number): [number, number, number] {
  const t = Math.max(0, Math.min(1, bFactor / 100));
  if (t < 0.25) {
    const s = t * 4;
    return [
      0 * (1 - s) + 0 * s,
      0 * (1 - s) + 1 * s,
      1 * (1 - s) + 1 * s,
    ];
  } else if (t < 0.5) {
    const s = (t - 0.25) * 4;
    return [
      0 * (1 - s) + 0 * s,
      1 * (1 - s) + 1 * s,
      1 * (1 - s) + 0 * s,
    ];
  } else if (t < 0.75) {
    const s = (t - 0.5) * 4;
    return [
      0 * (1 - s) + 1 * s,
      1 * (1 - s) + 1 * s,
      0 * (1 - s) + 0 * s,
    ];
  } else {
    const s = (t - 0.75) * 4;
    return [
      1 * (1 - s) + 1 * s,
      1 * (1 - s) + 0 * s,
      0 * (1 - s) + 0 * s,
    ];
  }
}

export function computeBFactorHeatmap(atoms: Atom[]): BFactorSphere[] {
  const spheres: BFactorSphere[] = [];

  let minBFactor = Infinity;
  let maxBFactor = -Infinity;

  for (const a of atoms) {
    const bf = a.bFactor ?? 20.0;
    minBFactor = Math.min(minBFactor, bf);
    maxBFactor = Math.max(maxBFactor, bf);
  }

  const range = maxBFactor - minBFactor || 1;

  for (let i = 0; i < atoms.length; i++) {
    const a = atoms[i];
    const bFactor = a.bFactor ?? 20.0;
    const normalizedValue = (bFactor - minBFactor) / range;
    const color = bFactorToColor(bFactor);
    const baseRadius = a.vdWRadius;
    const radius = baseRadius * (0.7 + 0.6 * normalizedValue);

    spheres.push({
      atomIndex: i,
      position: [a.x, a.y, a.z],
      bFactor,
      normalizedValue,
      color,
      radius,
      chainId: a.chainId,
    });
  }

  return spheres;
}

export function getBFactorStats(atoms: Atom[]): {
  min: number;
  max: number;
  mean: number;
  median: number;
} {
  if (atoms.length === 0) {
    return { min: 0, max: 0, mean: 0, median: 0 };
  }

  const values = atoms.map(a => a.bFactor ?? 20.0).sort((a, b) => a - b);
  const sum = values.reduce((a, b) => a + b, 0);

  const min = values[0];
  const max = values[values.length - 1];
  const mean = sum / values.length;
  const mid = Math.floor(values.length / 2);
  const median = values.length % 2 === 0
    ? (values[mid - 1] + values[mid]) / 2
    : values[mid];

  return { min, max, mean, median };
}
