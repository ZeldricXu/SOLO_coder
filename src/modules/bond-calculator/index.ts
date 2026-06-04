import { Atom, Bond } from '../molecule-parser/types';
import { SpatialHash } from './spatial-hash';
import {
  getBondRadiusTable,
  getElementPairThreshold,
  getCovalentRadius,
  subscribeToBondRadiusTableChanges,
  getPairSpecificConfig,
  type BondRadiusTable,
} from './bond-radius-table';

export type { BondRadiusTable, PairThreshold, SpecialBondConfig } from './bond-radius-table';
export {
  getBondRadiusTable,
  setBondRadiusTable,
  reloadBondRadiusTable,
  loadBondRadiusTableFromJson,
  loadBondRadiusTableFromUrl,
  subscribeToBondRadiusTableChanges,
  getElementPairThreshold,
  getPairSpecificConfig,
  getCovalentRadius,
  validateBondRadiusTable,
} from './bond-radius-table';

const FALLBACK_TOLERANCE = 1.2;

function getMaxThresholdForAtoms(atoms: Atom[], table: BondRadiusTable): number {
  let maxThreshold = table.defaults.maxBondDistance;
  for (let i = 0; i < atoms.length; i++) {
    for (let j = i + 1; j < atoms.length; j++) {
      const { threshold } = getElementPairThreshold(atoms[i].element, atoms[j].element, table);
      if (threshold > maxThreshold) {
        maxThreshold = threshold;
      }
    }
  }
  return maxThreshold;
}

export function inferBonds(
  atoms: Atom[],
  tolerance: number = FALLBACK_TOLERANCE,
  table: BondRadiusTable = getBondRadiusTable()
): Bond[] {
  const bonds: Bond[] = [];

  if (atoms.length === 0) {
    return bonds;
  }

  const effectiveTolerance = tolerance ?? table.defaults.tolerance;
  const maxThreshold = getMaxThresholdForAtoms(atoms, table);

  const cellSize = maxThreshold * 2;
  const hash = new SpatialHash(cellSize);

  for (let i = 0; i < atoms.length; i++) {
    hash.insert(i, atoms[i].x, atoms[i].y, atoms[i].z);
  }

  for (let i = 0; i < atoms.length; i++) {
    const a1 = atoms[i];
    const neighbors = hash.query(a1.x, a1.y, a1.z, maxThreshold);

    for (const j of neighbors) {
      if (j <= i) {
        continue;
      }

      const a2 = atoms[j];
      const pairConfig = getPairSpecificConfig(a1.element, a2.element, table);

      let threshold: number;
      if (pairConfig) {
        threshold = pairConfig.distance * pairConfig.tolerance;
      } else {
        const r1 = getCovalentRadius(a1.element, table);
        const r2 = getCovalentRadius(a2.element, table);
        threshold = (r1 + r2) * effectiveTolerance;
      }

      const dx = a1.x - a2.x;
      const dy = a1.y - a2.y;
      const dz = a1.z - a2.z;
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

      if (dist < threshold) {
        const r1 = getCovalentRadius(a1.element, table);
        const r2 = getCovalentRadius(a2.element, table);
        const sumRadii = r1 + r2;
        let order = 1;
        if (dist < 1.1 * sumRadii) {
          order = 3;
        } else if (dist < 1.25 * sumRadii) {
          order = 2;
        }

        bonds.push({
          atomIndex1: i,
          atomIndex2: j,
          order,
        });
      }
    }
  }

  return bonds;
}

export function inferBondsWithMetadata(
  atoms: Atom[],
  tolerance: number = FALLBACK_TOLERANCE,
  table: BondRadiusTable = getBondRadiusTable()
): { bonds: Bond[]; pairTypes: Record<string, string> } {
  const bonds = inferBonds(atoms, tolerance, table);
  const pairTypes: Record<string, string> = {};

  for (const bond of bonds) {
    const a1 = atoms[bond.atomIndex1];
    const a2 = atoms[bond.atomIndex2];
    const pairConfig = getPairSpecificConfig(a1.element, a2.element, table);
    if (pairConfig?.description) {
      const key = `${bond.atomIndex1}-${bond.atomIndex2}`;
      pairTypes[key] = pairConfig.description;
    }
  }

  return { bonds, pairTypes };
}

export function getBondTableVersion(): string {
  return getBondRadiusTable().version;
}

export function onBondTableChanged(callback: (table: BondRadiusTable) => void): () => void {
  return subscribeToBondRadiusTableChanges(callback);
}

