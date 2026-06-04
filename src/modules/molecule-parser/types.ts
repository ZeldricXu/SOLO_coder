export interface Atom {
  index: number;
  element: string;
  x: number;
  y: number;
  z: number;
  residue?: string;
  residueSeq?: number;
  chainId?: string;
  occupancy?: number;
  bFactor?: number;
  vdWRadius: number;
  color: [number, number, number];
  isCA?: boolean;
  isBackbone?: boolean;
}

export interface Bond {
  atomIndex1: number;
  atomIndex2: number;
  order: number;
}

export interface Model {
  index: number;
  atoms: Atom[];
  bonds: Bond[];
}

export interface FileMetadata {
  format: 'pdb' | 'sdf' | 'xyz';
  fileName: string;
  atomCount: number;
  bondCount: number;
  modelCount: number;
  chains?: string[];
  title?: string;
  compound?: string;
}

export interface ParsedMolecule {
  atoms: Atom[];
  bonds: Bond[];
  models: Model[];
  metadata: FileMetadata;
}
