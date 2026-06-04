import type { Atom } from '@/modules/molecule-parser/types';

export function makeAtom(overrides: Partial<Atom> & { index: number }): Atom {
  return {
    element: 'C',
    x: 0, y: 0, z: 0,
    vdWRadius: 1.7,
    color: [0.56, 0.56, 0.56],
    ...overrides,
  };
}

export function makeAtomGrid(count: number, spacing: number = 2.0): Atom[] {
  const side = Math.ceil(Math.cbrt(count));
  const atoms: Atom[] = [];
  let idx = 0;
  for (let x = 0; x < side && idx < count; x++) {
    for (let y = 0; y < side && idx < count; y++) {
      for (let z = 0; z < side && idx < count; z++) {
        atoms.push(makeAtom({
          index: idx,
          element: idx % 3 === 0 ? 'N' : idx % 3 === 1 ? 'C' : 'O',
          x: x * spacing,
          y: y * spacing,
          z: z * spacing,
        }));
        idx++;
      }
    }
  }
  return atoms;
}

export function makeLinearAtoms(length: number, spacing: number = 1.54): Atom[] {
  const atoms: Atom[] = [];
  for (let i = 0; i < length; i++) {
    atoms.push(makeAtom({
      index: i,
      element: 'C',
      x: i * spacing,
      y: 0,
      z: 0,
    }));
  }
  return atoms;
}

export function makeCollinearAtoms(): Atom[] {
  return [
    makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
    makeAtom({ index: 1, element: 'C', x: 1.54, y: 0, z: 0 }),
    makeAtom({ index: 2, element: 'C', x: 3.08, y: 0, z: 0 }),
  ];
}

export function makeTetrahedralAtoms(): Atom[] {
  return [
    makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
    makeAtom({ index: 1, element: 'H', x: 1.09, y: 0, z: 0 }),
    makeAtom({ index: 2, element: 'H', x: -0.363, y: 1.027, z: 0 }),
    makeAtom({ index: 3, element: 'H', x: -0.363, y: -0.514, z: 0.890 }),
    makeAtom({ index: 4, element: 'H', x: -0.363, y: -0.514, z: -0.890 }),
  ];
}

export function makeWaterMolecule(offsetX = 0, offsetZ = 0): Atom[] {
  return [
    makeAtom({ index: 0, element: 'O', x: 0 + offsetX, y: 0, z: 0 + offsetZ, vdWRadius: 1.52, color: [1, 0.05, 0.05] }),
    makeAtom({ index: 1, element: 'H', x: 0.757 + offsetX, y: 0.586, z: 0 + offsetZ, vdWRadius: 1.2, color: [1, 1, 1] }),
    makeAtom({ index: 2, element: 'H', x: -0.757 + offsetX, y: 0.586, z: 0 + offsetZ, vdWRadius: 1.2, color: [1, 1, 1] }),
  ];
}

export function makeProteinBackbone(chainId: string, resCount: number, startX = 0): Atom[] {
  const atoms: Atom[] = [];
  let idx = 0;
  const residues = ['ALA', 'GLY', 'VAL', 'LEU', 'SER'];
  for (let r = 0; r < resCount; r++) {
    const x = startX + r * 3.8;
    atoms.push(makeAtom({ index: idx++, element: 'N', x, y: 0, z: 0, residue: residues[r % 5], residueSeq: r + 1, chainId, isBackbone: true }));
    atoms.push(makeAtom({ index: idx++, element: 'C', x: x + 1.0, y: 0.5, z: 0, residue: residues[r % 5], residueSeq: r + 1, chainId, isCA: true, isBackbone: true }));
    atoms.push(makeAtom({ index: idx++, element: 'C', x: x + 2.0, y: 0, z: 0.3, residue: residues[r % 5], residueSeq: r + 1, chainId, isBackbone: true }));
    atoms.push(makeAtom({ index: idx++, element: 'O', x: x + 2.0, y: -1.0, z: 0.3, residue: residues[r % 5], residueSeq: r + 1, chainId, isBackbone: true }));
  }
  return atoms;
}

export const PDB_WATER = `HEADER    TEST
TITLE     WATER MOLECULE
ATOM      1  O   HOH A   1       0.000   0.000   0.000  1.00 20.00           O
ATOM      2  H1  HOH A   1       0.757   0.586   0.000  1.00 20.00           H
ATOM      3  H2  HOH A   1      -0.757   0.586   0.000  1.00 20.00           H
CONECT    1    2    3
END
`;

export const PDB_MULTI_MODEL = `HEADER    NMR ENSEMBLE
MODEL        1
ATOM      1  CA  ALA A   1       1.000   0.000   0.000  1.00  0.00           C
ATOM      2  N   ALA A   1       0.000   1.000   0.000  1.00  0.00           N
ENDMDL
MODEL        2
ATOM      1  CA  ALA A   1       1.100   0.100   0.000  1.00  0.00           C
ATOM      2  N   ALA A   1       0.100   1.100   0.000  1.00  0.00           N
ENDMDL
END
`;

export const PDB_PROTEIN_FRAGMENT = `HEADER    PROTEIN FRAGMENT
ATOM      1  N   ALA A   1       0.000   0.000   0.000  1.00 10.00           N
ATOM      2  CA  ALA A   1       1.000   0.500   0.000  1.00 10.00           C
ATOM      3  C   ALA A   1       2.000   0.000   0.300  1.00 10.00           C
ATOM      4  O   ALA A   1       2.000  -1.000   0.300  1.00 10.00           O
ATOM      5  N   GLY A   2       3.000   0.500   0.000  1.00 10.00           N
ATOM      6  CA  GLY A   2       4.000   0.000   0.300  1.00 10.00           C
CONECT    1    2
CONECT    2    3
CONECT    3    4
CONECT    3    5
CONECT    5    6
END
`;

export const PDB_HETATM = `HEADER    HETATM TEST
HETATM    1 FE   HEM A 101       0.000   0.000   0.000  1.00 15.00          FE
ATOM      2  N   ALA A   1       2.000   0.000   0.000  1.00 10.00           N
END
`;

export const PDB_MALFORMED_MISSING_COLS = `ATOM   INVA
`;

export const PDB_EMPTY = '';

export const SDF_WATER = `
water
     RDKit          3D

  3  2  0  0  0  0  0  0  0  0999 V2000
    0.0000    0.0000    0.0000 O   0  0  0  0  0  0  0  0  0  0  0  0
    0.7570    0.5860    0.0000 H   0  0  0  0  0  0  0  0  0  0  0  0
   -0.7570    0.5860    0.0000 H   0  0  0  0  0  0  0  0  0  0  0  0
  1  2  1  0
  1  3  1  0
M  END
$$$$
`;

export const SDF_BENZENE = `
benzene
     RDKit          3D

  6  6  0  0  0  0  0  0  0  0999 V2000
    1.4000    0.0000    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
    0.7000    1.2124    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
   -0.7000    1.2124    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
   -1.4000    0.0000    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
   -0.7000   -1.2124    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
    0.7000   -1.2124    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0
  1  2  2  0
  2  3  1  0
  3  4  2  0
  4  5  1  0
  5  6  2  0
  6  1  1  0
M  END
$$$$
`;

export const XYZ_WATER = `3
Water molecule
O     0.000000     0.000000     0.000000
H     0.757000     0.586000     0.000000
H    -0.757000     0.586000     0.000000
`;

export const XYZ_METHANE = `5
Methane
C     0.000000     0.000000     0.000000
H     1.090000     0.000000     0.000000
H    -0.363000     1.027000     0.000000
H    -0.363000    -0.514000     0.890000
H    -0.363000    -0.514000    -0.890000
`;

export const XYZ_EMPTY = `0
Empty
`;

export const XYZ_INVALID = `abc
Invalid
C 0 0 0
`;
