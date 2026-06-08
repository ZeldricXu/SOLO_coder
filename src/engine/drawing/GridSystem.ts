import type { Point2D } from '@/types/geometry';

export interface GridConfig {
  size: number;
  majorSpacing: number;
  color: string;
  majorColor: string;
  show: boolean;
}

export class GridSystem {
  private config: GridConfig;

  constructor(config: Partial<GridConfig> = {}) {
    this.config = {
      size: 0.1,
      majorSpacing: 10,
      color: '#2a3040',
      majorColor: '#3a4050',
      show: true,
      ...config,
    };
  }

  setConfig(config: Partial<GridConfig>): void {
    this.config = { ...this.config, ...config };
  }

  getConfig(): GridConfig {
    return { ...this.config };
  }

  snap(point: Point2D): Point2D {
    return {
      x: Math.round(point.x / this.config.size) * this.config.size,
      y: Math.round(point.y / this.config.size) * this.config.size,
    };
  }

  snapToGrid(point: Point2D): Point2D {
    return this.snap(point);
  }

  draw(
    ctx: CanvasRenderingContext2D,
    width: number,
    height: number,
    offset: Point2D,
    zoom: number
  ): void {
    if (!this.config.show) return;

    const gridSize = this.config.size * zoom;
    const majorSpacing = this.config.majorSpacing;

    const startX = Math.floor(-offset.x / gridSize) * gridSize + offset.x;
    const startY = Math.floor(-offset.y / gridSize) * gridSize + offset.y;
    const endX = startX + width + gridSize;
    const endY = startY + height + gridSize;

    ctx.lineWidth = 1;

    for (let x = startX; x < endX; x += gridSize) {
      const gridIndex = Math.round((x - offset.x) / gridSize);
      const isMajor = gridIndex % majorSpacing === 0;

      ctx.beginPath();
      ctx.strokeStyle = isMajor ? this.config.majorColor : this.config.color;
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }

    for (let y = startY; y < endY; y += gridSize) {
      const gridIndex = Math.round((y - offset.y) / gridSize);
      const isMajor = gridIndex % majorSpacing === 0;

      ctx.beginPath();
      ctx.strokeStyle = isMajor ? this.config.majorColor : this.config.color;
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }

    ctx.strokeStyle = '#ff6b35';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(offset.x, 0);
    ctx.lineTo(offset.x, height);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, offset.y);
    ctx.lineTo(width, offset.y);
    ctx.stroke();
  }

  worldToScreen(world: Point2D, offset?: Point2D, zoom?: number): Point2D {
    const off = offset || { x: 0, y: 60 };
    const z = zoom || 80;
    return {
      x: world.x * z + off.x,
      y: world.y * z + off.y,
    };
  }

  screenToWorld(screen: Point2D, offset?: Point2D, zoom?: number): Point2D {
    const off = offset || { x: 0, y: 60 };
    const z = zoom || 80;
    return {
      x: (screen.x - off.x) / z,
      y: (screen.y - off.y) / z,
    };
  }
}
