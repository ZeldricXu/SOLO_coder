import * as THREE from 'three';
import type { Wall, Opening } from '@/types/floorplan';
import { angle, distance } from '@/utils/geometry';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';

export class DoorGenerator {
  private materialFactory: PBRMaterialFactory;

  constructor(materialFactory: PBRMaterialFactory) {
    this.materialFactory = materialFactory;
  }

  private getOpeningWorldPosition(wall: Wall, opening: Opening): {
    position: THREE.Vector3;
    rotationY: number;
  } {
    const wallLength = distance(wall.start, wall.end);
    const t = Math.max(0, Math.min(1, opening.positionX / wallLength));
    const pos = {
      x: wall.start.x + (wall.end.x - wall.start.x) * t,
      y: wall.start.y + (wall.end.y - wall.start.y) * t,
    };
    const wallAngle = angle(wall.start, wall.end);
    return {
      position: new THREE.Vector3(pos.x, opening.sillHeight || 0, pos.y),
      rotationY: -wallAngle,
    };
  }

  buildDoorPanel(opening: Opening, wall: Wall): THREE.Group | null {
    if (opening.type !== 'door') return null;

    const { position, rotationY } = this.getOpeningWorldPosition(wall, opening);
    const group = new THREE.Group();
    group.name = `door-${opening.id}`;
    group.userData = { openingId: opening.id, type: 'door' };

    const doorThickness = 0.05;
    const doorWidth = opening.width * 0.95;
    const doorHeight = opening.height - 0.05;

    const geometry = new THREE.BoxGeometry(doorWidth, doorHeight, doorThickness);
    const woodMaterial = new THREE.MeshStandardMaterial({
      color: 0x8b6f47,
      roughness: 0.7,
      metalness: 0.05,
    });

    const doorMesh = new THREE.Mesh(geometry, woodMaterial);
    doorMesh.castShadow = true;
    doorMesh.receiveShadow = true;
    doorMesh.position.y = doorHeight / 2;

    const pivot = new THREE.Group();
    pivot.position.x = -doorWidth / 2;
    const swingAngle = opening.swingAngle ?? 0;
    pivot.rotation.y = (swingAngle * Math.PI) / 180;
    doorMesh.position.x = doorWidth / 2;
    pivot.add(doorMesh);

    const handleGeom = new THREE.CylinderGeometry(0.015, 0.015, 0.1, 16);
    const handleMat = new THREE.MeshStandardMaterial({
      color: 0xb0b0b0,
      roughness: 0.3,
      metalness: 0.9,
    });
    const handle = new THREE.Mesh(handleGeom, handleMat);
    handle.position.set(doorWidth * 0.35, doorHeight * 0.5, doorThickness + 0.02);
    doorMesh.add(handle);

    group.add(pivot);
    group.position.copy(position);
    group.rotation.y = rotationY;

    return group;
  }

  buildWindowPanel(opening: Opening, wall: Wall): THREE.Group | null {
    if (opening.type !== 'window') return null;

    const { position, rotationY } = this.getOpeningWorldPosition(wall, opening);
    const group = new THREE.Group();
    group.name = `window-${opening.id}`;
    group.userData = { openingId: opening.id, type: 'window' };

    const frameWidth = 0.04;
    const glassMat = this.materialFactory.createGlassMaterial();
    const frameMat = new THREE.MeshStandardMaterial({
      color: 0xffffff,
      roughness: 0.4,
      metalness: 0.2,
    });

    const frameDepth = 0.06;
    const halfW = opening.width / 2;
    const halfH = opening.height / 2;

    const frames = [
      { x: 0, y: halfH - frameWidth / 2, w: opening.width, h: frameWidth },
      { x: 0, y: -halfH + frameWidth / 2, w: opening.width, h: frameWidth },
      { x: -halfW + frameWidth / 2, y: 0, w: frameWidth, h: opening.height },
      { x: halfW - frameWidth / 2, y: 0, w: frameWidth, h: opening.height },
      { x: 0, y: 0, w: frameWidth, h: opening.height },
    ];

    frames.forEach((f) => {
      const geom = new THREE.BoxGeometry(f.w, f.h, frameDepth);
      const mesh = new THREE.Mesh(geom, frameMat);
      mesh.position.set(f.x, f.y, 0);
      mesh.castShadow = true;
      group.add(mesh);
    });

    const glassGeom = new THREE.BoxGeometry(opening.width - frameWidth * 2, opening.height - frameWidth * 2, 0.01);
    const glassLeft = new THREE.Mesh(glassGeom, glassMat);
    glassLeft.position.x = -opening.width / 4;
    const glassRight = new THREE.Mesh(glassGeom, glassMat);
    glassRight.position.x = opening.width / 4;
    group.add(glassLeft, glassRight);

    group.position.copy(position);
    group.position.y += opening.height / 2;
    group.rotation.y = rotationY;

    return group;
  }

  buildAllOpenings(openings: Opening[], walls: Wall[]): THREE.Group[] {
    const results: THREE.Group[] = [];
    const wallMap = new Map(walls.map((w) => [w.id, w]));

    for (const opening of openings) {
      const wall = wallMap.get(opening.wallId);
      if (!wall) continue;

      if (opening.type === 'door') {
        const door = this.buildDoorPanel(opening, wall);
        if (door) results.push(door);
      } else if (opening.type === 'window') {
        const win = this.buildWindowPanel(opening, wall);
        if (win) results.push(win);
      }
    }
    return results;
  }
}
