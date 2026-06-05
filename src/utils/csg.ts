import * as THREE from 'three';
import { SUBTRACTION, ADDITION, INTERSECTION, Evaluator } from 'three-bvh-csg';

const csgEvaluator = new Evaluator();

const toBrush = (mesh: THREE.Mesh) => {
  mesh.updateMatrixWorld();
  return mesh as any;
};

export const subtractGeometry = (
  baseMesh: THREE.Mesh,
  subtractMesh: THREE.Mesh
): THREE.Mesh => {
  try {
    csgEvaluator.useGroups = false;
    const baseBrush = toBrush(baseMesh);
    const subtractBrush = toBrush(subtractMesh);
    const result = csgEvaluator.evaluate(baseBrush, subtractBrush, SUBTRACTION);
    const material = Array.isArray(baseMesh.material) ? baseMesh.material[0] : baseMesh.material;
    return new THREE.Mesh(result.geometry, material);
  } catch (e) {
    console.warn('CSG subtraction failed, returning base mesh', e);
    return baseMesh.clone();
  }
};

export const intersectGeometry = (
  meshA: THREE.Mesh,
  meshB: THREE.Mesh
): THREE.Mesh => {
  try {
    csgEvaluator.useGroups = false;
    const brushA = toBrush(meshA);
    const brushB = toBrush(meshB);
    const result = csgEvaluator.evaluate(brushA, brushB, INTERSECTION);
    const material = Array.isArray(meshA.material) ? meshA.material[0] : meshA.material;
    return new THREE.Mesh(result.geometry, material);
  } catch (e) {
    console.warn('CSG intersection failed, returning meshA', e);
    return meshA.clone();
  }
};

export const addGeometry = (meshA: THREE.Mesh, meshB: THREE.Mesh): THREE.Mesh => {
  try {
    csgEvaluator.useGroups = false;
    const brushA = toBrush(meshA);
    const brushB = toBrush(meshB);
    const result = csgEvaluator.evaluate(brushA, brushB, ADDITION);
    const material = Array.isArray(meshA.material) ? meshA.material[0] : meshA.material;
    return new THREE.Mesh(result.geometry, material);
  } catch (e) {
    console.warn('CSG addition failed, returning meshA', e);
    return meshA.clone();
  }
};

export const createWallGeometry = (
  start: THREE.Vector3,
  end: THREE.Vector3,
  thickness: number,
  height: number
): THREE.Mesh => {
  const length = start.distanceTo(end);
  const mid = new THREE.Vector3().addVectors(start, end).multiplyScalar(0.5);
  const direction = new THREE.Vector3().subVectors(end, start).normalize();
  const angle = Math.atan2(direction.z, direction.x);

  const geometry = new THREE.BoxGeometry(length, height, thickness);
  const material = new THREE.MeshStandardMaterial({ color: 0xffffff });
  const mesh = new THREE.Mesh(geometry, material);

  mesh.position.copy(mid);
  mesh.position.y = height / 2;
  mesh.rotation.y = -angle;

  mesh.updateMatrix();
  mesh.matrixAutoUpdate = false;

  return mesh;
};

export const createArcWallGeometry = (
  center: THREE.Vector3,
  radius: number,
  startAngle: number,
  endAngle: number,
  thickness: number,
  height: number,
  segments: number = 32
): THREE.Mesh => {
  const shape = new THREE.Shape();

  const angleRange = endAngle - startAngle;
  const outerRadius = radius + thickness / 2;
  const innerRadius = radius - thickness / 2;

  shape.moveTo(
    center.x + Math.cos(startAngle) * outerRadius,
    center.z + Math.sin(startAngle) * outerRadius
  );

  for (let i = 0; i <= segments; i++) {
    const t = i / segments;
    const angle = startAngle + angleRange * t;
    shape.lineTo(
      center.x + Math.cos(angle) * outerRadius,
      center.z + Math.sin(angle) * outerRadius
    );
  }

  for (let i = segments; i >= 0; i--) {
    const t = i / segments;
    const angle = startAngle + angleRange * t;
    shape.lineTo(
      center.x + Math.cos(angle) * innerRadius,
      center.z + Math.sin(angle) * innerRadius
    );
  }

  shape.closePath();

  const extrudeSettings = {
    depth: height,
    bevelEnabled: false,
    curveSegments: segments,
  };

  const geometry = new THREE.ExtrudeGeometry(shape, extrudeSettings);
  geometry.rotateX(-Math.PI / 2);

  const material = new THREE.MeshStandardMaterial({ color: 0xffffff });
  const mesh = new THREE.Mesh(geometry, material);

  return mesh;
};

export const createOpeningCutter = (
  position: THREE.Vector3,
  width: number,
  height: number,
  depth: number,
  rotationY: number = 0
): THREE.Mesh => {
  const geometry = new THREE.BoxGeometry(width, height, depth);
  const material = new THREE.MeshStandardMaterial({ color: 0x000000 });
  const mesh = new THREE.Mesh(geometry, material);

  mesh.position.copy(position);
  mesh.position.y = position.y + height / 2;
  mesh.rotation.y = rotationY;

  mesh.updateMatrix();
  mesh.matrixAutoUpdate = false;

  return mesh;
};

export const cutOpeningInWall = (
  wallMesh: THREE.Mesh,
  openingCutter: THREE.Mesh
): THREE.Mesh => {
  return subtractGeometry(wallMesh, openingCutter);
};

export const createFloorGeometry = (
  points: THREE.Vector2[],
  thickness: number = 0.05
): THREE.Mesh => {
  const shape = new THREE.Shape();
  if (points.length > 0) {
    shape.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length; i++) {
      shape.lineTo(points[i].x, points[i].y);
    }
    shape.closePath();
  }

  const extrudeSettings = {
    depth: thickness,
    bevelEnabled: false,
  };

  const geometry = new THREE.ExtrudeGeometry(shape, extrudeSettings);
  geometry.rotateX(-Math.PI / 2);

  const material = new THREE.MeshStandardMaterial({ color: 0x8b7355 });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.receiveShadow = true;

  return mesh;
};

export const mergeMeshes = (meshes: THREE.Mesh[]): THREE.Mesh | null => {
  if (meshes.length === 0) return null;
  if (meshes.length === 1) return meshes[0];

  let result = meshes[0];
  for (let i = 1; i < meshes.length; i++) {
    result = addGeometry(result, meshes[i]);
  }
  return result;
};

export const disposeMesh = (mesh: THREE.Mesh): void => {
  if (mesh.geometry) {
    mesh.geometry.dispose();
  }
  if (mesh.material) {
    if (Array.isArray(mesh.material)) {
      mesh.material.forEach((m) => m.dispose());
    } else {
      mesh.material.dispose();
    }
  }
};
