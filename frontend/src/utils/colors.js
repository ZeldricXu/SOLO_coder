export const ELEMENT_COLORS = {
  H: 0xFFFFFF, C: 0x909090, N: 0x3050F8, O: 0xFF0D0D, S: 0xFFFF30,
  P: 0xFF8000, F: 0x90E050, CL: 0x1FF01F, BR: 0xA62929, I: 0x940094,
  FE: 0xE06633, ZN: 0x7D80B0, CU: 0xC88033, MG: 0x8AFF00, MN: 0x9C7AC7,
  CA: 0x3DFF00, NA: 0xAB5CF2, K: 0x8F40D4, SE: 0xFFA100, CO: 0xF090A0,
  NI: 0x50D050,
};

export const ELEMENT_RADII = {
  H: 0.31, C: 0.77, N: 0.75, O: 0.73, S: 1.02, P: 1.06,
  F: 0.64, CL: 0.99, BR: 1.14, I: 1.33, FE: 1.26, ZN: 1.22,
  CU: 1.18, MG: 1.36, MN: 1.39, CA: 1.76, NA: 1.66, K: 2.03,
  SE: 1.16, CO: 1.32, NI: 1.24,
};

export const CHAIN_COLORS = [
  0x4FC3F7, 0xFF8A65, 0xAED581, 0xBA68C8, 0xFFD54F,
  0xF06292, 0x4DB6AC, 0xFFB74D, 0x7986CB, 0xA1887F,
];

export const HYDROPHOBIC_SCALE = {
  ALA: 1.8, ARG: -4.5, ASN: -3.5, ASP: -3.5, CYS: 2.5,
  GLN: -3.5, GLU: -3.5, GLY: -0.4, HIS: -3.2, ILE: 4.5,
  LEU: 3.8, LYS: -3.9, MET: 1.9, PHE: 2.8, PRO: -1.6,
  SER: -0.8, THR: -0.7, TRP: -0.9, TYR: -1.3, VAL: 4.2,
};

export function getElementColor(element) {
  return ELEMENT_COLORS[element.toUpperCase()] || 0xAAAAAA;
}

export function getElementRadius(element) {
  return ELEMENT_RADII[element.toUpperCase()] || 0.77;
}

export function getChainColor(index) {
  return CHAIN_COLORS[index % CHAIN_COLORS.length];
}

export function getHydrophobicityColor(resName) {
  const scale = HYDROPHOBIC_SCALE[resName.toUpperCase()];
  if (scale === undefined) return 0x888888;
  const t = (scale + 4.5) / 9.0;
  const r = Math.round(255 * (1 - t));
  const b = Math.round(255 * t);
  return (r << 16) | (b);
}

export function getBFactorColor(bfactor, minB, maxB) {
  const range = maxB - minB || 1;
  const t = Math.min(1, Math.max(0, (bfactor - minB) / range));
  const r = Math.round(255 * t);
  const b = Math.round(255 * (1 - t));
  return (r << 16) | b;
}

export function electrostaticColor(potential, minP, maxP) {
  const range = maxP - minP || 1;
  const t = (potential - minP) / range;
  if (t < 0.5) {
    const s = t * 2;
    const r = Math.round(255 * (1 - s));
    const g = Math.round(255 * (1 - Math.abs(s - 0.5) * 2));
    const b = Math.round(255 * s);
    return [r / 255, g / 255, b / 255];
  } else {
    const s = (t - 0.5) * 2;
    const r = Math.round(255 * s);
    const g = Math.round(255 * (1 - Math.abs(s - 0.5) * 2));
    const b = Math.round(255 * (1 - s));
    return [r / 255, g / 255, b / 255];
  }
}

export function rmsdColor(rmsd, maxRmsd = 5.0) {
  const t = Math.min(1, rmsd / maxRmsd);
  const r = Math.round(255 * t);
  const b = Math.round(255 * (1 - t));
  const g = Math.round(255 * (1 - Math.abs(t - 0.5) * 2));
  return (r << 16) | (g << 8) | b;
}
