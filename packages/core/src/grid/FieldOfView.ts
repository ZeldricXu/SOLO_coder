import type { CubeCoords, FieldOfViewResult, Viewer, ViewBlocker, Direction } from '../types';
import { HEX_DIRECTIONS } from '../types';
import {
  cubeKey, cubeDistance, cubeLine, cubeEquals, cubeNeighbors, parseCubeKey, cubeAdd, cubeMultiply
} from './coords';
import type { HexGrid } from './HexGrid';
import { terrainRegistry } from './TerrainConfig';
import { serializeSet, deserializeSet } from '../utils/serialization';

interface ShadowInterval {
  start: number;
  end: number;
}

interface FOVOptions {
  revealTiles?: boolean;
  includeShadows?: boolean;
  lightFalloff?: boolean;
  minLightLevel?: number;
  customBlockers?: ViewBlocker[];
}

interface LineOfSightResult {
  visible: boolean;
  blockedBy?: CubeCoords;
  path: CubeCoords[];
}

export class FieldOfViewCalculator {
  private grid: HexGrid;
  private customBlockers: Map<string, ViewBlocker>;
  private lastFOV: FieldOfViewResult | null;

  constructor(grid: HexGrid) {
    this.grid = grid;
    this.customBlockers = new Map();
    this.lastFOV = null;
  }

  calculateFOV(
    viewer: Viewer,
    options: FOVOptions = {}
  ): FieldOfViewResult {
    const visible: Set<string> = new Set();
    const revealed: Set<string> = new Set();
    const blocked: Set<string> = new Set();
    const shadows: Set<string> = new Set();

    const centerKey = cubeKey(viewer.coords);
    visible.add(centerKey);
    revealed.add(centerKey);

    const addCustomBlockers = options.customBlockers ?? [];
    const allBlockers = new Map(this.customBlockers);
    for (const blocker of addCustomBlockers) {
      allBlockers.set(cubeKey(blocker.coords), blocker);
    }

    for (let direction = 0; direction < 6; direction++) {
      this.castShadow(
        viewer.coords,
        viewer.visionRange,
        direction,
        viewer.height,
        visible,
        revealed,
        blocked,
        shadows,
        allBlockers,
        options
      );
    }

    if (options.revealTiles) {
      for (const key of visible) {
        this.grid.setVisibility(parseCubeKey(key), true, true);
      }
    }

    const result: FieldOfViewResult = {
      visible,
      revealed,
      blocked,
      shadows,
    };

    this.lastFOV = result;
    return result;
  }

  lineOfSight(
    from: CubeCoords,
    to: CubeCoords,
    visionRange: number = Infinity,
    options: FOVOptions = {}
  ): LineOfSightResult {
    const distance = cubeDistance(from, to);

    if (distance > visionRange) {
      return { visible: false, path: [], blockedBy: undefined };
    }

    if (cubeEquals(from, to)) {
      return { visible: true, path: [{ ...from }], blockedBy: undefined };
    }

    const line = cubeLine(from, to);
    const allBlockers = new Map(this.customBlockers);
    const addCustomBlockers = options.customBlockers ?? [];
    for (const blocker of addCustomBlockers) {
      allBlockers.set(cubeKey(blocker.coords), blocker);
    }

    for (let i = 1; i < line.length; i++) {
      const coords = line[i];

      if (cubeEquals(coords, to)) {
        return { visible: true, path: line, blockedBy: undefined };
      }

      if (this.isBlocked(coords, from, allBlockers)) {
        return {
          visible: false,
          path: line.slice(0, i + 1),
          blockedBy: coords,
        };
      }
    }

    return { visible: true, path: line, blockedBy: undefined };
  }

  isBlocked(
    coords: CubeCoords,
    from?: CubeCoords,
    customBlockers: Map<string, ViewBlocker> = this.customBlockers
  ): boolean {
    const tile = this.grid.getTile(coords);
    if (!tile) return true;

    if (from && customBlockers.has(cubeKey(coords))) {
      const blocker = customBlockers.get(cubeKey(coords));
      if (blocker && blocker.opacity >= 1) {
        return true;
      }
    }

    const terrainBlocksVision = terrainRegistry.blocksVision(tile.terrain);
    if (terrainBlocksVision) {
      return true;
    }

    if (tile.units.length > 0 && from && !cubeEquals(from, coords)) {
      return true;
    }

    const entitiesOnTile = this.grid.getEntitiesAtTile(coords);
    const entityBlocksVision = entitiesOnTile.some(e => e.blocksVision && !e.isDestroyed);
    if (entityBlocksVision && from && !cubeEquals(from, coords)) {
      return true;
    }

    if (customBlockers.has(cubeKey(coords))) {
      const blocker = customBlockers.get(cubeKey(coords));
      if (blocker && blocker.opacity >= 0.8) {
        return true;
      }
    }

    return false;
  }

  getBlockerOpacity(
    coords: CubeCoords,
    viewerHeight: number,
    customBlockers: Map<string, ViewBlocker>
  ): number {
    const tile = this.grid.getTile(coords);
    if (!tile) return 1;

    let opacity = 0;
    const terrainConfig = terrainRegistry.get(tile.terrain);
    if (terrainConfig.blocksVision) {
      opacity = 1;
    }

    if (tile.units.length > 0) {
      opacity = Math.max(opacity, 0.5);
    }

    const entitiesOnTile = this.grid.getEntitiesAtTile(coords);
    for (const entity of entitiesOnTile) {
      if (entity.blocksVision && !entity.isDestroyed) {
        opacity = Math.max(opacity, 1);
      }
    }

    if (customBlockers.has(cubeKey(coords))) {
      const blocker = customBlockers.get(cubeKey(coords))!;
      opacity = Math.max(opacity, blocker.opacity);
    }

    const heightDiff = tile.height - viewerHeight;
    if (heightDiff > 1) {
      opacity = Math.max(opacity, Math.min(1, (heightDiff - 1) * 0.5));
    }

    return opacity;
  }

  addCustomBlocker(blocker: ViewBlocker): void {
    this.customBlockers.set(cubeKey(blocker.coords), blocker);
  }

  removeCustomBlocker(coords: CubeCoords): void {
    this.customBlockers.delete(cubeKey(coords));
  }

  clearCustomBlockers(): void {
    this.customBlockers.clear();
  }

  getLastFOV(): FieldOfViewResult | null {
    return this.lastFOV;
  }

  applyFOVToGrid(fov: FieldOfViewResult): void {
    this.grid.resetVisibility();
    for (const key of fov.visible) {
      this.grid.setVisibility(parseCubeKey(key), true, true);
    }
    for (const key of fov.revealed) {
      const tile = this.grid.getTile(parseCubeKey(key));
      if (tile && !tile.isVisible) {
        tile.isRevealed = true;
      }
    }
  }

  private castShadow(
    center: CubeCoords,
    range: number,
    direction: number,
    viewerHeight: number,
    visible: Set<string>,
    revealed: Set<string>,
    blocked: Set<string>,
    shadows: Set<string>,
    customBlockers: Map<string, ViewBlocker>,
    options: FOVOptions
  ): void {
    const intervals: ShadowInterval[] = [{ start: 0, end: 1 }];

    for (let row = 1; row <= range; row++) {
      if (intervals.length === 0) break;

      const rowCoords = this.getRowCoords(center, direction, row);

      for (let col = 0; col < rowCoords.length; col++) {
        const tileCoords = rowCoords[col];

        if (!this.grid.hasTile(tileCoords)) continue;

        const tileKey = cubeKey(tileCoords);

        if (cubeDistance(center, tileCoords) > range) continue;

        const colStart = col / row;
        const colEnd = (col + 1) / row;

        const overlapInterval = this.findOverlapInterval(colStart, colEnd, intervals);

        if (overlapInterval.length === 0) continue;

        const opacity = this.getBlockerOpacity(tileCoords, viewerHeight, customBlockers);

        if (opacity < 1) {
          for (const interval of overlapInterval) {
            const visibility = this.calculateVisibility(interval, colStart, colEnd);
            if (visibility > (options.minLightLevel ?? 0.1)) {
              visible.add(tileKey);
              revealed.add(tileKey);

              if (options.includeShadows && visibility < 0.7) {
                shadows.add(tileKey);
              }
            }
          }
        } else {
          blocked.add(tileKey);
          this.subtractIntervals(intervals, colStart, colEnd);
        }
      }
    }
  }

  private getRowCoords(
    center: CubeCoords,
    direction: number,
    row: number
  ): CubeCoords[] {
    const result: CubeCoords[] = [];
    const dirA = HEX_DIRECTIONS[direction as Direction];
    const dirB = HEX_DIRECTIONS[((direction + 2) % 6) as Direction];

    const startTile = cubeAdd(center, cubeMultiply(dirA, row));

    for (let step = 0; step <= row; step++) {
      result.push(cubeAdd(startTile, cubeMultiply(dirB, step)));
    }

    return result;
  }

  private findOverlapInterval(
    start: number,
    end: number,
    intervals: ShadowInterval[]
  ): ShadowInterval[] {
    const result: ShadowInterval[] = [];

    for (const interval of intervals) {
      const overlapStart = Math.max(start, interval.start);
      const overlapEnd = Math.min(end, interval.end);

      if (overlapStart < overlapEnd) {
        result.push({ start: overlapStart, end: overlapEnd });
      }
    }

    return result;
  }

  private calculateVisibility(
    overlap: ShadowInterval,
    tileStart: number,
    tileEnd: number
  ): number {
    const overlapSize = overlap.end - overlap.start;
    const tileSize = tileEnd - tileStart;
    if (tileSize === 0) return 0;
    return overlapSize / tileSize;
  }

  private subtractIntervals(
    intervals: ShadowInterval[],
    start: number,
    end: number
  ): void {
    const newIntervals: ShadowInterval[] = [];

    for (const interval of intervals) {
      if (end <= interval.start || start >= interval.end) {
        newIntervals.push(interval);
      } else {
        if (start > interval.start) {
          newIntervals.push({ start: interval.start, end: Math.min(start, interval.end) });
        }
        if (end < interval.end) {
          newIntervals.push({ start: Math.max(end, interval.start), end: interval.end });
        }
      }
    }

    intervals.length = 0;
    for (const interval of newIntervals) {
      if (interval.end - interval.start > 0.001) {
        intervals.push(interval);
      }
    }
  }

  toJSON(): Record<string, unknown> {
    return {
      customBlockers: Array.from(this.customBlockers.entries()).map(([key, blocker]) => ({
        key,
        blocker: {
          coords: blocker.coords,
          opacity: blocker.opacity,
          height: blocker.height,
        },
      })),
      lastFOV: this.lastFOV
        ? {
            visible: serializeSet(this.lastFOV.visible, (v) => v),
            revealed: serializeSet(this.lastFOV.revealed, (v) => v),
            blocked: serializeSet(this.lastFOV.blocked, (v) => v),
            shadows: serializeSet(this.lastFOV.shadows, (v) => v),
          }
        : null,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    const blockersData = data.customBlockers as Array<{ key: string; blocker: ViewBlocker }>;
    this.customBlockers = new Map();
    for (const item of blockersData) {
      this.customBlockers.set(item.key, item.blocker);
    }

    if (data.lastFOV) {
      const fovData = data.lastFOV as Record<string, unknown>;
      this.lastFOV = {
        visible: deserializeSet(fovData.visible as string[], (v) => v),
        revealed: deserializeSet(fovData.revealed as string[], (v) => v),
        blocked: deserializeSet(fovData.blocked as string[], (v) => v),
        shadows: deserializeSet(fovData.shadows as string[], (v) => v),
      };
    } else {
      this.lastFOV = null;
    }
  }

  static fromJSON(grid: HexGrid, data: Record<string, unknown>): FieldOfViewCalculator {
    const fov = new FieldOfViewCalculator(grid);
    fov.fromJSON(data);
    return fov;
  }
}
