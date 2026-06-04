import { describe, it, expect } from 'vitest';
import { measureDistance, measureAngle, measureDihedral, MeasurementManager } from './index';
import { makeWaterMolecule, makeCollinearAtoms, makeLinearAtoms } from '@/test/fixtures';

describe('measureDistance', () => {
  it('computes distance along x-axis', () => {
    const a1 = { x: 0, y: 0, z: 0 };
    const a2 = { x: 3, y: 0, z: 0 };
    expect(measureDistance(a1, a2)).toBeCloseTo(3.0, 2);
  });

  it('computes distance along y-axis', () => {
    const a1 = { x: 0, y: 0, z: 0 };
    const a2 = { x: 0, y: 4, z: 0 };
    expect(measureDistance(a1, a2)).toBeCloseTo(4.0, 2);
  });

  it('returns 0 for same atom', () => {
    const a1 = { x: 0, y: 0, z: 0 };
    expect(measureDistance(a1, a1)).toBeCloseTo(0.0, 2);
  });

  it('computes 3D diagonal distance', () => {
    const a1 = { x: 1, y: 2, z: 3 };
    const a2 = { x: 4, y: 6, z: 7 };
    expect(measureDistance(a1, a2)).toBeCloseTo(Math.sqrt(41), 2);
  });

  it('computes O-H1 distance in water molecule', () => {
    const water = makeWaterMolecule();
    expect(measureDistance(water[0], water[1])).toBeCloseTo(Math.sqrt(0.757 ** 2 + 0.586 ** 2), 2);
  });
});

describe('measureAngle', () => {
  it('computes right angle', () => {
    const a1 = { x: 0, y: 0, z: 0 };
    const a2 = { x: 1, y: 0, z: 0 };
    const a3 = { x: 0, y: 1, z: 0 };
    expect(measureAngle(a2, a1, a3)).toBeCloseTo(90, 2);
  });

  it('computes 180° for collinear atoms', () => {
    const collinear = makeCollinearAtoms();
    expect(measureAngle(collinear[0], collinear[1], collinear[2])).toBeCloseTo(180, 2);
  });

  it('computes H-O-H angle in water molecule', () => {
    const water = makeWaterMolecule();
    expect(measureAngle(water[1], water[0], water[2])).toBeCloseTo(104.5, 0);
  });

  it('computes equilateral triangle angle', () => {
    const a1 = { x: 0, y: 0, z: 0 };
    const a2 = { x: 1, y: 0, z: 0 };
    const a3 = { x: 0.5, y: Math.sqrt(3) / 2, z: 0 };
    expect(measureAngle(a2, a1, a3)).toBeCloseTo(60, 2);
  });
});

describe('measureDihedral', () => {
  it('computes 180° for anti-periplanar conformation', () => {
    const a1 = { x: 0, y: 1, z: 0 };
    const a2 = { x: 0, y: 0, z: 0 };
    const a3 = { x: 1, y: 0, z: 0 };
    const a4 = { x: 1, y: -1, z: 0 };
    expect(measureDihedral(a1, a2, a3, a4)).toBeCloseTo(180, 1);
  });

  it('computes gauche conformation dihedral', () => {
    const a1 = { x: 0, y: 1, z: 0 };
    const a2 = { x: 0, y: 0, z: 0 };
    const a3 = { x: 1, y: 0, z: 0 };
    const a4 = { x: 1, y: 1, z: 1 };
    const result = measureDihedral(a1, a2, a3, a4);
    expect(Math.abs(result)).toBeLessThanOrEqual(180);
    expect(Math.abs(result)).toBeGreaterThanOrEqual(0);
  });

  it('computes H2O2-like dihedral', () => {
    const a1 = { x: -0.757, y: 0.586, z: 0 };
    const a2 = { x: 0, y: 0, z: 0 };
    const a3 = { x: 0.757, y: 0, z: 0.586 };
    const a4 = { x: 1.514, y: -0.586, z: 0.586 };
    const result = measureDihedral(a1, a2, a3, a4);
    expect(result).not.toBeNaN();
  });

  it('computes 0° for planar cis conformation', () => {
    const a1 = { x: 0, y: 1, z: 0 };
    const a2 = { x: 0, y: 0, z: 0 };
    const a3 = { x: 1, y: 0, z: 0 };
    const a4 = { x: 1, y: 1, z: 0 };
    expect(measureDihedral(a1, a2, a3, a4)).toBeCloseTo(0, 2);
  });
});

describe('Edge Cases', () => {
  it('measureAngle returns 180° for collinear atoms', () => {
    const collinear = makeCollinearAtoms();
    expect(measureAngle(collinear[0], collinear[1], collinear[2])).toBeCloseTo(180, 2);
  });

  it('measureDistance returns 0 for identical positions', () => {
    const a = { x: 5, y: 5, z: 5 };
    expect(measureDistance(a, a)).toBeCloseTo(0, 2);
  });

  it('measureDistance between atoms in different chains', () => {
    const chainA = makeLinearAtoms(2, 1.54);
    const chainB = makeLinearAtoms(2, 1.54);
    expect(measureDistance(chainA[0], chainB[0])).toBeCloseTo(0, 2);
  });

  it('measureDihedral with collinear middle bond produces a value', () => {
    const a1 = { x: 0, y: 1, z: 0 };
    const a2 = { x: 0, y: 0, z: 0 };
    const a3 = { x: 1, y: 0, z: 0 };
    const a4 = { x: 2, y: 0, z: 0 };
    const result = measureDihedral(a1, a2, a3, a4);
    expect(result).not.toBeNaN();
  });
});

describe('MeasurementManager', () => {
  it('returns distance measurement with 2 atom selections', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(2, 3.0);
    mgr.setTool('distance');
    mgr.addAtomSelection(0, atoms);
    const result = mgr.addAtomSelection(1, atoms);
    expect(result).not.toBeNull();
    expect(result!.type).toBe('distance');
    expect(result!.value).toBeCloseTo(3.0, 2);
  });

  it('returns angle measurement with 3 atom selections', () => {
    const mgr = new MeasurementManager();
    const a1 = { index: 0, x: 0, y: 0, z: 0 };
    const a2 = { index: 1, x: 1, y: 0, z: 0 };
    const a3 = { index: 2, x: 0, y: 1, z: 0 };
    const atoms = [a1, a2, a3];
    mgr.setTool('angle');
    expect(mgr.addAtomSelection(0, atoms)).toBeNull();
    expect(mgr.addAtomSelection(1, atoms)).toBeNull();
    const result = mgr.addAtomSelection(2, atoms);
    expect(result).not.toBeNull();
    expect(result!.type).toBe('angle');
  });

  it('returns dihedral measurement with 4 atom selections', () => {
    const mgr = new MeasurementManager();
    const a1 = { index: 0, x: 0, y: 1, z: 0 };
    const a2 = { index: 1, x: 0, y: 0, z: 0 };
    const a3 = { index: 2, x: 1, y: 0, z: 0 };
    const a4 = { index: 3, x: 1, y: 1, z: 0 };
    const atoms = [a1, a2, a3, a4];
    mgr.setTool('dihedral');
    expect(mgr.addAtomSelection(0, atoms)).toBeNull();
    expect(mgr.addAtomSelection(1, atoms)).toBeNull();
    expect(mgr.addAtomSelection(2, atoms)).toBeNull();
    const result = mgr.addAtomSelection(3, atoms);
    expect(result).not.toBeNull();
    expect(result!.type).toBe('dihedral');
  });

  it('returns null with fewer than required atoms', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(4);
    mgr.setTool('dihedral');
    expect(mgr.addAtomSelection(0, atoms)).toBeNull();
    expect(mgr.addAtomSelection(1, atoms)).toBeNull();
    expect(mgr.addAtomSelection(2, atoms)).toBeNull();
  });

  it('resets selectedAtoms after measurement completes', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(4);
    mgr.setTool('distance');
    mgr.addAtomSelection(0, atoms);
    mgr.addAtomSelection(1, atoms);
    expect(mgr.getSelectedAtoms()).toEqual([]);
  });

  it('accumulates multiple measurements in getMeasurements()', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(4);
    mgr.setTool('distance');
    mgr.addAtomSelection(0, atoms);
    mgr.addAtomSelection(1, atoms);
    mgr.addAtomSelection(2, atoms);
    mgr.addAtomSelection(3, atoms);
    expect(mgr.getMeasurements()).toHaveLength(2);
  });

  it('removes measurement by index', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(4);
    mgr.setTool('distance');
    mgr.addAtomSelection(0, atoms);
    mgr.addAtomSelection(1, atoms);
    mgr.addAtomSelection(2, atoms);
    mgr.addAtomSelection(3, atoms);
    mgr.removeMeasurement(0);
    expect(mgr.getMeasurements()).toHaveLength(1);
  });

  it('clears partial selection with clearSelection()', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(4);
    mgr.setTool('angle');
    mgr.addAtomSelection(0, atoms);
    expect(mgr.getSelectedAtoms()).toEqual([0]);
    mgr.clearSelection();
    expect(mgr.getSelectedAtoms()).toEqual([]);
  });

  it('returns null from addAtomSelection when tool is null', () => {
    const mgr = new MeasurementManager();
    const atoms = makeLinearAtoms(2);
    mgr.setTool(null);
    expect(mgr.addAtomSelection(0, atoms)).toBeNull();
  });
});
