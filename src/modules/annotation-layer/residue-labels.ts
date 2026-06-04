import { Vec3 } from '@/utils/math';
import { Atom } from '../molecule-parser/types';

export interface ResidueLabel {
  position: Vec3;
  text: string;
  chainId: string;
  atomIndex: number;
}

export function computeResidueLabels(atoms: Atom[]): ResidueLabel[] {
  const labels: ResidueLabel[] = [];

  for (let i = 0; i < atoms.length; i++) {
    const atom = atoms[i];
    if (atom.isCA) {
      labels.push({
        position: [atom.x, atom.y, atom.z],
        text: `${atom.residue ?? ''}${atom.residueSeq ?? ''}`,
        chainId: atom.chainId ?? '',
        atomIndex: i,
      });
    }
  }

  return labels;
}
