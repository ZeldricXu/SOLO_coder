import * as THREE from 'three';
import type { Wall, Opening, Material as MaterialData } from '@/types/floorplan';
import { angle, distance } from '@/utils/geometry';
import {
  createWallGeometry,
  createArcWallGeometry,
  createOpeningCutter,
  cutOpeningInWall,
} from '@/utils/csg';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';

export class WallBuilder {
  private materialFactory: PBRMaterialFactory;
  private materialCache: Map<string, THREE.Material> = new Map();

  constructor() {
    this.materialFactory = new PBRMaterialFactory();
  }

  buildWall(wall: Wall, openings: Opening[], materials: MaterialData[]): THREE.Group {
    const group = new THREE.Group();
    group.name = `wall-${wall.id}`;
    group.userData = { wallId: wall.id, type: 'wall' };

    const wallMesh = this.createWallMesh(wall);
    let finalMesh = wallMesh;

    const wallOpenings = openings.filter((o) => o.wallId === wall.id);
    for (const opening of wallOpenings) {
      const cutter = this.createOpeningCutter(wall, opening);
      if (cutter) {
        finalMesh = cutOpeningInWall(finalMesh, cutter);
      }
    }

    const material = this.getMaterial(wall.materialId, materials);
    if (material) {
      finalMesh.material = material;
    }

    finalMesh.castShadow = true;
    finalMesh.receiveShadow = true;
    finalMesh.userData = { wallId: wall.id, type: 'wall' };

    group.add(finalMesh);
    return group;
  }

  private createWallMesh(wall: Wall): THREE.Mesh {
    const start = new THREE.Vector3(wall.start.x, 0, wall.start.y);
    const end = new THREE.Vector3(wall.end.x, 0, wall.end.y);

    if (wall.type === 'arc' && wall.center) {
      const center = new THREE.Vector3(wall.center.x, 0, wall.center.y);
      const startAngle = angle(wall.center, wall.start);
      const endAngle = angle(wall.center, wall.end);
      const radius = distance(wall.center, wall.start);
      return createArcWallGeometry(
        center,
        radius,
        startAngle,
        endAngle,
        wall.thickness,
        wall.height
      );
    }

    return createWallGeometry(start, end, wall.thickness, wall.height);
  }

  private createOpeningCutter(wall: Wall, opening: Opening): THREE.Mesh | null {
    const wallLength = distance(wall.start, wall.end);
    if (opening.positionX < 0 || opening.positionX > wallLength) return null;

    const t = opening.positionX / wallLength;
    const pos = {
      x: wall.start.x + (wall.end.x - wall.start.x) * t,
      y: wall.start.y + (wall.end.y - wall.start.y) * t,
    };

    const wallAngle = angle(wall.start, wall.end);
    const perpAngle = wallAngle + Math.PI / 2;
    const halfThick = wall.thickness / 2;

    const position = new THREE.Vector3(
      pos.x + Math.cos(perpAngle) * halfThick,
      0,
      pos.y + Math.sin(perpAngle) * halfThick
    );

    const sillHeight = opening.sillHeight || 0;
    position.y = sillHeight;

    const rotation = -wallAngle;

    return createOpeningCutter(
      position,
      opening.width,
      opening.height,
      wall.thickness + 0.1,
      rotation
    );
  }

  private getMaterial(
    materialId: string,
    materials: MaterialData[]
  ): THREE.Material | null {
    if (this.materialCache.has(materialId)) {
      return this.materialCache.get(materialId)!;
    }

    const materialData = materials.find((m) => m.id === materialId);
    if (!materialData) return null;

    const material = this.materialFactory.createMaterial(materialData);
    this.materialCache.set(materialId, material);
    return material;
  }

  buildFloor(
    boundary: { x: number; y: number }[],
    materialId: string,
    materials: MaterialData[]
  ): THREE.Mesh {
    const points = boundary.map((p) => new THREE.Vector2(p.x, p.y));

    const shape = new THREE.Shape();
    if (points.length > 0) {
      shape.moveTo(points[0].x, points[0].y);
      for (let i = 1; i < points.length; i++) {
        shape.lineTo(points[i].x, points[i].y);
      }
      shape.closePath();
    }

    const extrudeSettings = {
      depth: 0.05,
      bevelEnabled: false,
    };

    const geometry = new THREE.ExtrudeGeometry(shape, extrudeSettings);
    geometry.rotateX(-Math.PI / 2);

    const material = this.getMaterial(materialId, materials) || new THREE.MeshStandardMaterial({ color: 0x8b7355 });

    const mesh = new THREE.Mesh(geometry, material);
    mesh.receiveShadow = true;
    mesh.userData = { type: 'floor' };

    return mesh;
  }

  buildCeiling(
    boundary: { x: number; y: number }[],
    height: number,
    materialId: string,
    materials: MaterialData[]
  ): THREE.Mesh {
    const floor = this.buildFloor(boundary, materialId, materials);
    floor.position.y = height;
    floor.rotation.x = Math.PI;
    floor.userData = { type: 'ceiling' };
    return floor;
  }

  clearCache(): void {
    this.materialCache.forEach((mat) => mat.dispose());
    this.materialCache.clear();
  }
}
