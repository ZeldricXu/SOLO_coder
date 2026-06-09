import * as THREE from 'three';
import type { Wall, Opening, Material as MaterialData } from '@/types/floorplan';
import { SceneManager } from './SceneManager';

export class WallBuilder {
  private sceneManager: SceneManager;

  constructor() {
    this.sceneManager = new SceneManager();
  }

  buildWall(wall: Wall, openings: Opening[], materials: MaterialData[]): THREE.Group {
    return this.sceneManager.buildWall(wall, openings, materials);
  }

  buildFloor(
    boundary: { x: number; y: number }[],
    materialId: string,
    materials: MaterialData[]
  ): THREE.Mesh {
    return this.sceneManager.buildFloor(boundary, materialId, materials);
  }

  buildCeiling(
    boundary: { x: number; y: number }[],
    height: number,
    materialId: string,
    materials: MaterialData[]
  ): THREE.Mesh {
    return this.sceneManager.buildCeiling(boundary, height, materialId, materials);
  }

  clearCache(): void {
    this.sceneManager.clearCache();
  }
}
