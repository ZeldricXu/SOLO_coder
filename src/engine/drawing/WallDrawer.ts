import type { Point2D } from '@/types/geometry';
import type { Wall } from '@/types/floorplan';
import { distance, getWallPoints, angle, midpoint } from '@/utils/geometry';
import { formatLength } from '@/utils/math';

export interface DrawOptions {
  wallColor: string;
  wallThickness: number;
  selectedColor: string;
  hoverColor: string;
  showDimensions: boolean;
}

const DEFAULT_OPTIONS: DrawOptions = {
  wallColor: '#ffffff',
  wallThickness: 0.2,
  selectedColor: '#ff6b35',
  hoverColor: '#00d4ff',
  showDimensions: true,
};

export class WallDrawer {
  private options: DrawOptions;

  constructor(options: Partial<DrawOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options };
  }

  setOptions(options: Partial<DrawOptions>): void {
    this.options = { ...this.options, ...options };
  }

  drawWall(
    ctx: CanvasRenderingContext2D,
    wall: Wall,
    zoom: number,
    offset: Point2D,
    isSelected: boolean = false,
    isHovered: boolean = false
  ): void {
    const points = getWallPoints(wall);
    if (points.length < 2) return;

    const thickness = wall.thickness * zoom;
    const color = isSelected ? this.options.selectedColor : isHovered ? this.options.hoverColor : this.options.wallColor;

    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = thickness;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    ctx.beginPath();
    ctx.moveTo(points[0].x * zoom + offset.x, points[0].y * zoom + offset.y);
    for (let i = 1; i < points.length; i++) {
      ctx.lineTo(points[i].x * zoom + offset.x, points[i].y * zoom + offset.y);
    }
    ctx.stroke();

    ctx.strokeStyle = isSelected || isHovered ? color : '#888888';
    ctx.lineWidth = 1;
    ctx.beginPath();
    const perpAng = angle(points[0], points[1]) + Math.PI / 2;
    const halfThick = thickness / 2;

    for (let side = -1; side <= 1; side += 2) {
      ctx.beginPath();
      for (let i = 0; i < points.length; i++) {
        const px = points[i].x + Math.cos(perpAng) * (wall.thickness / 2) * side;
        const py = points[i].y + Math.sin(perpAng) * (wall.thickness / 2) * side;
        if (i === 0) {
          ctx.moveTo(px * zoom + offset.x, py * zoom + offset.y);
        } else {
          ctx.lineTo(px * zoom + offset.x, py * zoom + offset.y);
        }
      }
      ctx.stroke();
    }

    ctx.fillStyle = color;
    for (const p of [wall.start, wall.end]) {
      ctx.beginPath();
      ctx.arc(p.x * zoom + offset.x, p.y * zoom + offset.y, 4, 0, Math.PI * 2);
      ctx.fill();
    }

    ctx.restore();

    if (this.options.showDimensions) {
      this.drawDimension(ctx, wall, zoom, offset);
    }
  }

  drawPreview(
    ctx: CanvasRenderingContext2D,
    start: Point2D,
    end: Point2D,
    zoom: number,
    offset: Point2D,
    wallType: 'straight' | 'arc' = 'straight',
    center?: Point2D
  ): void {
    const thickness = this.options.wallThickness * zoom;

    ctx.save();
    ctx.strokeStyle = '#00d4ff';
    ctx.lineWidth = thickness;
    ctx.setLineDash([10, 5]);
    ctx.lineCap = 'round';

    ctx.beginPath();
    if (wallType === 'arc' && center) {
      const radius = distance(center, start);
      const startAng = angle(center, start);
      const endAng = angle(center, end);
      ctx.arc(center.x * zoom + offset.x, center.y * zoom + offset.y, radius * zoom, startAng, endAng);
    } else {
      ctx.moveTo(start.x * zoom + offset.x, start.y * zoom + offset.y);
      ctx.lineTo(end.x * zoom + offset.x, end.y * zoom + offset.y);
    }
    ctx.stroke();

    ctx.fillStyle = '#ff6b35';
    ctx.beginPath();
    ctx.arc(start.x * zoom + offset.x, start.y * zoom + offset.y, 5, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#00d4ff';
    ctx.beginPath();
    ctx.arc(end.x * zoom + offset.x, end.y * zoom + offset.y, 5, 0, Math.PI * 2);
    ctx.fill();

    ctx.restore();

    if (this.options.showDimensions) {
      const previewWall: Wall = {
        id: 'preview',
        type: wallType,
        start,
        end,
        center,
        thickness: this.options.wallThickness,
        height: 2.8,
        materialId: 'mat-wall-white',
      };
      this.drawDimension(ctx, previewWall, zoom, offset, true);
    }
  }

  private drawDimension(
    ctx: CanvasRenderingContext2D,
    wall: Wall,
    zoom: number,
    offset: Point2D,
    isPreview: boolean = false
  ): void {
    const mid = midpoint(wall.start, wall.end);
    const len = distance(wall.start, wall.end);
    const wallAngle = angle(wall.start, wall.end);
    const perpAngle = wallAngle + Math.PI / 2;
    const offsetDist = 30 / zoom;

    const labelPos = {
      x: mid.x + Math.cos(perpAngle) * offsetDist,
      y: mid.y + Math.sin(perpAngle) * offsetDist,
    };

    const screenX = labelPos.x * zoom + offset.x;
    const screenY = labelPos.y * zoom + offset.y;

    ctx.save();
    ctx.fillStyle = isPreview ? '#00d4ff' : '#adb5bd';
    ctx.font = '11px JetBrains Mono, monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';

    const text = formatLength(len);
    const padding = 4;
    const metrics = ctx.measureText(text);
    const textWidth = metrics.width + padding * 2;
    const textHeight = 18;

    ctx.fillStyle = 'rgba(26, 31, 46, 0.9)';
    ctx.fillRect(screenX - textWidth / 2, screenY - textHeight / 2, textWidth, textHeight);

    ctx.fillStyle = isPreview ? '#00d4ff' : '#e9ecef';
    ctx.fillText(text, screenX, screenY);

    const startDim = {
      x: wall.start.x + Math.cos(perpAngle) * offsetDist * 0.6,
      y: wall.start.y + Math.sin(perpAngle) * offsetDist * 0.6,
    };
    const endDim = {
      x: wall.end.x + Math.cos(perpAngle) * offsetDist * 0.6,
      y: wall.end.y + Math.sin(perpAngle) * offsetDist * 0.6,
    };

    ctx.strokeStyle = isPreview ? '#00d4ff' : '#6c757d';
    ctx.lineWidth = 1;
    ctx.setLineDash([]);

    ctx.beginPath();
    ctx.moveTo(startDim.x * zoom + offset.x, startDim.y * zoom + offset.y);
    ctx.lineTo(endDim.x * zoom + offset.x, endDim.y * zoom + offset.y);
    ctx.stroke();

    for (const p of [startDim, endDim]) {
      ctx.beginPath();
      const tickAng = perpAngle - Math.PI / 2;
      ctx.moveTo(
        p.x * zoom + offset.x + Math.cos(tickAng) * 4,
        p.y * zoom + offset.y + Math.sin(tickAng) * 4
      );
      ctx.lineTo(
        p.x * zoom + offset.x - Math.cos(tickAng) * 4,
        p.y * zoom + offset.y - Math.sin(tickAng) * 4
      );
      ctx.stroke();
    }

    ctx.restore();
  }

  drawOpening(
    ctx: CanvasRenderingContext2D,
    opening: { type: 'door' | 'window'; position: Point2D; width: number; wallAngle: number },
    zoom: number,
    offset: Point2D,
    isSelected: boolean = false
  ): void {
    const { type, position, width, wallAngle } = opening;
    const halfWidth = width / 2;

    const perpAng = wallAngle + Math.PI / 2;
    const p1 = {
      x: position.x + Math.cos(wallAngle) * halfWidth + Math.cos(perpAng) * 0.12,
      y: position.y + Math.sin(wallAngle) * halfWidth + Math.sin(perpAng) * 0.12,
    };
    const p2 = {
      x: position.x - Math.cos(wallAngle) * halfWidth + Math.cos(perpAng) * 0.12,
      y: position.y - Math.sin(wallAngle) * halfWidth + Math.sin(perpAng) * 0.12,
    };

    ctx.save();
    ctx.strokeStyle = isSelected ? '#ff6b35' : type === 'door' ? '#4ade80' : '#60a5fa';
    ctx.lineWidth = 2;

    ctx.beginPath();
    ctx.moveTo(p1.x * zoom + offset.x, p1.y * zoom + offset.y);
    ctx.lineTo(p2.x * zoom + offset.x, p2.y * zoom + offset.y);
    ctx.stroke();

    if (type === 'door') {
      ctx.beginPath();
      ctx.arc(
        p2.x * zoom + offset.x,
        p2.y * zoom + offset.y,
        width * zoom,
        wallAngle,
        wallAngle + Math.PI / 2
      );
      ctx.stroke();

      ctx.beginPath();
      ctx.moveTo(p2.x * zoom + offset.x, p2.y * zoom + offset.y);
      ctx.lineTo(
        p2.x * zoom + offset.x + Math.cos(wallAngle) * width * zoom,
        p2.y * zoom + offset.y + Math.sin(wallAngle) * width * zoom
      );
      ctx.stroke();
    } else {
      const p3 = {
        x: position.x + Math.cos(wallAngle) * halfWidth - Math.cos(perpAng) * 0.12,
        y: position.y + Math.sin(wallAngle) * halfWidth - Math.sin(perpAng) * 0.12,
      };
      const p4 = {
        x: position.x - Math.cos(wallAngle) * halfWidth - Math.cos(perpAng) * 0.12,
        y: position.y - Math.sin(wallAngle) * halfWidth - Math.sin(perpAng) * 0.12,
      };

      ctx.beginPath();
      ctx.moveTo(p3.x * zoom + offset.x, p3.y * zoom + offset.y);
      ctx.lineTo(p4.x * zoom + offset.x, p4.y * zoom + offset.y);
      ctx.stroke();
    }

    ctx.restore();
  }
}
