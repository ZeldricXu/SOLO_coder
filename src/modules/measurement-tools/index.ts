import { PickResult, pickAtom } from './picking';
import { measureDistance } from './distance';
import { measureAngle } from './angle';
import { measureDihedral } from './dihedral';

export type MeasurementType = 'distance' | 'angle' | 'dihedral';

export interface Measurement {
  type: MeasurementType;
  atomIndices: number[];
  value: number;
}

const REQUIRED_ATOMS: Record<MeasurementType, number> = {
  distance: 2,
  angle: 3,
  dihedral: 4,
};

export class MeasurementManager {
  private measurements: Measurement[] = [];
  private currentTool: MeasurementType | null = null;
  private selectedAtoms: number[] = [];

  setTool(type: MeasurementType | null): void {
    this.currentTool = type;
    this.selectedAtoms = [];
  }

  addAtomSelection(
    index: number,
    atoms: { index: number; x: number; y: number; z: number }[]
  ): Measurement | null {
    if (this.currentTool === null) return null;

    this.selectedAtoms.push(index);

    const required = REQUIRED_ATOMS[this.currentTool];
    if (this.selectedAtoms.length < required) return null;

    const selected = this.selectedAtoms.map(i => atoms.find(a => a.index === i)!);

    let value: number;
    switch (this.currentTool) {
      case 'distance':
        value = measureDistance(selected[0], selected[1]);
        break;
      case 'angle':
        value = measureAngle(selected[0], selected[1], selected[2]);
        break;
      case 'dihedral':
        value = measureDihedral(selected[0], selected[1], selected[2], selected[3]);
        break;
    }

    const measurement: Measurement = {
      type: this.currentTool,
      atomIndices: [...this.selectedAtoms],
      value,
    };

    this.measurements.push(measurement);
    this.selectedAtoms = [];
    return measurement;
  }

  getMeasurements(): Measurement[] {
    return this.measurements;
  }

  removeMeasurement(index: number): void {
    this.measurements.splice(index, 1);
  }

  clearSelection(): void {
    this.selectedAtoms = [];
  }

  getSelectedAtoms(): number[] {
    return this.selectedAtoms;
  }

  getCurrentTool(): MeasurementType | null {
    return this.currentTool;
  }
}

export { PickResult, pickAtom, measureDistance, measureAngle, measureDihedral };
