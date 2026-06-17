import type { CubeCoords, MapGeneratorConfig, TerrainType, OffsetCoords } from '../types';
import { cubeKey, offsetToCube } from './coords';
import type { HexGrid } from './HexGrid';
import { PerlinNoise, ValueNoise, generateNoiseMap, generateFalloffMap } from '../utils/noise';

interface GeneratedTileData {
  height: number;
  moisture: number;
  temperature: number;
  terrain: TerrainType;
}

interface RandomFeatureResult {
  placedTiles: CubeCoords[];
  placedCount: Record<string, number>;
}

const DEFAULT_CONFIG: MapGeneratorConfig = {
  seed: 12345,
  width: 32,
  height: 32,
  scale: 50,
  octaves: 6,
  persistence: 0.5,
  lacunarity: 2.0,
  terrainThresholds: [
    { maxHeight: 0.25, terrain: 'water' },
    { maxHeight: 0.35, terrain: 'sand' },
    { maxHeight: 0.55, terrain: 'plain' },
    { maxHeight: 0.7, terrain: 'forest' },
    { maxHeight: 0.85, terrain: 'mountain' },
    { maxHeight: 1.0, terrain: 'mountain' },
  ],
  randomFeatures: {
    forestDensity: 0.3,
    mountainDensity: 0.1,
    waterDensity: 0.05,
    roadProbability: 0.1,
  },
};

export class MapGenerator {
  private config: MapGeneratorConfig;
  private heightMap: number[][];
  private moistureMap: number[][];
  private temperatureMap: number[][];
  private tileData: Map<string, GeneratedTileData>;
  private perlin: PerlinNoise;
  private valueNoise: ValueNoise;
  private rand: SeededRandom;

  constructor(config?: Partial<MapGeneratorConfig>) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.heightMap = [];
    this.moistureMap = [];
    this.temperatureMap = [];
    this.tileData = new Map();
    this.perlin = new PerlinNoise(this.config.seed);
    this.valueNoise = new ValueNoise(this.config.seed + 9999);
    this.rand = new SeededRandom(this.config.seed);
  }

  generate(grid: HexGrid): void {
    const { width, height } = grid.getDimensions();
    const config = grid.getConfig();

    this.heightMap = generateNoiseMap(
      width,
      height,
      this.config.seed,
      this.config.scale,
      this.config.octaves,
      this.config.persistence,
      this.config.lacunarity
    );

    this.moistureMap = generateNoiseMap(
      width,
      height,
      this.config.seed + 1000,
      this.config.scale * 1.5,
      this.config.octaves - 1,
      this.config.persistence,
      this.config.lacunarity
    );

    this.temperatureMap = this.generateTemperatureMap(width, height);

    const falloff = generateFalloffMap(width, height, 3);
    this.applyFalloffMap(this.heightMap, falloff);

    this.tileData.clear();

    for (let row = 0; row < height; row++) {
      for (let col = 0; col < width; col++) {
        const offset: OffsetCoords = { col, row };
        const coords = offsetToCube(offset, config.orientation);
        const key = cubeKey(coords);

        const height = this.heightMap[row][col];
        const moisture = this.moistureMap[row][col];
        const temperature = this.temperatureMap[row][col];

        const terrain = this.applyTerrainThresholds(height, moisture, temperature);

        const data: GeneratedTileData = {
          height,
          moisture,
          temperature,
          terrain,
        };

        this.tileData.set(key, data);

        grid.setTileHeight(coords, height * 10);
        grid.setTileTerrain(coords, terrain);
      }
    }

    this.placeRandomFeatures(grid);
  }

  applyTerrainThresholds(
    height: number,
    moisture: number,
    temperature: number
  ): TerrainType {
    const thresholds = [...this.config.terrainThresholds].sort(
      (a, b) => a.maxHeight - b.maxHeight
    );

    let terrain: TerrainType = thresholds[0]?.terrain ?? 'plain';

    for (const threshold of thresholds) {
      if (height <= threshold.maxHeight) {
        terrain = threshold.terrain;
        break;
      }
    }

    terrain = this.applyMoistureModifiers(terrain, height, moisture);
    terrain = this.applyTemperatureModifiers(terrain, height, temperature);

    return terrain;
  }

  placeRandomFeatures(grid: HexGrid): RandomFeatureResult {
    const result: RandomFeatureResult = {
      placedTiles: [],
      placedCount: {},
    };

    result.placedCount = {
      forest: 0,
      mountain: 0,
      water: 0,
      road: 0,
    };

    const allTiles = grid.getAllTiles();
    const shuffledTiles = this.shuffleArray(allTiles);

    for (const tile of shuffledTiles) {
      const data = this.tileData.get(cubeKey(tile.coords));
      if (!data) continue;

      if (this.shouldPlaceForest(tile.terrain, data.moisture)) {
        grid.setTileTerrain(tile.coords, 'forest');
        data.terrain = 'forest';
        result.placedTiles.push(tile.coords);
        result.placedCount['forest']++;
      }

      if (this.shouldPlaceMountain(tile.terrain, data.height, data.moisture)) {
        grid.setTileTerrain(tile.coords, 'mountain');
        data.terrain = 'mountain';
        result.placedTiles.push(tile.coords);
        result.placedCount['mountain']++;
      }

      if (this.shouldPlaceWater(tile.terrain, data.moisture, data.height)) {
        grid.setTileTerrain(tile.coords, 'swamp');
        data.terrain = 'swamp';
        result.placedTiles.push(tile.coords);
        result.placedCount['water']++;
      }
    }

    this.placeRoads(grid, result);

    return result;
  }

  private generateTemperatureMap(width: number, height: number): number[][] {
    const map: number[][] = [];

    for (let y = 0; y < height; y++) {
      map[y] = [];
      for (let x = 0; x < width; x++) {
        const latitude = Math.abs((y / height) - 0.5) * 2;

        const noise = this.perlin.noise2D(
          (x + this.config.seed) / (this.config.scale * 2),
          (y + this.config.seed) / (this.config.scale * 2)
        );

        const temperature = 1 - latitude * 0.7 + (noise - 0.5) * 0.4;
        map[y][x] = Math.max(0, Math.min(1, temperature));
      }
    }

    return map;
  }

  private applyFalloffMap(heightMap: number[][], falloff: number[][]): void {
    const height = heightMap.length;
    const width = heightMap[0]?.length ?? 0;

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        heightMap[y][x] = Math.max(0, heightMap[y][x] - falloff[y][x]);
      }
    }
  }

  private applyMoistureModifiers(
    terrain: TerrainType,
    height: number,
    moisture: number
  ): TerrainType {
    if (terrain === 'plain') {
      if (moisture > 0.8) return 'swamp';
      if (moisture > 0.65) return 'forest';
      if (moisture < 0.2) return 'sand';
    }

    if (terrain === 'sand' && moisture > 0.5) {
      return 'plain';
    }

    return terrain;
  }

  private applyTemperatureModifiers(
    terrain: TerrainType,
    height: number,
    temperature: number
  ): TerrainType {
    if (terrain === 'mountain' && height > 0.8 && temperature < 0.3) {
      return 'snow';
    }

    if (terrain === 'plain' && temperature < 0.2 && height > 0.4) {
      return 'snow';
    }

    if (terrain === 'sand' && temperature > 0.9) {
      return 'sand';
    }

    if (terrain === 'water' && temperature < 0.15) {
      return 'snow';
    }

    return terrain;
  }

  private shouldPlaceForest(currentTerrain: TerrainType, moisture: number): boolean {
    if (currentTerrain !== 'plain') return false;
    const chance = moisture * this.config.randomFeatures.forestDensity;
    return this.rand.next() < chance;
  }

  private shouldPlaceMountain(currentTerrain: TerrainType, height: number, moisture: number): boolean {
    if (currentTerrain !== 'plain' && currentTerrain !== 'forest') return false;
    if (height < 0.6) return false;
    const chance = (height - 0.6) * 2 * this.config.randomFeatures.mountainDensity;
    return this.rand.next() < chance;
  }

  private shouldPlaceWater(currentTerrain: TerrainType, moisture: number, height: number): boolean {
    if (currentTerrain !== 'plain') return false;
    if (height > 0.6) return false;
    const chance = moisture * this.config.randomFeatures.waterDensity;
    return this.rand.next() < chance;
  }

  private placeRoads(grid: HexGrid, result: RandomFeatureResult): void {
    const { width, height } = grid.getDimensions();
    const config = grid.getConfig();
    if (this.config.randomFeatures.roadProbability <= 0) return;

    if (this.rand.next() < this.config.randomFeatures.roadProbability) {
      const startRow = Math.floor(this.rand.next() * height);
      const endRow = Math.floor(this.rand.next() * height);

      let currentCol = 0;
      let currentRow = startRow;

      while (currentCol < width) {
        const offset: OffsetCoords = { col: currentCol, row: currentRow };
        const coords = offsetToCube(offset, config.orientation);

        if (grid.hasTile(coords)) {
          const tile = grid.getTile(coords);
          if (tile && tile.terrain !== 'water' && tile.terrain !== 'mountain' && tile.terrain !== 'wall') {
            const data = this.tileData.get(cubeKey(coords));
            grid.setTileTerrain(coords, 'road');
            if (data) data.terrain = 'road';
            result.placedTiles.push(coords);
            result.placedCount['road']++;
          }
        }

        currentCol++;

        if (currentRow < endRow) {
          currentRow += this.rand.next() < 0.6 ? 1 : 0;
        } else if (currentRow > endRow) {
          currentRow -= this.rand.next() < 0.6 ? 1 : 0;
        }

        currentRow = Math.max(0, Math.min(height - 1, currentRow));
      }
    }
  }

  private shuffleArray<T>(array: T[]): T[] {
    const result = [...array];
    for (let i = result.length - 1; i > 0; i--) {
      const j = Math.floor(this.rand.next() * (i + 1));
      [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
  }

  getHeightAt(coords: CubeCoords): number {
    return this.tileData.get(cubeKey(coords))?.height ?? 0;
  }

  getMoistureAt(coords: CubeCoords): number {
    return this.tileData.get(cubeKey(coords))?.moisture ?? 0;
  }

  getTemperatureAt(coords: CubeCoords): number {
    return this.tileData.get(cubeKey(coords))?.temperature ?? 0;
  }

  getTerrainAt(coords: CubeCoords): TerrainType | undefined {
    return this.tileData.get(cubeKey(coords))?.terrain;
  }

  getHeightMap(): number[][] {
    return this.heightMap.map(row => [...row]);
  }

  getMoistureMap(): number[][] {
    return this.moistureMap.map(row => [...row]);
  }

  getTemperatureMap(): number[][] {
    return this.temperatureMap.map(row => [...row]);
  }

  getConfig(): Readonly<MapGeneratorConfig> {
    return { ...this.config };
  }

  setConfig(config: Partial<MapGeneratorConfig>): void {
    this.config = { ...this.config, ...config };
    this.perlin = new PerlinNoise(this.config.seed);
    this.valueNoise = new ValueNoise(this.config.seed + 9999);
    this.rand = new SeededRandom(this.config.seed);
  }

  regenerateSeed(seed?: number): void {
    const newSeed = seed ?? Math.floor(Math.random() * 1000000);
    this.config.seed = newSeed;
    this.perlin = new PerlinNoise(newSeed);
    this.valueNoise = new ValueNoise(newSeed + 9999);
    this.rand = new SeededRandom(newSeed);
  }

  toJSON(): Record<string, unknown> {
    return {
      config: this.config,
      heightMap: this.heightMap,
      moistureMap: this.moistureMap,
      temperatureMap: this.temperatureMap,
      tileData: Array.from(this.tileData.entries()).map(([key, data]) => ({
        key,
        data,
      })),
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.config = data.config as MapGeneratorConfig;
    this.heightMap = data.heightMap as number[][];
    this.moistureMap = data.moistureMap as number[][];
    this.temperatureMap = data.temperatureMap as number[][];

    this.tileData = new Map();
    const tileDataArray = data.tileData as Array<{ key: string; data: GeneratedTileData }>;
    for (const item of tileDataArray) {
      this.tileData.set(item.key, item.data);
    }

    this.perlin = new PerlinNoise(this.config.seed);
    this.valueNoise = new ValueNoise(this.config.seed + 9999);
    this.rand = new SeededRandom(this.config.seed);
  }

  static fromJSON(data: Record<string, unknown>): MapGenerator {
    const generator = new MapGenerator();
    generator.fromJSON(data);
    return generator;
  }
}

class SeededRandom {
  private seed: number;

  constructor(seed: number) {
    this.seed = seed;
  }

  next(): number {
    this.seed = (this.seed * 16807 + 0) % 2147483647;
    return (this.seed - 1) / 2147483646;
  }

  nextInt(min: number, max: number): number {
    return Math.floor(this.next() * (max - min + 1)) + min;
  }

  nextFloat(min: number, max: number): number {
    return this.next() * (max - min) + min;
  }
}
