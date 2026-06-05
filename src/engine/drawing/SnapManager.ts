import type { Point2D, Line2D } from '@/types/geometry';
import type { Wall } from '@/types/floorplan';
import { distance, pointToLineDistance, closestPointOnLine, pointsEqual } from '@/utils/geometry';

export interface SnapTarget {
  type: 'endpoint' | 'midpoint' | 'intersection' | 'perpendicular' | 'line';
  point: Point2D;
  distance: number;
  wallId?: string;
}

export interface SnapConfig {
  enabled: boolean;
  threshold: number;
  snapToEndpoints: boolean;
  snapToMidpoints: boolean;
  snapToIntersections: boolean;
  snapToPerpendicular: boolean;
  snapToLines: boolean;
}

const DEFAULT_CONFIG: SnapConfig = {
  enabled: true,
  threshold: 10,
  snapToEndpoints: true,
  snapToMidpoints: true,
  snapToIntersections: true,
  snapToPerpendicular: true,
  snapToLines: true,
};

export class SnapManager {
  private config: SnapConfig;
  private walls: Wall[] = [];

  constructor(config: Partial<SnapConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  setConfig(config: Partial<SnapConfig>): void {
    this.config = { ...this.config, ...config };
  }

  getConfig(): SnapConfig {
    return { ...this.config };
  }

  setWalls(walls: Wall[]): void {
    this.walls = walls;
  }

  findSnapTarget(
    point: Point2D,
    zoom: number,
    excludeWallId?: string
  ): SnapTarget | null {
    if (!this.config.enabled) return null;

    const threshold = this.config.threshold / zoom;
    const targets: SnapTarget[] = [];

    for (const wall of this.walls) {
      if (wall.id === excludeWallId) continue;

      const line: Line2D = { start: wall.start, end: wall.end };

      if (this.config.snapToEndpoints) {
        const distStart = distance(point, wall.start);
        if (distStart < threshold) {
          targets.push({
            type: 'endpoint',
            point: { ...wall.start },
            distance: distStart,
            wallId: wall.id,
          });
        }

        const distEnd = distance(point, wall.end);
        if (distEnd < threshold) {
          targets.push({
            type: 'endpoint',
            point: { ...wall.end },
            distance: distEnd,
            wallId: wall.id,
          });
        }
      }

      if (this.config.snapToMidpoints) {
        const midpoint = {
          x: (wall.start.x + wall.end.x) / 2,
          y: (wall.start.y + wall.end.y) / 2,
        };
        const distMid = distance(point, midpoint);
        if (distMid < threshold) {
          targets.push({
            type: 'midpoint',
            point: midpoint,
            distance: distMid,
            wallId: wall.id,
          });
        }
      }

      if (this.config.snapToLines) {
        const distToLine = pointToLineDistance(point, line);
        if (distToLine < threshold) {
          const closest = closestPointOnLine(point, line);
          if (!pointsEqual(closest, wall.start) && !pointsEqual(closest, wall.end)) {
            targets.push({
              type: 'line',
              point: closest,
              distance: distToLine,
              wallId: wall.id,
            });
          }
        }
      }
    }

    if (this.config.snapToIntersections) {
      for (let i = 0; i < this.walls.length; i++) {
        for (let j = i + 1; j < this.walls.length; j++) {
          if (this.walls[i].id === excludeWallId || this.walls[j].id === excludeWallId) continue;

          const intersection = this.findIntersection(this.walls[i], this.walls[j]);
          if (intersection) {
            const dist = distance(point, intersection);
            if (dist < threshold) {
              targets.push({
                type: 'intersection',
                point: intersection,
                distance: dist,
              });
            }
          }
        }
      }
    }

    if (targets.length === 0) return null;

    targets.sort((a, b) => a.distance - b.distance);
    return targets[0];
  }

  private findIntersection(wall1: Wall, wall2: Wall): Point2D | null {
    const line1 = { start: wall1.start, end: wall1.end };
    const line2 = { start: wall2.start, end: wall2.end };

    const denom =
      (line2.end.y - line2.start.y) * (line1.end.x - line1.start.x) -
      (line2.end.x - line2.start.x) * (line1.end.y - line1.start.y);

    if (Math.abs(denom) < 0.0001) return null;

    const ua =
      ((line2.end.x - line2.start.x) * (line1.start.y - line2.start.y) -
        (line2.end.y - line2.start.y) * (line1.start.x - line2.start.x)) /
      denom;
    const ub =
      ((line1.end.x - line1.start.x) * (line1.start.y - line2.start.y) -
        (line1.end.y - line1.start.y) * (line1.start.x - line2.start.x)) /
      denom;

    if (ua >= 0 && ua <= 1 && ub >= 0 && ub <= 1) {
      return {
        x: line1.start.x + ua * (line1.end.x - line1.start.x),
        y: line1.start.y + ua * (line1.end.y - line1.start.y),
      };
    }

    return null;
  }

  drawSnapIndicator(ctx: CanvasRenderingContext2D, target: SnapTarget, zoom: number): void {
    const size = 8;

    ctx.save();
    ctx.strokeStyle = '#00d4ff';
    ctx.fillStyle = '#00d4ff';
    ctx.lineWidth = 2;

    switch (target.type) {
      case 'endpoint':
        ctx.beginPath();
        ctx.arc(target.point.x * zoom, target.point.y * zoom, size / 2, 0, Math.PI * 2);
        ctx.fill();
        break;
      case 'midpoint':
        ctx.beginPath();
        const mx = target.point.x * zoom;
        const my = target.point.y * zoom;
        ctx.moveTo(mx - size / 2, my - size / 2);
        ctx.lineTo(mx + size / 2, my - size / 2);
        ctx.lineTo(mx + size / 2, my + size / 2);
        ctx.lineTo(mx - size / 2, my + size / 2);
        ctx.closePath();
        ctx.fill();
        break;
      case 'intersection':
        ctx.beginPath();
        const ix = target.point.x * zoom;
        const iy = target.point.y * zoom;
        ctx.moveTo(ix, iy - size);
        ctx.lineTo(ix + size, iy);
        ctx.lineTo(ix, iy + size);
        ctx.lineTo(ix - size, iy);
        ctx.closePath();
        ctx.fill();
        break;
      case 'line':
      case 'perpendicular':
        ctx.beginPath();
        ctx.arc(target.point.x * zoom, target.point.y * zoom, size / 3, 0, Math.PI * 2);
        ctx.stroke();
        break;
    }

    ctx.restore();
  }
}
