import * as THREE from 'three';
import type { Wall } from '@/types/floorplan';
import type { Point2D } from '@/types/geometry';
import { angle, distance } from '@/utils/geometry';
import {
  createWallGeometry,
  createArcWallGeometry,
  createFloorGeometry,
} from '@/utils/csg';

export class WallGeometryBuilder {
  buildStraightWall(wall: Wall): THREE.Mesh {
    const start = new THREE.Vector3(wall.start.x, 0, wall.start.y);
    const end = new THREE.Vector3(wall.end.x, 0, wall.end.y);
    return createWallGeometry(start, end, wall.thickness, wall.height);
  }

  buildArcWall(wall: Wall): THREE.Mesh | null {
    if (!wall.center) return null;
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

  buildWall(wall: Wall): THREE.Mesh {
    if (wall.type === 'arc') {
      return this.buildArcWall(wall) || this.buildStraightWall(wall);
    }
    return this.buildStraightWall(wall);
  }

  buildFloor(boundary: Point2D[], material?: THREE.Material): THREE.Mesh {
    const points = boundary.map((p) => new THREE.Vector2(p.x, p.y));
    const mesh = createFloorGeometry(points, 0.05);
    mesh.receiveShadow = true;
    mesh.userData = { type: 'floor' };
    if (material) {
      mesh.material = material;
    }
    return mesh;
  }

  buildCeiling(boundary: Point2D[], height: number, material?: THREE.Material): THREE.Mesh {
    const mesh = this.buildFloor(boundary, material);
    mesh.position.y = height;
    mesh.rotation.x = Math.PI;
    mesh.userData = { type: 'ceiling' };
    return mesh;
  }
}
