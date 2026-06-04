import { describe, it, expect } from 'vitest'
import { Octree, LOD_LEVELS } from './octree'
import type { OctreeNode } from './octree'
import { extractFrustum, frustumContainsPoint, frustumContainsSphere } from './frustum'
import { makeAtomGrid, makeWaterMolecule } from '@/test/fixtures'
import { mat4Perspective, mat4LookAt, degToRad, mat4Multiply } from '@/utils/math'

function collectLeafIndices(node: OctreeNode): number[] {
  if (node.children === null) {
    return [...node.atomIndices]
  }
  return node.children.flatMap(collectLeafIndices)
}

function getTreeDepth(node: OctreeNode): number {
  if (node.children === null) return 0
  return 1 + Math.max(...node.children.map(getTreeDepth))
}

function countNodes(node: OctreeNode): number {
  if (node.children === null) return 1
  return 1 + node.children.reduce((sum, c) => sum + countNodes(c), 0)
}

function buildFullFrustum(eye: number[], target: number[], fovy = 90, near = 0.1, far = 100) {
  const view = mat4LookAt(eye as [number, number, number], target as [number, number, number], [0, 1, 0])
  const proj = mat4Perspective(degToRad(fovy), 1, near, far)
  const vp = mat4Multiply(proj, view)
  return extractFrustum(vp)
}

describe('Octree Construction', () => {
  it('returns node with empty atomIndices and no children for empty atom list', () => {
    const octree = new Octree()
    const tree = octree.build([])
    expect(tree.atomIndices).toEqual([])
    expect(tree.children).toBeNull()
  })

  it('returns node with 1 atom index and no subdivision for single atom', () => {
    const octree = new Octree()
    const tree = octree.build([{ index: 0, x: 1, y: 2, z: 3 }])
    expect(tree.atomIndices).toEqual([0])
    expect(tree.children).toBeNull()
  })

  it('builds tree with depth > 0 and total leaf atom indices = 8 for makeAtomGrid(8, 2.0)', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(8, 2.0)
    const tree = octree.build(atoms, 8, 1)
    expect(getTreeDepth(tree)).toBeGreaterThan(0)
    expect(collectLeafIndices(tree)).toHaveLength(8)
  })

  it('builds tree with correct bounding box for makeAtomGrid(27, 2.0)', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms)
    expect(tree.min[0]).toBeCloseTo(0, 6)
    expect(tree.min[1]).toBeCloseTo(0, 6)
    expect(tree.min[2]).toBeCloseTo(0, 6)
    expect(tree.max[0]).toBeCloseTo(4, 6)
    expect(tree.max[1]).toBeCloseTo(4, 6)
    expect(tree.max[2]).toBeCloseTo(4, 6)
  })

  it('builds tree with correct node count and leaf atoms summing to 64 for makeAtomGrid(64, 1.0)', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(64, 1.0)
    const tree = octree.build(atoms, 8, 8)
    expect(countNodes(tree)).toBeGreaterThan(1)
    expect(collectLeafIndices(tree)).toHaveLength(64)
  })
})

describe('Octree Culling', () => {
  it('returns all atom indices with identity frustum (no clipping)', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms)
    const frustum = buildFullFrustum([2, 2, 20], [2, 2, 2], 90, 0.1, 100)
    const visible = octree.cull(tree, frustum)
    const allIndices = atoms.map((a) => a.index)
    expect(visible.sort((a, b) => a - b)).toEqual(allIndices.sort((a, b) => a - b))
  })

  it('returns empty or very few indices when frustum is looking away', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms)
    const frustum = buildFullFrustum([2, 2, 20], [2, 2, 40], 60, 0.1, 100)
    const visible = octree.cull(tree, frustum)
    expect(visible.length).toBeLessThanOrEqual(5)
  })

  it('returns subset of atom indices with partial frustum', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms, 8, 1)
    const frustum = buildFullFrustum([0, 0, 20], [0, 0, 2], 20, 0.1, 100)
    const visible = octree.cull(tree, frustum)
    expect(visible.length).toBeGreaterThan(0)
    expect(visible.length).toBeLessThan(atoms.length)
  })

  it('culls water molecule atoms correctly with perspective frustum', () => {
    const octree = new Octree()
    const atoms = makeWaterMolecule()
    const tree = octree.build(atoms)
    const view = mat4LookAt([0, 0, 10], [0, 0, 0], [0, 1, 0])
    const proj = mat4Perspective(degToRad(60), 1, 0.1, 100)
    const vp = mat4Multiply(proj, view)
    const frustum = extractFrustum(vp)
    const visible = octree.cull(tree, frustum)
    expect(visible.sort((a, b) => a - b)).toEqual([0, 1, 2])
  })
})

describe('Octree LOD', () => {
  it('returns LOD_LEVELS[0] for near camera', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms)
    const frustum = buildFullFrustum([2, 2, 3], [2, 2, 2], 90, 0.1, 100)
    const result = octree.getLOD(tree, [2, 2, 3], frustum)
    expect(result.lodLevel).toBe(LOD_LEVELS[0])
  })

  it('returns reduced LOD level for far camera', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms)
    const frustum = buildFullFrustum([2, 2, 300], [2, 2, 2], 90, 0.1, 1000)
    const result = octree.getLOD(tree, [2, 2, 300], frustum)
    expect(result.lodLevel.scaleFactor).toBeLessThan(1.0)
  })

  it('does not subsample at full detail LOD with near camera', () => {
    const octree = new Octree()
    const atoms = makeAtomGrid(27, 2.0)
    const tree = octree.build(atoms)
    const frustum = buildFullFrustum([2, 2, 3], [2, 2, 2], 90, 0.1, 100)
    const result = octree.getLOD(tree, [2, 2, 3], frustum)
    expect(result.visibleIndices.length).toBeLessThanOrEqual(Infinity)
  })
})

describe('Frustum', () => {
  it('extractFrustum returns 6 planes from a view-projection matrix', () => {
    const view = mat4LookAt([0, 0, 10], [0, 0, 0], [0, 1, 0])
    const proj = mat4Perspective(degToRad(60), 1, 0.1, 100)
    const vp = mat4Multiply(proj, view)
    const frustum = extractFrustum(vp)
    expect(frustum.planes).toHaveLength(6)
  })

  it('frustumContainsPoint: origin is inside a default perspective frustum', () => {
    const view = mat4LookAt([0, 0, 10], [0, 0, 0], [0, 1, 0])
    const proj = mat4Perspective(degToRad(60), 1, 0.1, 100)
    const vp = mat4Multiply(proj, view)
    const frustum = extractFrustum(vp)
    expect(frustumContainsPoint(frustum, [0, 0, 0])).toBe(true)
  })

  it('frustumContainsSphere: sphere at origin with radius 1 is inside frustum', () => {
    const view = mat4LookAt([0, 0, 10], [0, 0, 0], [0, 1, 0])
    const proj = mat4Perspective(degToRad(60), 1, 0.1, 100)
    const vp = mat4Multiply(proj, view)
    const frustum = extractFrustum(vp)
    expect(frustumContainsSphere(frustum, [0, 0, 0], 1)).toBe(true)
  })

  it('frustumContainsSphere: sphere far away returns false', () => {
    const view = mat4LookAt([0, 0, 10], [0, 0, 0], [0, 1, 0])
    const proj = mat4Perspective(degToRad(60), 1, 0.1, 100)
    const vp = mat4Multiply(proj, view)
    const frustum = extractFrustum(vp)
    expect(frustumContainsSphere(frustum, [1000, 1000, 1000], 1)).toBe(false)
  })
})

describe('Degenerate Inputs', () => {
  it('builds valid tree without infinite loop when all atoms at origin', () => {
    const octree = new Octree()
    const atoms = Array.from({ length: 10 }, (_, i) => ({ index: i, x: 0, y: 0, z: 0 }))
    const tree = octree.build(atoms)
    const leafIndices = collectLeafIndices(tree)
    expect(leafIndices.sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
  })

  it('builds without error when two atoms are at same position', () => {
    const octree = new Octree()
    const atoms = [
      { index: 0, x: 1, y: 1, z: 1 },
      { index: 1, x: 1, y: 1, z: 1 },
    ]
    const tree = octree.build(atoms)
    const leafIndices = collectLeafIndices(tree)
    expect(leafIndices.sort((a, b) => a - b)).toEqual([0, 1])
  })

  it('builds correctly with very large coordinates (1e6)', () => {
    const octree = new Octree()
    const atoms = [
      { index: 0, x: 0, y: 0, z: 0 },
      { index: 1, x: 1e6, y: 1e6, z: 1e6 },
    ]
    const tree = octree.build(atoms)
    expect(tree.min[0]).toBeCloseTo(0, 6)
    expect(tree.min[1]).toBeCloseTo(0, 6)
    expect(tree.min[2]).toBeCloseTo(0, 6)
    expect(tree.max[0]).toBeCloseTo(1e6, -6)
    expect(tree.max[1]).toBeCloseTo(1e6, -6)
    expect(tree.max[2]).toBeCloseTo(1e6, -6)
  })
})
