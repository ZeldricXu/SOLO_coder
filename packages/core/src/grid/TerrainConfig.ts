import type { TerrainType, TileConfig } from '../types';

export const DEFAULT_TERRAIN_CONFIGS: Record<TerrainType, TileConfig> = {
  plain: {
    type: 'plain',
    name: '平原',
    moveCost: 1,
    defenseBonus: 0,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: false,
    blocksVision: false,
    color: '#8BC34A',
  },
  forest: {
    type: 'forest',
    name: '森林',
    moveCost: 2,
    defenseBonus: 15,
    attackBonus: 0,
    accuracyBonus: -10,
    blocksMovement: false,
    blocksVision: true,
    color: '#2E7D32',
  },
  mountain: {
    type: 'mountain',
    name: '山地',
    moveCost: 3,
    defenseBonus: 30,
    attackBonus: 10,
    accuracyBonus: 0,
    blocksMovement: false,
    blocksVision: true,
    color: '#795548',
  },
  water: {
    type: 'water',
    name: '水域',
    moveCost: 999,
    defenseBonus: 0,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: true,
    blocksVision: false,
    color: '#2196F3',
  },
  road: {
    type: 'road',
    name: '道路',
    moveCost: 0.5,
    defenseBonus: -5,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: false,
    blocksVision: false,
    color: '#9E9E9E',
  },
  wall: {
    type: 'wall',
    name: '城墙',
    moveCost: 999,
    defenseBonus: 50,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: true,
    blocksVision: true,
    color: '#607D8B',
  },
  lava: {
    type: 'lava',
    name: '熔岩',
    moveCost: 999,
    defenseBonus: 0,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: true,
    blocksVision: false,
    color: '#FF5722',
  },
  sand: {
    type: 'sand',
    name: '沙地',
    moveCost: 1.5,
    defenseBonus: -5,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: false,
    blocksVision: false,
    color: '#FFEB3B',
  },
  snow: {
    type: 'snow',
    name: '雪地',
    moveCost: 2,
    defenseBonus: 5,
    attackBonus: 0,
    accuracyBonus: -5,
    blocksMovement: false,
    blocksVision: false,
    color: '#ECEFF1',
  },
  swamp: {
    type: 'swamp',
    name: '沼泽',
    moveCost: 2.5,
    defenseBonus: -10,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: false,
    blocksVision: false,
    color: '#33691E',
  },
  building: {
    type: 'building',
    name: '建筑',
    moveCost: 999,
    defenseBonus: 40,
    attackBonus: 5,
    accuracyBonus: 0,
    blocksMovement: true,
    blocksVision: true,
    color: '#5D4037',
  },
  ruins: {
    type: 'ruins',
    name: '废墟',
    moveCost: 2,
    defenseBonus: 20,
    attackBonus: 0,
    accuracyBonus: 0,
    blocksMovement: false,
    blocksVision: false,
    color: '#424242',
  },
};

export class TerrainRegistry {
  private configs: Map<TerrainType, TileConfig>;

  constructor() {
    this.configs = new Map();
    for (const [type, config] of Object.entries(DEFAULT_TERRAIN_CONFIGS)) {
      this.configs.set(type, config);
    }
  }

  register(config: TileConfig): void {
    this.configs.set(config.type, config);
  }

  get(type: TerrainType): TileConfig {
    const config = this.configs.get(type);
    if (!config) {
      return this.configs.get('plain')!;
    }
    return config;
  }

  has(type: TerrainType): boolean {
    return this.configs.has(type);
  }

  getAll(): TileConfig[] {
    return Array.from(this.configs.values());
  }

  getMoveCost(type: TerrainType): number {
    return this.get(type).moveCost;
  }

  getDefenseBonus(type: TerrainType): number {
    return this.get(type).defenseBonus;
  }

  getAttackBonus(type: TerrainType): number {
    return this.get(type).attackBonus;
  }

  blocksMovement(type: TerrainType): boolean {
    return this.get(type).blocksMovement;
  }

  blocksVision(type: TerrainType): boolean {
    return this.get(type).blocksVision;
  }
}

export const terrainRegistry = new TerrainRegistry();
