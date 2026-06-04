import { Vec3, vec3Create, vec3Subtract, vec3Cross, vec3Normalize } from '@/utils/math';
import { Atom } from '../molecule-parser/types';

export interface RibbonSegment {
  start: Vec3;
  end: Vec3;
  normal: Vec3;
  color: [number, number, number];
  chainId: string;
}

function cardinalSplinePoint(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: number, tension: number): Vec3 {
  const s = (1 - tension) / 2;
  const t2 = t * t;
  const t3 = t2 * t;

  const h1 = 2 * t3 - 3 * t2 + 1;
  const h2 = -2 * t3 + 3 * t2;
  const h3 = t3 - 2 * t2 + t;
  const h4 = t3 - t2;

  const m1x = s * (p2[0] - p0[0]);
  const m1y = s * (p2[1] - p0[1]);
  const m1z = s * (p2[2] - p0[2]);
  const m2x = s * (p3[0] - p1[0]);
  const m2y = s * (p3[1] - p1[1]);
  const m2z = s * (p3[2] - p1[2]);

  return [
    h1 * p1[0] + h2 * p2[0] + h3 * m1x + h4 * m2x,
    h1 * p1[1] + h2 * p2[1] + h3 * m1y + h4 * m2y,
    h1 * p1[2] + h2 * p2[2] + h3 * m1z + h4 * m2z,
  ];
}

function computePeptideNormal(prev: Vec3, curr: Vec3, next: Vec3): Vec3 {
  const tangent = vec3Create();
  vec3Subtract(tangent, next, prev);
  vec3Normalize(tangent, tangent);

  const toPrev = vec3Create();
  vec3Subtract(toPrev, prev, curr);
  vec3Normalize(toPrev, toPrev);

  const normal = vec3Create();
  vec3Cross(normal, tangent, toPrev);
  const len = normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2];
  if (len < 1e-10) {
    if (Math.abs(tangent[0]) < 0.9) {
      vec3Cross(normal, tangent, [1, 0, 0]);
    } else {
      vec3Cross(normal, tangent, [0, 1, 0]);
    }
  }
  vec3Normalize(normal, normal);
  return normal;
}

export function computeBackboneRibbon(atoms: Atom[]): RibbonSegment[] {
  const segments: RibbonSegment[] = [];

  const chainMap = new Map<string, Atom[]>();
  for (const atom of atoms) {
    if (!atom.isBackbone) continue;
    const chain = atom.chainId ?? '';
    if (!chainMap.has(chain)) {
      chainMap.set(chain, []);
    }
    chainMap.get(chain)!.push(atom);
  }

  for (const [chainId, chainAtoms] of chainMap) {
    const caAtoms = chainAtoms.filter(a => a.isCA).sort((a, b) => (a.residueSeq ?? 0) - (b.residueSeq ?? 0));

    if (caAtoms.length < 2) continue;

    const positions: Vec3[] = caAtoms.map(a => [a.x, a.y, a.z] as Vec3);
    const colors: [number, number, number][] = caAtoms.map(a => a.color);

    const tension = 0.5;
    const subdivs = 4;

    for (let i = 0; i < positions.length - 1; i++) {
      const p0 = positions[Math.max(0, i - 1)];
      const p1 = positions[i];
      const p2 = positions[i + 1];
      const p3 = positions[Math.min(positions.length - 1, i + 2)];

      const normal = computePeptideNormal(p0, p1, p2);

      for (let s = 0; s < subdivs; s++) {
        const t0 = s / subdivs;
        const t1 = (s + 1) / subdivs;

        const start = cardinalSplinePoint(p0, p1, p2, p3, t0, tension);
        const end = cardinalSplinePoint(p0, p1, p2, p3, t1, tension);

        segments.push({
          start,
          end,
          normal: [normal[0], normal[1], normal[2]],
          color: colors[i],
          chainId,
        });
      }
    }
  }

  return segments;
}
