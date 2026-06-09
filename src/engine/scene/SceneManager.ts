import * as THREE from 'three';
import type { Wall, Opening, Room, Material as MaterialData } from '@/types/floorplan';
import { WallGeometryBuilder } from './WallGeometryBuilder';
import { OpeningCutter } from './OpeningCutter';
import { DoorGenerator } from './DoorGenerator';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';

export class SceneManager {
  public geometryBuilder: WallGeometryBuilder;
  public openingCutter: OpeningCutter;
  public doorGenerator: DoorGenerator;
  private materialFactory: PBRMaterialFactory;
  private materialCache: Map<string, THREE.Material> = new Map();

  constructor(materialFactory?: PBRMaterialFactory) {
    this.materialFactory = materialFactory || new PBRMaterialFactory();
    this.geometryBuilder = new WallGeometryBuilder();
    this.openingCutter = new OpeningCutter();
    this.doorGenerator = new DoorGenerator(this.materialFactory);
  }

  private getMaterial(materialId: string, materials: MaterialData[]): THREE.Material | null {
    if (this.materialCache.has(materialId)) {
      return this.materialCache.get(materialId)!;
    }
    const materialData = materials.find((m) => m.id === materialId);
    if (!materialData) return null;
    const material = this.materialFactory.createMaterial(materialData);
    this.materialCache.set(materialId, material);
    return material;
  }

  buildWall(wall: Wall, openings: Opening[], materials: MaterialData[]): THREE.Group {
    const group = new THREE.Group();
    group.name = `wall-${wall.id}`;
    group.userData = { wallId: wall.id, type: 'wall' };

    let wallMesh = this.geometryBuilder.buildWall(wall);
    wallMesh = this.openingCutter.cutOpenings(wallMesh, wall, openings);

    const material = this.getMaterial(wall.materialId, materials);
    if (material) {
      wallMesh.material = material;
    }

    wallMesh.castShadow = true;
    wallMesh.receiveShadow = true;
    wallMesh.userData = { wallId: wall.id, type: 'wall' };

    group.add(wallMesh);
    return group;
  }

  buildFloor(
    boundary: { x: number; y: number }[],
    materialId: string,
    materials: MaterialData[]
  ): THREE.Mesh {
    const material = this.getMaterial(materialId, materials);
    const mesh = this.geometryBuilder.buildFloor(boundary, material || undefined);
    if (!material) {
      mesh.material = new THREE.MeshStandardMaterial({ color: 0x8b7355 });
    }
    return mesh;
  }

  buildCeiling(
    boundary: { x: number; y: number }[],
    height: number,
    materialId: string,
    materials: MaterialData[]
  ): THREE.Mesh {
    const material = this.getMaterial(materialId, materials);
    const mesh = this.geometryBuilder.buildCeiling(boundary, height, material || undefined);
    if (!material) {
      mesh.material = new THREE.MeshStandardMaterial({ color: 0x8b7355 });
    }
    return mesh;
  }

  buildRoomElements(room: Room, materials: MaterialData[], wallHeight: number): {
    floor: THREE.Mesh;
    ceiling: THREE.Mesh;
  } {
    return {
      floor: this.buildFloor(room.boundary, room.floorMaterialId, materials),
      ceiling: this.buildCeiling(room.boundary, wallHeight, room.ceilingMaterialId, materials),
    };
  }

  buildAllDoorsAndWindows(openings: Opening[], walls: Wall[]): THREE.Group[] {
    return this.doorGenerator.buildAllOpenings(openings, walls);
  }

  clearCache(): void {
    this.materialCache.forEach((mat) => mat.dispose());
    this.materialCache.clear();
    this.materialFactory.clearCache();
  }

  getMaterialFactory(): PBRMaterialFactory {
    return this.materialFactory;
  }
}
