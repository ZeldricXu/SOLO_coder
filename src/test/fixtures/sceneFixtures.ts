import * as THREE from 'three';
import type { Wall, Opening, FurnitureItem } from '@/types/floorplan';
import { generateId } from '@/utils/geometry';
import { createTestPoint } from './drawingFixtures';
import { DEFAULT_MATERIALS } from '@/types/materials';

export const createTestOpening = (
  type: 'door' | 'window',
  wallId: string,
  options: Partial<Opening> = {}
): Opening => ({
  id: generateId(),
  type,
  wallId,
  positionX: 0.5,
  width: type === 'door' ? 0.9 : 1.2,
  height: type === 'door' ? 2.1 : 1.5,
  sillHeight: type === 'window' ? 0.9 : 0,
  swingAngle: type === 'door' ? 90 : 0,
  ...options,
});

export const createTestFurniture = (
  modelId: string,
  position: { x: number; y: number; z?: number },
  options: Partial<FurnitureItem> = {}
): FurnitureItem => ({
  id: generateId(),
  modelId,
  name: options.name || `Furniture-${modelId}`,
  category: options.category || 'generic',
  position: { x: position.x, y: position.y, z: position.z ?? 0 },
  rotation: 0,
  scale: 1,
  ...options,
});

export const createTestScene = (): THREE.Scene => {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x1a1f2e);
  return scene;
};

export const createTestCamera = (): THREE.PerspectiveCamera => {
  return new THREE.PerspectiveCamera(50, 16 / 9, 0.1, 1000);
};

export const createTestWallWithOpenings = (): { wall: Wall; openings: Opening[] } => {
  const wall: Wall = {
    id: generateId(),
    type: 'straight',
    start: createTestPoint(0, 0),
    end: createTestPoint(5, 0),
    thickness: 0.2,
    height: 2.8,
    materialId: 'mat-wall-white',
  };

  const door = createTestOpening('door', wall.id, { positionX: 1 });
  const window = createTestOpening('window', wall.id, { positionX: 3 });

  return { wall, openings: [door, window] };
};

export const createTestMaterial = (overrides: Partial<THREE.MeshStandardMaterialParameters> = {}) => {
  return new THREE.MeshStandardMaterial({
    color: 0xffffff,
    roughness: 0.5,
    metalness: 0.0,
    ...overrides,
  });
};

export const createTestBoxGeometry = (
  width: number = 1,
  height: number = 1,
  depth: number = 1
): THREE.BoxGeometry => {
  return new THREE.BoxGeometry(width, height, depth);
};

export const createTestBoxMesh = (
  width: number = 1,
  height: number = 1,
  depth: number = 1,
  material?: THREE.Material
): THREE.Mesh => {
  const geometry = createTestBoxGeometry(width, height, depth);
  const mat = material || createTestMaterial();
  return new THREE.Mesh(geometry, mat);
};

export const validateGeometryVertices = (
  geometry: THREE.BufferGeometry,
  expectedVertexCount: number
): boolean => {
  const position = geometry.getAttribute('position');
  return position && position.count === expectedVertexCount;
};

export const validateNormals = (geometry: THREE.BufferGeometry): boolean => {
  const normal = geometry.getAttribute('normal');
  if (!normal) return false;

  for (let i = 0; i < normal.count; i++) {
    const x = normal.getX(i);
    const y = normal.getY(i);
    const z = normal.getZ(i);
    const length = Math.sqrt(x * x + y * y + z * z);
    if (Math.abs(length - 1) > 0.001) {
      return false;
    }
  }
  return true;
};

export const validateWallGeometry = (
  mesh: THREE.Mesh,
  expectedWidth: number,
  expectedHeight: number,
  expectedThickness: number
): { valid: boolean; errors: string[] } => {
  const errors: string[] = [];
  const geometry = mesh.geometry as THREE.BoxGeometry;

  if (!(geometry instanceof THREE.BoxGeometry)) {
    errors.push('Geometry is not a BoxGeometry');
    return { valid: false, errors };
  }

  const params = geometry.parameters;
  if (Math.abs(params.width - expectedWidth) > 0.001) {
    errors.push(`Width mismatch: expected ${expectedWidth}, got ${params.width}`);
  }
  if (Math.abs(params.height - expectedHeight) > 0.001) {
    errors.push(`Height mismatch: expected ${expectedHeight}, got ${params.height}`);
  }
  if (Math.abs(params.depth - expectedThickness) > 0.001) {
    errors.push(`Depth mismatch: expected ${expectedThickness}, got ${params.depth}`);
  }

  if (!validateNormals(geometry)) {
    errors.push('Normals are not normalized');
  }

  return { valid: errors.length === 0, errors };
};

export const getPBRMaterialParams = (material: THREE.MeshStandardMaterial) => {
  return {
    color: material.color.getHex(),
    roughness: material.roughness,
    metalness: material.metalness,
    map: material.map,
    normalMap: material.normalMap,
    roughnessMap: material.roughnessMap,
    metalnessMap: material.metalnessMap,
    envMapIntensity: material.envMapIntensity,
  };
};

export const sceneTestFixtures = {
  createTestOpening,
  createTestFurniture,
  createTestScene,
  createTestCamera,
  createTestWallWithOpenings,
  createTestMaterial,
  createTestBoxGeometry,
  createTestBoxMesh,
  validateGeometryVertices,
  validateNormals,
  validateWallGeometry,
  getPBRMaterialParams,
  DEFAULT_MATERIALS,
};

export default sceneTestFixtures;
