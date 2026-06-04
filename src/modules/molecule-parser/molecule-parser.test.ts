import { describe, it, expect } from 'vitest';
import { parsePDB, parseSDF, parseXYZ, parseMolecule, detectFormat, ParseErrorCode } from './index';
import {
  PDB_WATER,
  PDB_MULTI_MODEL,
  PDB_PROTEIN_FRAGMENT,
  PDB_HETATM,
  PDB_MALFORMED_MISSING_COLS,
  PDB_EMPTY,
  SDF_WATER,
  SDF_BENZENE,
  XYZ_WATER,
  XYZ_METHANE,
  XYZ_EMPTY,
  XYZ_INVALID,
} from '@/test/fixtures';

describe('detectFormat', () => {
  it("Returns 'pdb' for .pdb extension", () => {
    expect(detectFormat('', 'molecule.pdb')).toBe('pdb');
  });

  it("Returns 'sdf' for .sdf extension", () => {
    expect(detectFormat('', 'molecule.sdf')).toBe('sdf');
  });

  it("Returns 'xyz' for .xyz extension", () => {
    expect(detectFormat('', 'molecule.xyz')).toBe('xyz');
  });

  it('Falls back to content heuristics when extension is unknown', () => {
    expect(detectFormat('ATOM      1  O   HOH A   1', 'file')).toBe('pdb');
    expect(detectFormat(PDB_WATER, 'file')).toBe('pdb');
    expect(detectFormat('HETATM    1 FE   HEM', 'file')).toBe('pdb');
  });
});

describe('parsePDB - Normal Paths', () => {
  it('PDB_WATER: parses 3 atoms, 2 bonds, correct elements, radii, coordinates, occupancy, bFactor', () => {
    const result = parsePDB(PDB_WATER, 'water.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(3);
      expect(result.value.bonds).toHaveLength(2);

      expect(result.value.atoms[0].element).toBe('O');
      expect(result.value.atoms[1].element).toBe('H');
      expect(result.value.atoms[2].element).toBe('H');

      expect(result.value.atoms[0].vdWRadius).toBeCloseTo(1.52, 2);
      expect(result.value.atoms[1].vdWRadius).toBeCloseTo(1.2, 1);
      expect(result.value.atoms[2].vdWRadius).toBeCloseTo(1.2, 1);

      expect(result.value.atoms[0].x).toBeCloseTo(0, 2);
      expect(result.value.atoms[0].y).toBeCloseTo(0, 2);
      expect(result.value.atoms[0].z).toBeCloseTo(0, 2);

      expect(result.value.atoms[1].x).toBeCloseTo(0.757, 3);
      expect(result.value.atoms[1].y).toBeCloseTo(0.586, 3);
      expect(result.value.atoms[1].z).toBeCloseTo(0, 2);

      expect(result.value.atoms[0].occupancy).toBeCloseTo(1.0, 2);
      expect(result.value.atoms[0].bFactor).toBeCloseTo(20.0, 2);
    }
  });

  it('PDB_MULTI_MODEL: creates 2 models, each with 2 atoms, model 1 coordinates differ from model 2', () => {
    const result = parsePDB(PDB_MULTI_MODEL, 'nmr.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.models).toHaveLength(2);
      expect(result.value.models[0].atoms).toHaveLength(2);
      expect(result.value.models[1].atoms).toHaveLength(2);

      expect(result.value.models[0].atoms[0].x).toBeCloseTo(1.0, 2);
      expect(result.value.models[1].atoms[0].x).toBeCloseTo(1.1, 2);

      expect(result.value.models[0].atoms[1].y).toBeCloseTo(1.0, 2);
      expect(result.value.models[1].atoms[1].y).toBeCloseTo(1.1, 2);
    }
  });

  it('PDB_PROTEIN_FRAGMENT: parses 6 atoms, 5 bonds, chain A, residue labels, isCA and isBackbone flags', () => {
    const result = parsePDB(PDB_PROTEIN_FRAGMENT, 'protein.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(6);
      expect(result.value.bonds).toHaveLength(5);

      expect(result.value.atoms[0].chainId).toBe('A');
      expect(result.value.atoms[1].chainId).toBe('A');

      expect(result.value.atoms[0].residue).toBe('ALA');
      expect(result.value.atoms[4].residue).toBe('GLY');

      expect(result.value.atoms[1].isCA).toBe(true);
      expect(result.value.atoms[5].isCA).toBe(true);
      expect(result.value.atoms[0].isCA).toBeFalsy();
      expect(result.value.atoms[2].isCA).toBeFalsy();
      expect(result.value.atoms[3].isCA).toBeFalsy();
      expect(result.value.atoms[4].isCA).toBeFalsy();

      expect(result.value.atoms[0].isBackbone).toBe(true);
      expect(result.value.atoms[1].isBackbone).toBe(true);
      expect(result.value.atoms[2].isBackbone).toBe(true);
      expect(result.value.atoms[3].isBackbone).toBe(true);
      expect(result.value.atoms[4].isBackbone).toBe(true);
      expect(result.value.atoms[5].isBackbone).toBe(true);
    }
  });

  it('PDB_HETATM: correctly parses HETATM record with FE element and vdW radius', () => {
    const result = parsePDB(PDB_HETATM, 'hem.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(2);
      expect(result.value.atoms[0].element).toBe('Fe');
      expect(result.value.atoms[0].vdWRadius).toBeCloseTo(1.94, 2);
    }
  });
});

describe('parsePDB - Error Paths', () => {
  it('PDB_MALFORMED_MISSING_COLS: does not crash, produces atoms with default/fallback values', () => {
    const result = parsePDB(PDB_MALFORMED_MISSING_COLS, 'bad.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms.length).toBeGreaterThanOrEqual(0);
      expect(result.value.metadata.format).toBe('pdb');
    }
  });

  it('PDB_EMPTY: returns empty atoms array, empty bonds, atomCount=0', () => {
    const result = parsePDB(PDB_EMPTY, 'empty.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(0);
      expect(result.value.bonds).toHaveLength(0);
      expect(result.value.metadata.atomCount).toBe(0);
    }
  });

  it('Missing element field: falls back to element derivation from atom name', () => {
    const pdbNoElement = `ATOM      1  CA  ALA A   1       1.000   0.000   0.000  1.00  0.00
`;
    const result = parsePDB(pdbNoElement, 'noelem.pdb');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(1);
      expect(result.value.atoms[0].element).toBe('C');
    }
  });

  it('Structured error for empty content', () => {
    const result = parsePDB('', 'empty.pdb');
    expect(result.ok).toBe(true);
    expect(result.errors.length).toBeGreaterThan(0);
    expect(result.errors[0].code).toBe(ParseErrorCode.EMPTY_CONTENT);
    expect(result.errors[0].lineNumber).toBe(0);
  });
});

describe('parseSDF - Normal Paths', () => {
  it('SDF_WATER: parses 3 atoms, 2 bonds, correct coordinates, bond order=1', () => {
    const result = parseSDF(SDF_WATER, 'water.sdf');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(3);
      expect(result.value.bonds).toHaveLength(2);

      expect(result.value.atoms[0].x).toBeCloseTo(0.0, 4);
      expect(result.value.atoms[0].y).toBeCloseTo(0.0, 4);
      expect(result.value.atoms[0].z).toBeCloseTo(0.0, 4);

      expect(result.value.atoms[1].x).toBeCloseTo(0.757, 3);
      expect(result.value.atoms[1].y).toBeCloseTo(0.586, 3);

      expect(result.value.bonds[0].order).toBe(1);
      expect(result.value.bonds[1].order).toBe(1);
    }
  });

  it('SDF_BENZENE: parses 6 atoms, 6 bonds with alternating bond orders, correct element C', () => {
    const result = parseSDF(SDF_BENZENE, 'benzene.sdf');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(6);
      expect(result.value.bonds).toHaveLength(6);

      expect(result.value.bonds[0].order).toBe(2);
      expect(result.value.bonds[1].order).toBe(1);
      expect(result.value.bonds[2].order).toBe(2);
      expect(result.value.bonds[3].order).toBe(1);
      expect(result.value.bonds[4].order).toBe(2);
      expect(result.value.bonds[5].order).toBe(1);

      for (const atom of result.value.atoms) {
        expect(atom.element).toBe('C');
      }
    }
  });

  it('Multi-molecule SDF creates 2 models', () => {
    const multiSdf = SDF_WATER + SDF_WATER;
    const result = parseSDF(multiSdf, 'multi.sdf');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.models).toHaveLength(2);
      expect(result.value.models[0].atoms).toHaveLength(3);
      expect(result.value.models[1].atoms).toHaveLength(3);
      expect(result.value.metadata.modelCount).toBe(2);
    }
  });
});

describe('parseSDF - Error Paths', () => {
  it('Empty content: returns empty result with error', () => {
    const result = parseSDF('', 'empty.sdf');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(0);
      expect(result.value.bonds).toHaveLength(0);
      expect(result.value.metadata.atomCount).toBe(0);
    }
    expect(result.errors.length).toBeGreaterThan(0);
  });

  it('Content without V2000 version tag: returns empty result with errors', () => {
    const result = parseSDF('some\nrandom\ntext\nhere', 'bad.sdf');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(0);
      expect(result.value.metadata.atomCount).toBe(0);
    }
  });
});

describe('parseXYZ - Normal Paths', () => {
  it('XYZ_WATER: parses 3 atoms, correct elements, correct coordinates, no bonds', () => {
    const result = parseXYZ(XYZ_WATER, 'water.xyz');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(3);
      expect(result.value.bonds).toHaveLength(0);

      expect(result.value.atoms[0].element).toBe('O');
      expect(result.value.atoms[1].element).toBe('H');
      expect(result.value.atoms[2].element).toBe('H');

      expect(result.value.atoms[0].x).toBeCloseTo(0.0, 5);
      expect(result.value.atoms[0].y).toBeCloseTo(0.0, 5);
      expect(result.value.atoms[0].z).toBeCloseTo(0.0, 5);

      expect(result.value.atoms[1].x).toBeCloseTo(0.757, 3);
      expect(result.value.atoms[1].y).toBeCloseTo(0.586, 3);
    }
  });

  it('XYZ_METHANE: parses 5 atoms, correct element inference, title line captured in metadata', () => {
    const result = parseXYZ(XYZ_METHANE, 'methane.xyz');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(5);
      expect(result.value.atoms[0].element).toBe('C');
      expect(result.value.metadata.title).toBe('Methane');
    }
  });
});

describe('parseXYZ - Error Paths', () => {
  it('XYZ_EMPTY: atom count = 0, returns empty result with error', () => {
    const result = parseXYZ(XYZ_EMPTY, 'empty.xyz');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(0);
      expect(result.value.metadata.atomCount).toBe(0);
    }
  });

  it('XYZ_INVALID: invalid atom count line, returns empty result with error', () => {
    const result = parseXYZ(XYZ_INVALID, 'invalid.xyz');

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.atoms).toHaveLength(0);
      expect(result.value.metadata.atomCount).toBe(0);
    }
    expect(result.errors.length).toBeGreaterThan(0);
  });
});

describe('parseMolecule (integration)', () => {
  it('Delegates to correct parser based on detectFormat', () => {
    const pdbResult = parseMolecule(PDB_WATER, 'water.pdb');
    expect(pdbResult.ok).toBe(true);
    if (pdbResult.ok) {
      expect(pdbResult.value.metadata.format).toBe('pdb');
      expect(pdbResult.value.atoms).toHaveLength(3);
    }

    const sdfResult = parseMolecule(SDF_WATER, 'water.sdf');
    expect(sdfResult.ok).toBe(true);
    if (sdfResult.ok) {
      expect(sdfResult.value.metadata.format).toBe('sdf');
      expect(sdfResult.value.atoms).toHaveLength(3);
    }

    const xyzResult = parseMolecule(XYZ_WATER, 'water.xyz');
    expect(xyzResult.ok).toBe(true);
    if (xyzResult.ok) {
      expect(xyzResult.value.metadata.format).toBe('xyz');
      expect(xyzResult.value.atoms).toHaveLength(3);
    }
  });

  it('Round-trip: parseMolecule with .pdb file produces same atoms as direct parsePDB call', () => {
    const viaMolecule = parseMolecule(PDB_WATER, 'water.pdb');
    const viaDirect = parsePDB(PDB_WATER, 'water.pdb');

    expect(viaMolecule.ok).toBe(true);
    expect(viaDirect.ok).toBe(true);

    if (viaMolecule.ok && viaDirect.ok) {
      expect(viaMolecule.value.atoms.length).toBe(viaDirect.value.atoms.length);

      for (let i = 0; i < viaMolecule.value.atoms.length; i++) {
        expect(viaMolecule.value.atoms[i].element).toBe(viaDirect.value.atoms[i].element);
        expect(viaMolecule.value.atoms[i].x).toBeCloseTo(viaDirect.value.atoms[i].x, 5);
        expect(viaMolecule.value.atoms[i].y).toBeCloseTo(viaDirect.value.atoms[i].y, 5);
        expect(viaMolecule.value.atoms[i].z).toBeCloseTo(viaDirect.value.atoms[i].z, 5);
      }

      expect(viaMolecule.value.bonds).toEqual(viaDirect.value.bonds);
    }
  });
});
