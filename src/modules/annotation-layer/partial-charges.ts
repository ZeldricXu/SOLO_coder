import { Atom } from '../molecule-parser/types';

export interface PartialChargeLabel {
  atomIndex: number;
  position: [number, number, number];
  charge: number;
  element: string;
  chainId?: string;
}

export function computePartialChargeLabels(
  atoms: Atom[]
): PartialChargeLabel[] {
  const labels: PartialChargeLabel[] = [];

  for (let i = 0; i < atoms.length; i++) {
    const a = atoms[i];
    let charge = 0.0;

    const element = a.element;
    const neighbors: number[] = [];
    for (let j = 0; j < atoms.length; j++) {
      if (i === j) continue;
      const b = atoms[j];
      const dx = a.x - b.x;
      const dy = a.y - b.y;
      const dz = a.z - b.z;
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      const r1 = getCovalentRadiusEstimate(a.element);
      const r2 = getCovalentRadiusEstimate(b.element);
      if (dist < (r1 + r2) * 1.2) {
        neighbors.push(j);
      }
    }

    const electronegativities: Record<string, number> = {
      H: 2.20, Li: 0.98, Be: 1.57, B: 2.04, C: 2.55, N: 3.04, O: 3.44, F: 3.98,
      Na: 0.93, Mg: 1.31, Al: 1.61, Si: 1.90, P: 2.19, S: 2.58, Cl: 3.16,
      K: 0.82, Ca: 1.00, Fe: 1.83, Zn: 1.65, Cu: 1.90,
    };

    const en1 = electronegativities[element] ?? 2.5;

    for (const ni of neighbors) {
      const neighbor = atoms[ni];
      const en2 = electronegativities[neighbor.element] ?? 2.5;
      const delta = en2 - en1;
      const bondCharge = 0.1 * Math.tanh(delta / 2);
      charge += bondCharge;
    }

    if (element === 'O' && neighbors.length === 1 && atoms[neighbors[0]].element === 'H') {
      charge = -0.5;
    } else if (element === 'N' && neighbors.length >= 3) {
      charge = 0.1 * neighbors.filter(ni => atoms[ni].element === 'H').length;
    }

    labels.push({
      atomIndex: i,
      position: [a.x, a.y, a.z],
      charge: Math.round(charge * 100) / 100,
      element,
      chainId: a.chainId,
    });
  }

  return labels;
}

function getCovalentRadiusEstimate(element: string): number {
  const radii: Record<string, number> = {
    H: 0.31, C: 0.76, N: 0.71, O: 0.66, F: 0.57,
    P: 1.07, S: 1.05, Cl: 1.02, Fe: 1.32, Zn: 1.22, Cu: 1.32,
    Ca: 1.76, Mg: 1.41, Na: 1.66, K: 2.03,
  };
  return radii[element] ?? 1.5;
}
