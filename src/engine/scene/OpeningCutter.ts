import * as THREE from 'three';
import type { Wall, Opening } from '@/types/floorplan';
import { angle, distance } from '@/utils/geometry';
import {
  createOpeningCutter,
  cutOpeningInWall,
} from '@/utils/csg';

export class OpeningCutter {
  createOpeningCutterMesh(wall: Wall, opening: Opening): THREE.Mesh | null {
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

  cutOpenings(wallMesh: THREE.Mesh, wall: Wall, openings: Opening[]): THREE.Mesh {
    let result = wallMesh;
    const wallOpenings = openings.filter((o) => o.wallId === wall.id);

    for (const opening of wallOpenings) {
      const cutter = this.createOpeningCutterMesh(wall, opening);
      if (cutter) {
        result = cutOpeningInWall(result, cutter);
      }
    }

    return result;
  }
}
