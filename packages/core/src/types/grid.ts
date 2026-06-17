import type { ID, TerrainType, TileConfig, Direction, Faction } from './common';

export interface CubeCoords {
  q: number;
  r: number;
  s: number;
}

export interface OffsetCoords {
  col: number;
  row: number;
}

export interface HexTile {
  id: ID;
  coords: CubeCoords;
  offsetCoords: OffsetCoords;
  terrain: TerrainType;
  height: number;
  units: ID[];
  objects: ID[];
  isVisible: boolean;
  isRevealed: boolean;
  lightLevel: number;
  lastVisited: number;
}

export interface HexGridConfig {
  radius?: number;
  width?: number;
  height?: number;
  orientation: 'pointy' | 'flat';
  defaultTerrain: TerrainType;
  tileSize: number;
}

export interface PathNode {
  coords: CubeCoords;
  cost: number;
  moveCost: number;
  distance: number;
  parent: PathNode | null;
}

export interface PathResult {
  path: CubeCoords[];
  totalCost: number;
  reachable: boolean;
  distance: number;
}

export interface FieldOfViewResult {
  visible: Set<string>;
  revealed: Set<string>;
  blocked: Set<string>;
  shadows: Set<string>;
}

export interface ViewBlocker {
  coords: CubeCoords;
  opacity: number;
  height: number;
}

export interface Viewer {
  id: ID;
  coords: CubeCoords;
  visionRange: number;
  height: number;
  faction: Faction;
}

export interface MapGeneratorConfig {
  seed: number;
  width: number;
  height: number;
  scale: number;
  octaves: number;
  persistence: number;
  lacunarity: number;
  terrainThresholds: Array<{
    maxHeight: number;
    terrain: TerrainType;
  }>;
  randomFeatures: {
    forestDensity: number;
    mountainDensity: number;
    waterDensity: number;
    roadProbability: number;
  };
}

export interface GridQueryOptions {
  includeTerrain?: TerrainType[];
  excludeTerrain?: TerrainType[];
  hasUnit?: boolean;
  hasObject?: boolean;
  isVisible?: boolean;
  minHeight?: number;
  maxHeight?: number;
  maxDistance?: number;
}

export const HEX_DIRECTIONS: Record<Direction, CubeCoords> = {
  0: { q: 1, r: -1, s: 0 },
  1: { q: 1, r: 0, s: -1 },
  2: { q: 0, r: 1, s: -1 },
  3: { q: -1, r: 1, s: 0 },
  4: { q: -1, r: 0, s: 1 },
  5: { q: 0, r: -1, s: 1 },
};

export const HEX_DIAGONALS: Record<Direction, CubeCoords> = {
  0: { q: 2, r: -1, s: -1 },
  1: { q: 1, r: 1, s: -2 },
  2: { q: -1, r: 2, s: -1 },
  3: { q: -2, r: 1, s: 1 },
  4: { q: -1, r: -1, s: 2 },
  5: { q: 1, r: -2, s: 1 },
};
