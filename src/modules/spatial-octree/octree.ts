import { type Vec3, vec3Distance } from '@/utils/math';
import { type Frustum } from './frustum';

export interface OctreeNode {
  min: Vec3;
  max: Vec3;
  atomIndices: number[];
  children: OctreeNode[] | null;
}

export interface LODLevel {
  maxAtoms: number;
  scaleFactor: number;
}

export const LOD_LEVELS: LODLevel[] = [
  { maxAtoms: Infinity, scaleFactor: 1.0 },
  { maxAtoms: 50000, scaleFactor: 0.5 },
  { maxAtoms: 10000, scaleFactor: 0.25 },
];

const LOD_DISTANCE_THRESHOLDS = [50, 200];

export interface PositionData {
  getPosition: (index: number) => Vec3;
}

export class Octree {
  private root: OctreeNode | null = null;
  private isStructureValid = false;

  build(
    atoms: { index: number; x: number; y: number; z: number }[],
    maxDepth = 8,
    maxAtomsPerNode = 64,
  ): OctreeNode {
    this.root = this.buildStructure(atoms, maxDepth, maxAtomsPerNode);
    this.isStructureValid = true;
    return this.root;
  }

  buildStructure(
    atoms: { index: number; x: number; y: number; z: number }[],
    maxDepth = 8,
    maxAtomsPerNode = 64,
  ): OctreeNode {
    if (atoms.length === 0) {
      return {
        min: [0, 0, 0],
        max: [0, 0, 0],
        atomIndices: [],
        children: null,
      };
    }

    let minX = Infinity;
    let minY = Infinity;
    let minZ = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    let maxZ = -Infinity;
    for (const atom of atoms) {
      if (atom.x < minX) minX = atom.x;
      if (atom.y < minY) minY = atom.y;
      if (atom.z < minZ) minZ = atom.z;
      if (atom.x > maxX) maxX = atom.x;
      if (atom.y > maxY) maxY = atom.y;
      if (atom.z > maxZ) maxZ = atom.z;
    }

    return this.buildNode(
      atoms,
      [minX, minY, minZ],
      [maxX, maxY, maxZ],
      0,
      maxDepth,
      maxAtomsPerNode,
    );
  }

  private buildNode(
    atoms: { index: number; x: number; y: number; z: number }[],
    min: Vec3,
    max: Vec3,
    depth: number,
    maxDepth: number,
    maxAtomsPerNode: number,
  ): OctreeNode {
    if (atoms.length <= maxAtomsPerNode || depth >= maxDepth) {
      return {
        min,
        max,
        atomIndices: atoms.map((a) => a.index),
        children: null,
      };
    }

    const mid: Vec3 = [
      (min[0] + max[0]) / 2,
      (min[1] + max[1]) / 2,
      (min[2] + max[2]) / 2,
    ];

    const octants: {
      atoms: { index: number; x: number; y: number; z: number }[];
      min: Vec3;
      max: Vec3;
    }[] = [];

    for (let i = 0; i < 8; i++) {
      const childMin: Vec3 = [
        (i & 1) === 0 ? min[0] : mid[0],
        (i & 2) === 0 ? min[1] : mid[1],
        (i & 4) === 0 ? min[2] : mid[2],
      ];
      const childMax: Vec3 = [
        (i & 1) === 0 ? mid[0] : max[0],
        (i & 2) === 0 ? mid[1] : max[1],
        (i & 4) === 0 ? mid[2] : max[2],
      ];
      octants.push({ atoms: [], min: childMin, max: childMax });
    }

    for (const atom of atoms) {
      const xi = atom.x >= mid[0] ? 1 : 0;
      const yi = atom.y >= mid[1] ? 1 : 0;
      const zi = atom.z >= mid[2] ? 1 : 0;
      const octantIndex = xi | (yi << 1) | (zi << 2);
      octants[octantIndex].atoms.push(atom);
    }

    const children: OctreeNode[] = [];
    for (const octant of octants) {
      if (octant.atoms.length > 0) {
        children.push(
          this.buildNode(
            octant.atoms,
            octant.min,
            octant.max,
            depth + 1,
            maxDepth,
            maxAtomsPerNode,
          ),
        );
      }
    }

    return { min, max, atomIndices: [], children };
  }

  cull(node: OctreeNode, frustum: Frustum): number[] {
    const result: number[] = [];
    this.cullNode(node, frustum, result);
    return result;
  }

  private cullNode(
    node: OctreeNode,
    frustum: Frustum,
    result: number[],
  ): void {
    if (!this.nodeIntersectsFrustum(node, frustum)) return;

    if (node.children === null) {
      result.push(...node.atomIndices);
      return;
    }

    for (const child of node.children) {
      this.cullNode(child, frustum, result);
    }
  }

  private nodeIntersectsFrustum(
    node: OctreeNode,
    frustum: Frustum,
  ): boolean {
    for (const [a, b, c, d] of frustum.planes) {
      const px = a >= 0 ? node.max[0] : node.min[0];
      const py = b >= 0 ? node.max[1] : node.min[1];
      const pz = c >= 0 ? node.max[2] : node.min[2];
      if (a * px + b * py + c * pz + d < 0) return false;
    }
    return true;
  }

  getLOD(
    node: OctreeNode,
    cameraPosition: Vec3,
    frustum: Frustum,
  ): { visibleIndices: number[]; lodLevel: LODLevel } {
    const allVisible = this.cull(node, frustum);

    const center: Vec3 = [
      (node.min[0] + node.max[0]) / 2,
      (node.min[1] + node.max[1]) / 2,
      (node.min[2] + node.max[2]) / 2,
    ];
    const distance = vec3Distance(cameraPosition, center);

    let lodLevel = LOD_LEVELS[0];
    for (let i = 0; i < LOD_DISTANCE_THRESHOLDS.length; i++) {
      if (distance >= LOD_DISTANCE_THRESHOLDS[i]) {
        lodLevel = LOD_LEVELS[i + 1];
      }
    }

    let visibleIndices = allVisible;
    if (visibleIndices.length > lodLevel.maxAtoms) {
      const step = visibleIndices.length / lodLevel.maxAtoms;
      const sampled: number[] = [];
      for (let i = 0; i < lodLevel.maxAtoms; i++) {
        sampled.push(visibleIndices[Math.floor(i * step)]);
      }
      visibleIndices = sampled;
    }

    return { visibleIndices, lodLevel };
  }

  flattenForGPU(node: OctreeNode): Float32Array {
    const nodes: OctreeNode[] = [];
    const nodeIndices = new Map<OctreeNode, number>();
    this.collectNodes(node, nodes, nodeIndices);

    const allAtomIndices: number[] = [];
    const atomOffsets = new Map<OctreeNode, number>();
    for (const n of nodes) {
      atomOffsets.set(n, allAtomIndices.length);
      allAtomIndices.push(...n.atomIndices);
    }

    const floatsPerNode = 16;
    const atomDataOffset = nodes.length * floatsPerNode;
    const buffer = new Float32Array(
      nodes.length * floatsPerNode + allAtomIndices.length,
    );

    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i];
      const offset = i * floatsPerNode;

      buffer[offset] = n.min[0];
      buffer[offset + 1] = n.min[1];
      buffer[offset + 2] = n.min[2];
      buffer[offset + 3] = n.max[0];
      buffer[offset + 4] = n.max[1];
      buffer[offset + 5] = n.max[2];
      buffer[offset + 6] =
        n.atomIndices.length > 0
          ? atomDataOffset + atomOffsets.get(n)!
          : 0;
      buffer[offset + 7] = n.atomIndices.length;

      const childOffsets: number[] = [-1, -1, -1, -1, -1, -1, -1, -1];
      if (n.children !== null) {
        const mid: Vec3 = [
          (n.min[0] + n.max[0]) / 2,
          (n.min[1] + n.max[1]) / 2,
          (n.min[2] + n.max[2]) / 2,
        ];
        for (const child of n.children) {
          const octant = this.getOctantIndex(mid, child);
          childOffsets[octant] = nodeIndices.get(child)!;
        }
      }
      for (let j = 0; j < 8; j++) {
        buffer[offset + 8 + j] = childOffsets[j];
      }
    }

    for (let i = 0; i < allAtomIndices.length; i++) {
      buffer[atomDataOffset + i] = allAtomIndices[i];
    }

    return buffer;
  }

  private collectNodes(
    node: OctreeNode,
    nodes: OctreeNode[],
    indices: Map<OctreeNode, number>,
  ): void {
    indices.set(node, nodes.length);
    nodes.push(node);
    if (node.children !== null) {
      for (const child of node.children) {
        this.collectNodes(child, nodes, indices);
      }
    }
  }

  private getOctantIndex(center: Vec3, child: OctreeNode): number {
    const xi = child.min[0] >= center[0] ? 1 : 0;
    const yi = child.min[1] >= center[1] ? 1 : 0;
    const zi = child.min[2] >= center[2] ? 1 : 0;
    return xi | (yi << 1) | (zi << 2);
  }

  getRoot(): OctreeNode | null {
    return this.root;
  }

  isValid(): boolean {
    return this.isStructureValid;
  }

  invalidate(): void {
    this.isStructureValid = false;
    this.root = null;
  }
}
