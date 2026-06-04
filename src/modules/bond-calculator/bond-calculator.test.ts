import { describe, it, expect, vi } from 'vitest';
import {
  inferBonds,
  inferBondsWithMetadata,
  getBondTableVersion,
  getBondRadiusTable,
  setBondRadiusTable,
  reloadBondRadiusTable,
  loadBondRadiusTableFromJson,
  getElementPairThreshold,
  getPairSpecificConfig,
  getCovalentRadius,
  validateBondRadiusTable,
  onBondTableChanged,
} from './index';
import { COVALENT_RADII } from './covalent-radii';
import { SpatialHash } from './spatial-hash';
import { makeAtom, makeWaterMolecule, makeLinearAtoms } from '@/test/fixtures';

describe('COVALENT_RADII (legacy)', () => {
  it('contains entries for H, C, N, O, S, P', () => {
    expect(COVALENT_RADII).toHaveProperty('H');
    expect(COVALENT_RADII).toHaveProperty('C');
    expect(COVALENT_RADII).toHaveProperty('N');
    expect(COVALENT_RADII).toHaveProperty('O');
    expect(COVALENT_RADII).toHaveProperty('S');
    expect(COVALENT_RADII).toHaveProperty('P');
  });

  it('all radii are positive numbers', () => {
    const entries = Object.values(COVALENT_RADII);
    for (const radius of entries) {
      expect(radius).toBeGreaterThan(0);
      expect(typeof radius).toBe('number');
    }
  });
});

describe('SpatialHash', () => {
  it('insert and query: insert 5 atoms, query returns nearby indices', () => {
    const hash = new SpatialHash(2.0);
    hash.insert(0, 0, 0, 0);
    hash.insert(1, 1, 0, 0);
    hash.insert(2, 0, 1, 0);
    hash.insert(3, 10, 10, 10);
    hash.insert(4, 11, 10, 10);
    const result = hash.query(0, 0, 0, 2.0);
    expect(result).toContain(0);
    expect(result).toContain(1);
    expect(result).toContain(2);
    expect(result).not.toContain(3);
    expect(result).not.toContain(4);
  });

  it('empty hash: query returns empty array', () => {
    const hash = new SpatialHash(2.0);
    const result = hash.query(0, 0, 0, 2.0);
    expect(result).toEqual([]);
  });

  it('single atom: query at that position returns its index', () => {
    const hash = new SpatialHash(2.0);
    hash.insert(42, 1.0, 2.0, 3.0);
    const result = hash.query(1.0, 2.0, 3.0, 2.0);
    expect(result).toContain(42);
  });
});

describe('inferBonds (backward compatible)', () => {
  it('water molecule infers 2 bonds (O-H)', () => {
    const water = makeWaterMolecule();
    const bonds = inferBonds(water);
    expect(bonds.length).toBe(2);
    for (const bond of bonds) {
      const involvesO = bond.atomIndex1 === 0 || bond.atomIndex2 === 0;
      const involvesH = bond.atomIndex1 !== 0 || bond.atomIndex2 !== 0;
      expect(involvesO && involvesH).toBe(true);
    }
  });

  it('empty atom array: returns no bonds', () => {
    const bonds = inferBonds([]);
    expect(bonds).toEqual([]);
  });

  it('single atom: returns no bonds', () => {
    const atoms = [makeAtom({ index: 0, element: 'C' })];
    const bonds = inferBonds(atoms);
    expect(bonds).toEqual([]);
  });

  it('two atoms far apart (beyond threshold): returns no bonds', () => {
    const atoms = [
      makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'C', x: 100, y: 0, z: 0 }),
    ];
    const bonds = inferBonds(atoms);
    expect(bonds).toEqual([]);
  });

  it('two atoms close together (C-C at 1.54 Å): infers 1 bond', () => {
    const atoms = [
      makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'C', x: 1.54, y: 0, z: 0 }),
    ];
    const bonds = inferBonds(atoms);
    expect(bonds.length).toBe(1);
    expect(bonds[0].atomIndex1).toBe(0);
    expect(bonds[0].atomIndex2).toBe(1);
  });

  it('linear chain of C atoms at 1.54 Å spacing: infers (n-1) bonds', () => {
    const n = 5;
    const atoms = makeLinearAtoms(n, 1.54);
    const bonds = inferBonds(atoms);
    expect(bonds.length).toBe(n - 1);
  });

  it('bond order estimation: very close atoms get higher order', () => {
    const sumRadii = COVALENT_RADII['C'] + COVALENT_RADII['C'];
    const atomsTriple = [
      makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'C', x: 1.0 * sumRadii, y: 0, z: 0 }),
    ];
    const bondsTriple = inferBonds(atomsTriple);
    expect(bondsTriple.length).toBe(1);
    expect(bondsTriple[0].order).toBe(3);

    const atomsDouble = [
      makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'C', x: 1.15 * sumRadii, y: 0, z: 0 }),
    ];
    const bondsDouble = inferBonds(atomsDouble);
    expect(bondsDouble.length).toBe(1);
    expect(bondsDouble[0].order).toBe(2);

    const atomsSingle = [
      makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'C', x: 1.3 * sumRadii, y: 0, z: 0 }),
    ];
    const bondsSingle = inferBonds(atomsSingle, 1.4);
    expect(bondsSingle.length).toBe(1);
    expect(bondsSingle[0].order).toBe(1);
  });
});

describe('bond-radius-table module', () => {
  it('getBondRadiusTable returns valid table with version and defaults', () => {
    const table = getBondRadiusTable();
    expect(table.version).toBe('1.0');
    expect(table.defaults.tolerance).toBe(1.2);
    expect(table.defaults.fallbackRadius).toBe(1.5);
    expect(table.defaults.maxBondDistance).toBe(2.5);
    expect(table.elementRadii.C).toBe(0.76);
    expect(table.elementRadii.H).toBe(0.31);
    expect(table.elementRadii.O).toBe(0.66);
  });

  it('getCovalentRadius returns correct values with fallback', () => {
    expect(getCovalentRadius('C')).toBe(0.76);
    expect(getCovalentRadius('H')).toBe(0.31);
    expect(getCovalentRadius('Fe')).toBe(1.32);
    expect(getCovalentRadius('Unknown')).toBe(1.5);
  });

  it('getElementPairThreshold returns pair-specific thresholds for S-S', () => {
    const result = getElementPairThreshold('S', 'S');
    expect(result.threshold).toBeCloseTo(2.1 * 1.15, 5);
    expect(result.description).toBe('Disulfide bridge');
  });

  it('getElementPairThreshold returns pair-specific thresholds for Fe-N (unordered)', () => {
    const result1 = getElementPairThreshold('Fe', 'N');
    const result2 = getElementPairThreshold('N', 'Fe');
    expect(result1.threshold).toBe(result2.threshold);
    expect(result1.threshold).toBeCloseTo(2.3 * 1.3, 5);
    expect(result1.description).toBe('Heme iron-nitrogen coordination');
  });

  it('getElementPairThreshold falls back to sum of radii for unknown pairs', () => {
    const result = getElementPairThreshold('C', 'N');
    const expected = (0.76 + 0.71) * 1.2;
    expect(result.threshold).toBeCloseTo(expected, 5);
    expect(result.description).toBeUndefined();
  });

  it('getPairSpecificConfig returns null for unknown pairs', () => {
    expect(getPairSpecificConfig('C', 'N')).toBeNull();
    expect(getPairSpecificConfig('S', 'S')).not.toBeNull();
  });

  it('validateBondRadiusTable accepts valid table', () => {
    const table = getBondRadiusTable();
    expect(() => validateBondRadiusTable(table)).not.toThrow();
  });

  it('validateBondRadiusTable rejects invalid table', () => {
    expect(() => validateBondRadiusTable(null)).toThrow();
    expect(() => validateBondRadiusTable({})).toThrow();
    expect(() => validateBondRadiusTable({ version: 123 })).toThrow();
    expect(() =>
      validateBondRadiusTable({
        version: '1.0',
        defaults: { tolerance: -1, fallbackRadius: 1, maxBondDistance: 1 },
        elementRadii: {},
        pairSpecificThresholds: {},
      })
    ).toThrow();
  });

  it('loadBondRadiusTableFromJson parses and validates', async () => {
    const json = JSON.stringify({
      version: '2.0',
      description: 'test',
      defaults: { tolerance: 1.2, fallbackRadius: 1.5, maxBondDistance: 2.5 },
      elementRadii: { C: 0.76, H: 0.31 },
      pairSpecificThresholds: {
        'C-N': { distance: 1.5, tolerance: 1.2, description: 'custom' },
      },
      specialBondTypes: {
        hydrogenBond: {
          enabled: true,
          donorElements: ['N'],
          acceptorElements: ['O'],
          maxDistance: 3.5,
          tolerance: 1.15,
        },
        halogenBond: {
          enabled: false,
          donorElements: ['Cl'],
          acceptorElements: ['O'],
          maxDistance: 4.0,
          tolerance: 1.15,
        },
        piStacking: { enabled: false, maxDistance: 5.0, tolerance: 1.15 },
      },
    });
    const table = await loadBondRadiusTableFromJson(json);
    expect(table.version).toBe('2.0');
    expect(table.pairSpecificThresholds['C-N']?.distance).toBe(1.5);
  });

  it('hot reload: setBondRadiusTable updates and notifies subscribers', () => {
    const originalVersion = getBondTableVersion();
    const callback = vi.fn();
    const unsubscribe = onBondTableChanged(callback);

    const customTable = {
      ...getBondRadiusTable(),
      version: 'custom-1.0',
    };
    setBondRadiusTable(customTable);

    expect(getBondTableVersion()).toBe('custom-1.0');
    expect(callback).toHaveBeenCalledTimes(1);
    expect(callback).toHaveBeenCalledWith(customTable);

    unsubscribe();

    reloadBondRadiusTable();
    expect(getBondTableVersion()).toBe(originalVersion);
    expect(callback).toHaveBeenCalledTimes(1);
  });

  it('getBondTableVersion returns current table version', () => {
    expect(typeof getBondTableVersion()).toBe('string');
    expect(getBondTableVersion().length).toBeGreaterThan(0);
  });
});

describe('inferBonds with pair-specific thresholds', () => {
  it('S-S disulfide bond detected at 2.05 Å (within pair-specific threshold)', () => {
    const atoms = [
      makeAtom({ index: 0, element: 'S', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'S', x: 2.05, y: 0, z: 0 }),
    ];
    const bonds = inferBonds(atoms);
    expect(bonds.length).toBe(1);
  });

  it('S-S bond not detected at 2.5 Å (beyond pair-specific threshold of 2.1 * 1.15 = 2.415)', () => {
    const atoms = [
      makeAtom({ index: 0, element: 'S', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'S', x: 2.5, y: 0, z: 0 }),
    ];
    const bonds = inferBonds(atoms);
    expect(bonds.length).toBe(0);
  });

  it('Fe-N coordination bond detected at 2.2 Å', () => {
    const atoms = [
      makeAtom({ index: 0, element: 'Fe', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'N', x: 2.2, y: 0, z: 0 }),
    ];
    const bonds = inferBonds(atoms);
    expect(bonds.length).toBe(1);
  });

  it('inferBondsWithMetadata returns pair type descriptions', () => {
    const atoms = [
      makeAtom({ index: 0, element: 'S', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'S', x: 2.05, y: 0, z: 0 }),
      makeAtom({ index: 2, element: 'C', x: 5, y: 0, z: 0 }),
      makeAtom({ index: 3, element: 'C', x: 6.54, y: 0, z: 0 }),
    ];
    const result = inferBondsWithMetadata(atoms);
    expect(result.bonds.length).toBe(2);
    expect(result.pairTypes['0-1']).toBe('Disulfide bridge');
    expect(result.pairTypes['2-3']).toBeUndefined();
  });

  it('custom table with modified pair threshold', () => {
    const customTable = {
      ...getBondRadiusTable(),
      pairSpecificThresholds: {
        ...getBondRadiusTable().pairSpecificThresholds,
        'C-C': { distance: 2.0, tolerance: 1.0, description: 'custom C-C' },
      },
    };

    const atoms = [
      makeAtom({ index: 0, element: 'C', x: 0, y: 0, z: 0 }),
      makeAtom({ index: 1, element: 'C', x: 1.9, y: 0, z: 0 }),
    ];

    const defaultBonds = inferBonds(atoms);
    const customBonds = inferBonds(atoms, 1.2, customTable);

    expect(defaultBonds.length).toBe(0);
    expect(customBonds.length).toBe(1);
  });
});
