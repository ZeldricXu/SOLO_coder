import type { 
  HexTile, 
  HexGridConfig, 
  CubeCoords, 
  OffsetCoords,
  GridQueryOptions,
  TerrainType,
  ID
} from '../types';
import { 
  cubeKey, 
  offsetToCube, 
  cubeToOffset, 
  cubeEquals,
  cubeNeighbors,
  cubeDistance,
  isInRange,
  cubeSpiral
} from './coords';
import { terrainRegistry } from './TerrainConfig';
import { generateId } from '../utils';

export class HexGrid {
  private tiles: Map<string, HexTile>;
  private config: HexGridConfig;
  private width: number;
  private height: number;

  constructor(config: HexGridConfig) {
    this.config = config;
    this.tiles = new Map();
    
    if (config.radius !== undefined) {
      this.initializeHexGrid(config.radius);
      this.width = config.radius * 2 + 1;
      this.height = config.radius * 2 + 1;
    } else if (config.width !== undefined && config.height !== undefined) {
      this.initializeRectGrid(config.width, config.height);
      this.width = config.width;
      this.height = config.height;
    } else {
      throw new Error('HexGrid must be initialized with either radius or width/height');
    }
  }

  private initializeHexGrid(radius: number): void {
    for (let q = -radius; q <= radius; q++) {
      const r1 = Math.max(-radius, -q - radius);
      const r2 = Math.min(radius, -q + radius);
      for (let r = r1; r <= r2; r++) {
        const s = -q - r;
        this.createTile({ q, r, s });
      }
    }
  }

  private initializeRectGrid(width: number, height: number): void {
    for (let row = 0; row < height; row++) {
      for (let col = 0; col < width; col++) {
        const coords = offsetToCube({ col, row }, this.config.orientation);
        this.createTile(coords, { col, row });
      }
    }
  }

  private createTile(coords: CubeCoords, offsetCoords?: OffsetCoords): HexTile {
    const key = cubeKey(coords);
    const tile: HexTile = {
      id: generateId(),
      coords,
      offsetCoords: offsetCoords || cubeToOffset(coords, this.config.orientation),
      terrain: this.config.defaultTerrain,
      height: 0,
      units: [],
      objects: [],
      isVisible: false,
      isRevealed: false,
      lightLevel: 1,
      lastVisited: -1,
    };
    this.tiles.set(key, tile);
    return tile;
  }

  getTile(coords: CubeCoords): HexTile | undefined {
    return this.tiles.get(cubeKey(coords));
  }

  getTileByOffset(offset: OffsetCoords): HexTile | undefined {
    const coords = offsetToCube(offset, this.config.orientation);
    return this.getTile(coords);
  }

  hasTile(coords: CubeCoords): boolean {
    return this.tiles.has(cubeKey(coords));
  }

  setTileTerrain(coords: CubeCoords, terrain: TerrainType): void {
    const tile = this.getTile(coords);
    if (tile) {
      tile.terrain = terrain;
    }
  }

  setTileHeight(coords: CubeCoords, height: number): void {
    const tile = this.getTile(coords);
    if (tile) {
      tile.height = height;
    }
  }

  getHeight(coords: CubeCoords): number {
    return this.getTile(coords)?.height ?? 0;
  }

  getTerrain(coords: CubeCoords): TerrainType {
    return this.getTile(coords)?.terrain ?? this.config.defaultTerrain;
  }

  addUnit(coords: CubeCoords, unitId: ID): void {
    const tile = this.getTile(coords);
    if (tile && !tile.units.includes(unitId)) {
      tile.units.push(unitId);
    }
  }

  removeUnit(coords: CubeCoords, unitId: ID): void {
    const tile = this.getTile(coords);
    if (tile) {
      tile.units = tile.units.filter(id => id !== unitId);
    }
  }

  moveUnit(from: CubeCoords, to: CubeCoords, unitId: ID): void {
    this.removeUnit(from, unitId);
    this.addUnit(to, unitId);
  }

  addObject(coords: CubeCoords, objectId: ID): void {
    const tile = this.getTile(coords);
    if (tile && !tile.objects.includes(objectId)) {
      tile.objects.push(objectId);
    }
  }

  removeObject(coords: CubeCoords, objectId: ID): void {
    const tile = this.getTile(coords);
    if (tile) {
      tile.objects = tile.objects.filter(id => id !== objectId);
    }
  }

  getNeighbors(coords: CubeCoords): HexTile[] {
    const neighbors: HexTile[] = [];
    for (const neighbor of cubeNeighbors(coords)) {
      const tile = this.getTile(neighbor);
      if (tile) {
        neighbors.push(tile);
      }
    }
    return neighbors;
  }

  getNeighborCoords(coords: CubeCoords): CubeCoords[] {
    return cubeNeighbors(coords).filter(n => this.hasTile(n));
  }

  getTilesInRange(center: CubeCoords, range: number): HexTile[] {
    const tiles: HexTile[] = [];
    for (const coords of cubeSpiral(center, range)) {
      const tile = this.getTile(coords);
      if (tile) {
        tiles.push(tile);
      }
    }
    return tiles;
  }

  getTilesAtRange(center: CubeCoords, range: number): HexTile[] {
    if (range === 0) {
      const tile = this.getTile(center);
      return tile ? [tile] : [];
    }
    
    const tiles: HexTile[] = [];
    const minRadius = range - 1;
    
    for (const coords of cubeSpiral(center, range)) {
      const distance = cubeDistance(center, coords);
      if (distance > minRadius && distance <= range) {
        const tile = this.getTile(coords);
        if (tile) {
          tiles.push(tile);
        }
      }
    }
    return tiles;
  }

  queryTiles(center: CubeCoords, options: GridQueryOptions): HexTile[] {
    const maxDistance = options.maxDistance ?? Infinity;
    const tiles: HexTile[] = [];
    
    for (const tile of this.getAllTiles()) {
      const distance = cubeDistance(center, tile.coords);
      if (distance > maxDistance) continue;
      
      if (options.includeTerrain && !options.includeTerrain.includes(tile.terrain)) continue;
      if (options.excludeTerrain && options.excludeTerrain.includes(tile.terrain)) continue;
      if (options.hasUnit !== undefined && tile.units.length > 0 !== options.hasUnit) continue;
      if (options.hasObject !== undefined && tile.objects.length > 0 !== options.hasObject) continue;
      if (options.isVisible !== undefined && tile.isVisible !== options.isVisible) continue;
      if (options.minHeight !== undefined && tile.height < options.minHeight) continue;
      if (options.maxHeight !== undefined && tile.height > options.maxHeight) continue;
      
      tiles.push(tile);
    }
    
    return tiles;
  }

  getMoveCost(from: CubeCoords, to: CubeCoords): number {
    const tile = this.getTile(to);
    if (!tile) return Infinity;
    
    const config = terrainRegistry.get(tile.terrain);
    if (config.blocksMovement) return Infinity;
    
    const heightDiff = Math.abs(this.getHeight(from) - tile.height);
    const heightCost = heightDiff > 1 ? heightDiff * 0.5 : 0;
    
    return config.moveCost + heightCost;
  }

  blocksVision(coords: CubeCoords): boolean {
    const tile = this.getTile(coords);
    if (!tile) return true;
    return terrainRegistry.blocksVision(tile.terrain) || tile.units.length > 0;
  }

  blocksMovement(coords: CubeCoords): boolean {
    const tile = this.getTile(coords);
    if (!tile) return true;
    return terrainRegistry.blocksMovement(tile.terrain) || tile.units.length > 0;
  }

  getVisibility(coords: CubeCoords): { isVisible: boolean; isRevealed: boolean } {
    const tile = this.getTile(coords);
    if (!tile) return { isVisible: false, isRevealed: false };
    return { isVisible: tile.isVisible, isRevealed: tile.isRevealed };
  }

  setVisibility(coords: CubeCoords, visible: boolean, revealed?: boolean): void {
    const tile = this.getTile(coords);
    if (tile) {
      tile.isVisible = visible;
      if (revealed !== undefined) {
        tile.isRevealed = revealed;
      } else if (visible) {
        tile.isRevealed = true;
      }
    }
  }

  resetVisibility(): void {
    for (const tile of this.tiles.values()) {
      tile.isVisible = false;
    }
  }

  getAllTiles(): HexTile[] {
    return Array.from(this.tiles.values());
  }

  getTileCount(): number {
    return this.tiles.size;
  }

  getBounds(): { minQ: number; maxQ: number; minR: number; maxR: number; minS: number; maxS: number } {
    let minQ = Infinity, maxQ = -Infinity;
    let minR = Infinity, maxR = -Infinity;
    let minS = Infinity, maxS = -Infinity;
    
    for (const tile of this.tiles.values()) {
      minQ = Math.min(minQ, tile.coords.q);
      maxQ = Math.max(maxQ, tile.coords.q);
      minR = Math.min(minR, tile.coords.r);
      maxR = Math.max(maxR, tile.coords.r);
      minS = Math.min(minS, tile.coords.s);
      maxS = Math.max(maxS, tile.coords.s);
    }
    
    return { minQ, maxQ, minR, maxR, minS, maxS };
  }

  getConfig(): Readonly<HexGridConfig> {
    return { ...this.config };
  }

  getDimensions(): { width: number; height: number } {
    return { width: this.width, height: this.height };
  }

  forEach(callback: (tile: HexTile) => void): void {
    this.tiles.forEach(callback);
  }

  findTile(predicate: (tile: HexTile) => boolean): HexTile | undefined {
    for (const tile of this.tiles.values()) {
      if (predicate(tile)) return tile;
    }
    return undefined;
  }

  filterTiles(predicate: (tile: HexTile) => boolean): HexTile[] {
    const result: HexTile[] = [];
    for (const tile of this.tiles.values()) {
      if (predicate(tile)) result.push(tile);
    }
    return result;
  }

  clearUnits(): void {
    for (const tile of this.tiles.values()) {
      tile.units = [];
    }
  }

  clearObjects(): void {
    for (const tile of this.tiles.values()) {
      tile.objects = [];
    }
  }

  clone(): HexGrid {
    const newGrid = new HexGrid(this.config);
    for (const [key, tile] of this.tiles.entries()) {
      newGrid.tiles.set(key, {
        ...tile,
        coords: { ...tile.coords },
        offsetCoords: { ...tile.offsetCoords },
        units: [...tile.units],
        objects: [...tile.objects],
      });
    }
    return newGrid;
  }

  toJSON(): Record<string, unknown> {
    return {
      config: this.config,
      width: this.width,
      height: this.height,
      tiles: Array.from(this.tiles.entries()).map(([key, tile]) => ({
        key,
        data: {
          ...tile,
          units: [...tile.units],
          objects: [...tile.objects],
        }
      })),
    };
  }

  static fromJSON(data: Record<string, unknown>): HexGrid {
    const config = data.config as HexGridConfig;
    const grid = new HexGrid(config);
    grid.width = data.width as number;
    grid.height = data.height as number;
    
    const tilesData = data.tiles as Array<{ key: string; data: Record<string, unknown> }>;
    for (const tileData of tilesData) {
      grid.tiles.set(tileData.key, tileData.data as unknown as HexTile);
    }
    
    return grid;
  }
}
