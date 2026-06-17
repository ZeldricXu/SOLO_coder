import type { CubeCoords, OffsetCoords, Direction } from '../types';
import { HEX_DIRECTIONS, HEX_DIAGONALS } from '../types';

export function cubeCoords(q: number, r: number, s: number): CubeCoords {
  if (Math.abs(q + r + s) > 0.001) {
    throw new Error(`Cube coordinates must sum to 0: q+ r + s = ${q + r + s}`);
  }
  return { q, r, s };
}

export function cubeEquals(a: CubeCoords, b: CubeCoords): boolean {
  return a.q === b.q && a.r === b.r && a.s === b.s;
}

export function cubeAdd(a: CubeCoords, b: CubeCoords): CubeCoords {
  return { q: a.q + b.q, r: a.r + b.r, s: a.s + b.s };
}

export function cubeSubtract(a: CubeCoords, b: CubeCoords): CubeCoords {
  return { q: a.q - b.q, r: a.r - b.r, s: a.s - b.s };
}

export function cubeMultiply(a: CubeCoords, scalar: number): CubeCoords {
  return { q: a.q * scalar, r: a.r * scalar, s: a.s * scalar };
}

export function cubeDistance(a: CubeCoords, b: CubeCoords): number {
  return (Math.abs(a.q - b.q) + Math.abs(a.r - b.r) + Math.abs(a.s - b.s)) / 2;
}

export function cubeLength(coords: CubeCoords): number {
  return (Math.abs(coords.q) + Math.abs(coords.r) + Math.abs(coords.s)) / 2;
}

export function cubeDirection(direction: Direction): CubeCoords {
  return HEX_DIRECTIONS[direction];
}

export function cubeNeighbor(coords: CubeCoords, direction: Direction): CubeCoords {
  return cubeAdd(coords, cubeDirection(direction));
}

export function cubeDiagonalDirection(direction: Direction): CubeCoords {
  return HEX_DIAGONALS[direction];
}

export function cubeDiagonalNeighbor(coords: CubeCoords, direction: Direction): CubeCoords {
  return cubeAdd(coords, cubeDiagonalDirection(direction));
}

export function cubeNeighbors(coords: CubeCoords): CubeCoords[] {
  return [0, 1, 2, 3, 4, 5].map(dir => cubeNeighbor(coords, dir as Direction));
}

export function cubeDiagonalNeighbors(coords: CubeCoords): CubeCoords[] {
  return [0, 1, 2, 3, 4, 5].map(dir => cubeDiagonalNeighbor(coords, dir as Direction));
}

export function cubeRing(center: CubeCoords, radius: number): CubeCoords[] {
  const results: CubeCoords[] = [];
  if (radius === 0) {
    results.push({ ...center });
    return results;
  }

  let hex = cubeAdd(center, cubeMultiply(cubeDirection(4), radius));
  
  for (let dir = 0; dir < 6; dir++) {
    for (let step = 0; step < radius; step++) {
      results.push({ ...hex });
      hex = cubeNeighbor(hex, dir as Direction);
    }
  }
  
  return results;
}

export function cubeSpiral(center: CubeCoords, maxRadius: number): CubeCoords[] {
  const results: CubeCoords[] = [{ ...center }];
  for (let radius = 1; radius <= maxRadius; radius++) {
    results.push(...cubeRing(center, radius));
  }
  return results;
}

export function cubeLine(a: CubeCoords, b: CubeCoords): CubeCoords[] {
  const distance = cubeDistance(a, b);
  if (distance === 0) return [{ ...a }];
  
  const results: CubeCoords[] = [];
  const step = 1.0 / Math.max(distance, 1);
  
  for (let i = 0; i <= distance; i++) {
    const t = step * i;
    results.push(cubeRound({
      q: a.q + (b.q - a.q) * t,
      r: a.r + (b.r - a.r) * t,
      s: a.s + (b.s - a.s) * t,
    }));
  }
  
  return results;
}

export function cubeRound(coords: CubeCoords): CubeCoords {
  let q = Math.round(coords.q);
  let r = Math.round(coords.r);
  let s = Math.round(coords.s);
  
  const qDiff = Math.abs(q - coords.q);
  const rDiff = Math.abs(r - coords.r);
  const sDiff = Math.abs(s - coords.s);
  
  if (qDiff > rDiff && qDiff > sDiff) {
    q = -r - s;
  } else if (rDiff > sDiff) {
    r = -q - s;
  } else {
    s = -q - r;
  }
  
  return { q: q || 0, r: r || 0, s: s || 0 };
}

export function cubeLerp(a: CubeCoords, b: CubeCoords, t: number): CubeCoords {
  return {
    q: a.q + (b.q - a.q) * t,
    r: a.r + (b.r - a.r) * t,
    s: a.s + (b.s - a.s) * t,
  };
}

export function offsetToCube(offset: OffsetCoords, orientation: 'pointy' | 'flat' = 'pointy'): CubeCoords {
  let q: number, r: number;
  
  if (orientation === 'pointy') {
    q = offset.col - (offset.row - (offset.row & 1)) / 2;
    r = offset.row;
  } else {
    q = offset.col;
    r = offset.row - (offset.col - (offset.col & 1)) / 2;
  }
  
  return { q, r, s: -q - r };
}

export function cubeToOffset(coords: CubeCoords, orientation: 'pointy' | 'flat' = 'pointy'): OffsetCoords {
  let col: number, row: number;
  
  if (orientation === 'pointy') {
    col = coords.q + (coords.r - (coords.r & 1)) / 2;
    row = coords.r;
  } else {
    col = coords.q;
    row = coords.r + (coords.q - (coords.q & 1)) / 2;
  }
  
  return { col: Math.round(col), row: Math.round(row) };
}

export function cubeToPixel(coords: CubeCoords, tileSize: number, orientation: 'pointy' | 'flat' = 'pointy'): { x: number; y: number } {
  if (orientation === 'pointy') {
    const x = tileSize * (Math.sqrt(3) * coords.q + Math.sqrt(3) / 2 * coords.r);
    const y = tileSize * (3 / 2 * coords.r);
    return { x, y };
  } else {
    const x = tileSize * (3 / 2 * coords.q);
    const y = tileSize * (Math.sqrt(3) / 2 * coords.q + Math.sqrt(3) * coords.r);
    return { x, y };
  }
}

export function pixelToCube(x: number, y: number, tileSize: number, orientation: 'pointy' | 'flat' = 'pointy'): CubeCoords {
  let q: number, r: number;
  
  if (orientation === 'pointy') {
    q = (Math.sqrt(3) / 3 * x - 1 / 3 * y) / tileSize;
    r = (2 / 3 * y) / tileSize;
  } else {
    q = (2 / 3 * x) / tileSize;
    r = (-1 / 3 * x + Math.sqrt(3) / 3 * y) / tileSize;
  }
  
  return cubeRound({ q, r, s: -q - r });
}

export function cubeKey(coords: CubeCoords): string {
  return `${coords.q},${coords.r},${coords.s}`;
}

export function parseCubeKey(key: string): CubeCoords {
  const [q, r, s] = key.split(',').map(Number);
  return { q, r, s };
}

export function getDirection(from: CubeCoords, to: CubeCoords): Direction {
  const diff = cubeSubtract(to, from);
  const length = cubeLength(diff);
  if (length === 0) return 0;
  
  const normalized = {
    q: Math.round(diff.q / length),
    r: Math.round(diff.r / length),
    s: Math.round(diff.s / length),
  };
  
  for (let dir = 0; dir < 6; dir++) {
    if (cubeEquals(normalized, HEX_DIRECTIONS[dir as Direction])) {
      return dir as Direction;
    }
  }
  
  return 0;
}

export function rotateDirection(direction: Direction, rotation: number): Direction {
  return ((direction + rotation) % 6 + 6) % 6 as Direction;
}

export function oppositeDirection(direction: Direction): Direction {
  return ((direction + 3) % 6) as Direction;
}

export function isInRange(center: CubeCoords, target: CubeCoords, range: number): boolean {
  return cubeDistance(center, target) <= range;
}

export function getTilesInRange(center: CubeCoords, range: number): CubeCoords[] {
  return cubeSpiral(center, range);
}

export function getTilesAtRange(center: CubeCoords, range: number): CubeCoords[] {
  return cubeRing(center, range);
}
