const ELEMENT_DATA: Record<string, {
  vdWRadius: number;
  covalentRadius: number;
  color: [number, number, number];
}> = {
  H:  { vdWRadius: 1.20, covalentRadius: 0.31, color: [1.0, 1.0, 1.0] },
  He: { vdWRadius: 1.40, covalentRadius: 0.28, color: [0.85, 1.0, 0.85] },
  Li: { vdWRadius: 1.82, covalentRadius: 1.28, color: [0.8, 0.5, 1.0] },
  Be: { vdWRadius: 1.53, covalentRadius: 0.96, color: [0.76, 1.0, 0.0] },
  B:  { vdWRadius: 1.92, covalentRadius: 0.84, color: [1.0, 0.71, 0.71] },
  C:  { vdWRadius: 1.70, covalentRadius: 0.76, color: [0.56, 0.56, 0.56] },
  N:  { vdWRadius: 1.55, covalentRadius: 0.71, color: [0.19, 0.31, 0.97] },
  O:  { vdWRadius: 1.52, covalentRadius: 0.66, color: [1.0, 0.05, 0.05] },
  F:  { vdWRadius: 1.47, covalentRadius: 0.57, color: [0.56, 0.88, 0.31] },
  Ne: { vdWRadius: 1.54, covalentRadius: 0.58, color: [0.7, 0.89, 0.96] },
  Na: { vdWRadius: 2.27, covalentRadius: 1.66, color: [0.67, 0.36, 0.95] },
  Mg: { vdWRadius: 1.73, covalentRadius: 1.41, color: [0.54, 1.0, 0.0] },
  Al: { vdWRadius: 1.84, covalentRadius: 1.21, color: [0.75, 0.65, 0.65] },
  Si: { vdWRadius: 2.10, covalentRadius: 1.11, color: [0.94, 0.78, 0.63] },
  P:  { vdWRadius: 1.80, covalentRadius: 1.07, color: [1.0, 0.5, 0.0] },
  S:  { vdWRadius: 1.80, covalentRadius: 1.05, color: [1.0, 1.0, 0.19] },
  Cl: { vdWRadius: 1.75, covalentRadius: 1.02, color: [0.12, 0.94, 0.12] },
  Ar: { vdWRadius: 1.88, covalentRadius: 1.06, color: [0.5, 0.82, 0.89] },
  K:  { vdWRadius: 2.75, covalentRadius: 2.03, color: [0.56, 0.25, 0.83] },
  Ca: { vdWRadius: 2.31, covalentRadius: 1.76, color: [0.24, 1.0, 0.0] },
  Sc: { vdWRadius: 2.11, covalentRadius: 1.70, color: [0.9, 0.9, 0.9] },
  Ti: { vdWRadius: 1.87, covalentRadius: 1.60, color: [0.75, 0.76, 0.78] },
  V:  { vdWRadius: 1.79, covalentRadius: 1.53, color: [0.65, 0.65, 0.67] },
  Cr: { vdWRadius: 1.89, covalentRadius: 1.39, color: [0.54, 0.6, 0.78] },
  Mn: { vdWRadius: 1.97, covalentRadius: 1.39, color: [0.61, 0.48, 0.79] },
  Fe: { vdWRadius: 1.94, covalentRadius: 1.32, color: [0.88, 0.4, 0.2] },
  Co: { vdWRadius: 1.92, covalentRadius: 1.26, color: [0.94, 0.56, 0.63] },
  Ni: { vdWRadius: 1.63, covalentRadius: 1.24, color: [0.31, 0.82, 0.31] },
  Cu: { vdWRadius: 1.40, covalentRadius: 1.32, color: [0.78, 0.5, 0.2] },
  Zn: { vdWRadius: 1.39, covalentRadius: 1.22, color: [0.49, 0.5, 0.69] },
  Ga: { vdWRadius: 1.87, covalentRadius: 1.22, color: [0.76, 0.56, 0.56] },
  Ge: { vdWRadius: 2.11, covalentRadius: 1.20, color: [0.4, 0.56, 0.56] },
  As: { vdWRadius: 1.85, covalentRadius: 1.19, color: [0.74, 0.5, 0.89] },
  Se: { vdWRadius: 1.90, covalentRadius: 1.20, color: [1.0, 0.63, 0.0] },
  Br: { vdWRadius: 1.85, covalentRadius: 1.20, color: [0.65, 0.16, 0.16] },
  Kr: { vdWRadius: 2.02, covalentRadius: 1.16, color: [0.36, 0.72, 0.82] },
  Rb: { vdWRadius: 3.03, covalentRadius: 2.20, color: [0.44, 0.18, 0.69] },
  Sr: { vdWRadius: 2.49, covalentRadius: 1.95, color: [0.0, 0.79, 0.0] },
  Ag: { vdWRadius: 1.72, covalentRadius: 1.53, color: [0.75, 0.75, 0.75] },
  Cd: { vdWRadius: 1.58, covalentRadius: 1.48, color: [1.0, 0.85, 0.56] },
  In: { vdWRadius: 1.93, covalentRadius: 1.44, color: [0.65, 0.46, 0.45] },
  Sn: { vdWRadius: 2.17, covalentRadius: 1.39, color: [0.4, 0.5, 0.5] },
  Sb: { vdWRadius: 2.06, covalentRadius: 1.39, color: [0.62, 0.39, 0.71] },
  Te: { vdWRadius: 2.06, covalentRadius: 1.38, color: [0.83, 0.48, 0.0] },
  I:  { vdWRadius: 1.98, covalentRadius: 1.39, color: [0.58, 0.0, 0.58] },
  Xe: { vdWRadius: 2.16, covalentRadius: 1.40, color: [0.26, 0.62, 0.69] },
  Cs: { vdWRadius: 3.43, covalentRadius: 2.44, color: [0.42, 0.17, 0.69] },
  Ba: { vdWRadius: 2.68, covalentRadius: 2.15, color: [0.0, 0.79, 0.0] },
  W:  { vdWRadius: 2.02, covalentRadius: 1.62, color: [0.33, 0.33, 0.33] },
  Pt: { vdWRadius: 1.75, covalentRadius: 1.36, color: [0.82, 0.82, 0.88] },
  Au: { vdWRadius: 1.66, covalentRadius: 1.36, color: [1.0, 0.82, 0.14] },
  Hg: { vdWRadius: 1.55, covalentRadius: 1.32, color: [0.72, 0.72, 0.82] },
  Pb: { vdWRadius: 2.02, covalentRadius: 1.46, color: [0.53, 0.53, 0.59] },
  U:  { vdWRadius: 1.86, covalentRadius: 1.58, color: [0.0, 0.56, 0.0] },
};

const DEFAULT_ELEMENT = {
  vdWRadius: 1.70,
  covalentRadius: 1.00,
  color: [1.0, 0.07, 0.69] as [number, number, number],
};

export function getElementData(element: string): { vdWRadius: number; covalentRadius: number; color: [number, number, number] } {
  const key = element.charAt(0).toUpperCase() + element.slice(1).toLowerCase();
  return ELEMENT_DATA[key] ?? DEFAULT_ELEMENT;
}

export function getCovalentRadius(element: string): number {
  return getElementData(element).covalentRadius;
}

export function getVdWRadius(element: string): number {
  return getElementData(element).vdWRadius;
}

export function getElementColor(element: string): [number, number, number] {
  return getElementData(element).color;
}
