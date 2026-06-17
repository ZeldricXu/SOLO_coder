import type { CubeCoords } from '@tactics/core';
import { pixelToCube as corePixelToCube } from '@tactics/core';

export function getHexCornerPoints(
  centerX: number,
  centerY: number,
  size: number,
  orientation: 'pointy' | 'flat' = 'pointy'
): Array<{ x: number; y: number }> {
  const points: Array<{ x: number; y: number }> = [];
  for (let i = 0; i < 6; i++) {
    const angle = orientation === 'pointy'
      ? (Math.PI / 180) * (60 * i - 30)
      : (Math.PI / 180) * (60 * i);
    points.push({
      x: centerX + size * Math.cos(angle),
      y: centerY + size * Math.sin(angle),
    });
  }
  return points;
}

export function getHexPath(
  centerX: number,
  centerY: number,
  size: number,
  orientation: 'pointy' | 'flat' = 'pointy'
): string {
  const points = getHexCornerPoints(centerX, centerY, size, orientation);
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ') + 'Z';
}

export function getFlatPointsArray(
  centerX: number,
  centerY: number,
  size: number,
  orientation: 'pointy' | 'flat' = 'pointy'
): number[] {
  const points = getHexCornerPoints(centerX, centerY, size, orientation);
  const flat: number[] = [];
  for (const p of points) {
    flat.push(p.x, p.y);
  }
  return flat;
}

export function pixelToHex(
  x: number,
  y: number,
  tileSize: number,
  panX: number,
  panY: number,
  zoom: number,
  orientation: 'pointy' | 'flat' = 'pointy'
): CubeCoords {
  const adjustedX = (x - panX) / zoom;
  const adjustedY = (y - panY) / zoom;
  return corePixelToCube(adjustedX, adjustedY, tileSize, orientation);
}

export function darkenColor(hexColor: string, amount: number = 0.2): string {
  const num = parseInt(hexColor.replace('#', ''), 16);
  const r = Math.max(0, (num >> 16) - Math.round(255 * amount));
  const g = Math.max(0, ((num >> 8) & 0x00ff) - Math.round(255 * amount));
  const b = Math.max(0, (num & 0x0000ff) - Math.round(255 * amount));
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`;
}

export function lightenColor(hexColor: string, amount: number = 0.2): string {
  const num = parseInt(hexColor.replace('#', ''), 16);
  const r = Math.min(255, (num >> 16) + Math.round(255 * amount));
  const g = Math.min(255, ((num >> 8) & 0x00ff) + Math.round(255 * amount));
  const b = Math.min(255, (num & 0x0000ff) + Math.round(255 * amount));
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`;
}

export function getFactionColor(faction: string): string {
  switch (faction) {
    case 'player':
      return '#2196F3';
    case 'enemy':
      return '#F44336';
    case 'neutral':
      return '#9E9E9E';
    default:
      return '#FF9800';
  }
}
