import { Vec3, vec3Distance } from '@/utils/math';
import { Atom } from '../molecule-parser/types';

export interface HBond {
  donorIndex: number;
  acceptorIndex: number;
  donorPos: Vec3;
  acceptorPos: Vec3;
  strength: number;
}

const MIN_DISTANCE = 2.5;
const MAX_DISTANCE = 3.5;

export function detectHBonds(
  atoms: Atom[],
  bonds: { atomIndex1: number; atomIndex2: number }[]
): HBond[] {
  const hBonds: HBond[] = [];

  const adjacency = new Map<number, number[]>();
  for (const bond of bonds) {
    if (!adjacency.has(bond.atomIndex1)) {
      adjacency.set(bond.atomIndex1, []);
    }
    if (!adjacency.has(bond.atomIndex2)) {
      adjacency.set(bond.atomIndex2, []);
    }
    adjacency.get(bond.atomIndex1)!.push(bond.atomIndex2);
    adjacency.get(bond.atomIndex2)!.push(bond.atomIndex1);
  }

  const nitrogenAtoms: number[] = [];
  const oxygenAtoms: number[] = [];

  for (let i = 0; i < atoms.length; i++) {
    if (atoms[i].element === 'N') {
      nitrogenAtoms.push(i);
    } else if (atoms[i].element === 'O') {
      oxygenAtoms.push(i);
    }
  }

  for (const ni of nitrogenAtoms) {
    const nAtom = atoms[ni];
    const nPos: Vec3 = [nAtom.x, nAtom.y, nAtom.z];
    const nResSeq = nAtom.residueSeq;
    const nChainId = nAtom.chainId;

    const neighbors = adjacency.get(ni);
    if (!neighbors) continue;

    const hasHBonded = neighbors.some(j => atoms[j].element === 'H');
    if (!hasHBonded) continue;

    for (const oi of oxygenAtoms) {
      const oAtom = atoms[oi];
      const oPos: Vec3 = [oAtom.x, oAtom.y, oAtom.z];

      if (oAtom.residueSeq === nResSeq && oAtom.chainId === nChainId) continue;

      const dist = vec3Distance(nPos, oPos);
      if (dist < MIN_DISTANCE || dist > MAX_DISTANCE) continue;

      const strength = 1.0 - (dist - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE);

      hBonds.push({
        donorIndex: ni,
        acceptorIndex: oi,
        donorPos: nPos,
        acceptorPos: oPos,
        strength: Math.max(0, Math.min(1, strength)),
      });
    }
  }

  return hBonds;
}
