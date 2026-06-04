import { Atom } from '../molecule-parser/types';
import { vec3Normalize, vec3Dot, vec3Distance } from '@/utils/math';
import type { Vec3 } from '@/utils/math';

export interface HBondNetworkEdge {
  donorAtomIndex: number;
  acceptorAtomIndex: number;
  distance: number;
  angle: number;
  donorElement: string;
  acceptorElement: string;
  isLigandInteraction: boolean;
  ligandSide: 'donor' | 'acceptor' | 'none';
}

export function isLigandAtom(atom: Atom): boolean {
  if (!atom.chainId) return false;
  const chainMatch = atom.chainId === 'L' || atom.chainId === 'LIG' || atom.chainId.startsWith('HET');
  const residueMatch = !!(atom.residue && (atom.residue === 'LIG' || atom.residue.startsWith('HET')));
  return chainMatch || residueMatch;
}

export function detectHBondNetwork(
  atoms: Atom[],
  bonds: { atomIndex1: number; atomIndex2: number }[]
): HBondNetworkEdge[] {
  const edges: HBondNetworkEdge[] = [];

  const donorElements = new Set(['N', 'O', 'S', 'F']);
  const acceptorElements = new Set(['N', 'O', 'S', 'F']);

  const bondedHydrogens = new Map<number, number[]>();
  for (const b of bonds) {
    const a1 = atoms[b.atomIndex1];
    const a2 = atoms[b.atomIndex2];
    if (a1?.element === 'H' && donorElements.has(a2?.element || '')) {
      if (!bondedHydrogens.has(b.atomIndex2)) bondedHydrogens.set(b.atomIndex2, []);
      bondedHydrogens.get(b.atomIndex2)!.push(b.atomIndex1);
    }
    if (a2?.element === 'H' && donorElements.has(a1?.element || '')) {
      if (!bondedHydrogens.has(b.atomIndex1)) bondedHydrogens.set(b.atomIndex1, []);
      bondedHydrogens.get(b.atomIndex1)!.push(b.atomIndex2);
    }
  }

  const atomMap = new Map<number, Atom>();
  for (let i = 0; i < atoms.length; i++) {
    atomMap.set(atoms[i].index ?? i, atoms[i]);
  }

  const maxDistance = 3.5;
  const minAngle = 120 * Math.PI / 180;

  for (let i = 0; i < atoms.length; i++) {
    const donor = atoms[i];
    if (!donorElements.has(donor.element)) continue;

    const hydrogens = bondedHydrogens.get(i) ?? [];
    for (let j = 0; j < atoms.length; j++) {
      if (i === j) continue;

      const acceptor = atoms[j];
      if (!acceptorElements.has(acceptor.element)) continue;

      const dist = vec3Distance(
        [donor.x, donor.y, donor.z],
        [acceptor.x, acceptor.y, acceptor.z]
      );

      if (dist > maxDistance) continue;

      let angle = Math.PI;
      if (hydrogens.length > 0) {
        for (const hIdx of hydrogens) {
          const hydrogen = atoms[hIdx];
          if (!hydrogen) continue;

          const dToH: Vec3 = [hydrogen.x - donor.x, hydrogen.y - donor.y, hydrogen.z - donor.z];
          const hToA: Vec3 = [acceptor.x - hydrogen.x, acceptor.y - hydrogen.y, acceptor.z - hydrogen.z];

          const n1 = vec3Normalize([0, 0, 0], dToH);
          const n2 = vec3Normalize([0, 0, 0], hToA);
          const dot = vec3Dot(n1, n2);
          const a = Math.acos(Math.max(-1, Math.min(1, dot)));
          if (a < angle) angle = a;
        }
      } else {
        const dToA: Vec3 = [acceptor.x - donor.x, acceptor.y - donor.y, acceptor.z - donor.z];
        angle = Math.acos(Math.max(-1, Math.min(1, vec3Dot(
          vec3Normalize([0, 0, 0], dToA),
          [0, 0, 1]
        ))));
      }

      if (angle < minAngle) continue;

      const isDonorLigand = isLigandAtom(donor);
      const isAcceptorLigand = isLigandAtom(acceptor);
      const isLigandInteraction = isDonorLigand !== isAcceptorLigand;
      const ligandSide = isDonorLigand ? 'donor' : isAcceptorLigand ? 'acceptor' : 'none';

      edges.push({
        donorAtomIndex: i,
        acceptorAtomIndex: j,
        distance: dist,
        angle: angle * 180 / Math.PI,
        donorElement: donor.element,
        acceptorElement: acceptor.element,
        isLigandInteraction,
        ligandSide,
      });
    }
  }

  return edges;
}

export function getLigandHBondInteractions(
  edges: HBondNetworkEdge[]
): HBondNetworkEdge[] {
  return edges.filter(e => e.isLigandInteraction);
}

export function getProteinHBondInteractions(
  edges: HBondNetworkEdge[]
): HBondNetworkEdge[] {
  return edges.filter(e => !e.isLigandInteraction);
}
