import type { CubeCoords, PathResult, PathNode, ID, GridQueryOptions } from '../types';
import { cubeDistance, cubeEquals, cubeKey, cubeNeighbors } from './coords';
import type { HexGrid } from './HexGrid';
import { terrainRegistry } from './TerrainConfig';
import { serializeMap, deserializeMap } from '../utils/serialization';

interface PathfindingOptions {
  ignoreTerrain?: string[];
  ignoreUnits?: boolean;
  unitId?: ID;
  preferTerrain?: string[];
  avoidTerrain?: string[];
  terrainPreferenceWeight?: number;
}

interface HeapNode {
  key: string;
  f: number;
  node: PathNode;
}

class MinHeap {
  private heap: HeapNode[] = [];

  get size(): number {
    return this.heap.length;
  }

  push(key: string, f: number, node: PathNode): void {
    this.heap.push({ key, f, node });
    this.bubbleUp(this.heap.length - 1);
  }

  pop(): HeapNode | undefined {
    if (this.heap.length === 0) return undefined;
    const top = this.heap[0];
    const last = this.heap.pop()!;
    if (this.heap.length > 0) {
      this.heap[0] = last;
      this.bubbleDown(0);
    }
    return top;
  }

  private bubbleUp(index: number): void {
    while (index > 0) {
      const parent = (index - 1) >> 1;
      if (this.heap[parent].f > this.heap[index].f) {
        [this.heap[parent], this.heap[index]] = [this.heap[index], this.heap[parent]];
        index = parent;
      } else {
        break;
      }
    }
  }

  private bubbleDown(index: number): void {
    const length = this.heap.length;
    while (true) {
      let smallest = index;
      const left = (index << 1) + 1;
      const right = (index << 1) + 2;

      if (left < length && this.heap[left].f < this.heap[smallest].f) {
        smallest = left;
      }
      if (right < length && this.heap[right].f < this.heap[smallest].f) {
        smallest = right;
      }
      if (smallest !== index) {
        [this.heap[index], this.heap[smallest]] = [this.heap[smallest], this.heap[index]];
        index = smallest;
      } else {
        break;
      }
    }
  }
}

export class Pathfinder {
  private grid: HexGrid;
  private cache: Map<string, PathResult>;
  private cacheMaxSize: number;

  constructor(grid: HexGrid, cacheMaxSize: number = 1000) {
    this.grid = grid;
    this.cache = new Map();
    this.cacheMaxSize = cacheMaxSize;
  }

  findPath(
    start: CubeCoords,
    goal: CubeCoords,
    maxMovePoints: number = Infinity,
    options: PathfindingOptions = {}
  ): PathResult {
    const cacheKey = this.getPathCacheKey(start, goal, maxMovePoints, options);
    const cached = this.cache.get(cacheKey);
    if (cached) {
      return cached;
    }

    if (!this.grid.hasTile(start) || !this.grid.hasTile(goal)) {
      const result: PathResult = { path: [], totalCost: 0, reachable: false, distance: 0 };
      this.addToCache(cacheKey, result);
      return result;
    }

    if (cubeEquals(start, goal)) {
      const result: PathResult = { path: [{ ...start }], totalCost: 0, reachable: true, distance: 0 };
      this.addToCache(cacheKey, result);
      return result;
    }

    const openHeap = new MinHeap();
    const openMap: Map<string, PathNode> = new Map();
    const closedSet: Set<string> = new Set();
    const startKey = cubeKey(start);
    const goalKey = cubeKey(goal);

    const startNode: PathNode = {
      coords: { ...start },
      cost: 0,
      moveCost: 0,
      distance: cubeDistance(start, goal),
      parent: null,
    };

    openHeap.push(startKey, startNode.cost + startNode.distance, startNode);
    openMap.set(startKey, startNode);

    while (openHeap.size > 0) {
      const current = openHeap.pop()!;
      const currentKey = current.key;
      const currentNode = current.node;

      if (currentKey === goalKey) {
        const result = this.reconstructPath(currentNode);
        this.addToCache(cacheKey, result);
        return result;
      }

      openMap.delete(currentKey);
      closedSet.add(currentKey);

      const neighbors = cubeNeighbors(currentNode.coords);

      for (const neighborCoords of neighbors) {
        const neighborKey = cubeKey(neighborCoords);

        if (closedSet.has(neighborKey)) continue;
        if (!this.grid.hasTile(neighborCoords)) continue;

        const tile = this.grid.getTile(neighborCoords);
        if (!tile) continue;

        if (options.ignoreTerrain?.includes(tile.terrain)) continue;

        const terrainConfig = terrainRegistry.get(tile.terrain);
        if (terrainConfig.blocksMovement && !options.ignoreTerrain?.includes(tile.terrain)) continue;

        if (!options.ignoreUnits && tile.units.length > 0) {
          if (options.unitId && !tile.units.includes(options.unitId)) {
            if (!cubeEquals(neighborCoords, goal)) {
              continue;
            }
          } else if (!options.unitId) {
            continue;
          }
        }

        const baseMoveCost = this.grid.getMoveCost(currentNode.coords, neighborCoords);
        if (!isFinite(baseMoveCost)) continue;

        let terrainBias = 0;
        const weight = options.terrainPreferenceWeight ?? 0.5;
        if (options.preferTerrain?.includes(tile.terrain)) {
          terrainBias = -weight;
        }
        if (options.avoidTerrain?.includes(tile.terrain)) {
          terrainBias = weight * 2;
        }

        const moveCost = baseMoveCost + terrainBias;
        const newMoveCost = currentNode.moveCost + moveCost;

        if (newMoveCost > maxMovePoints) continue;

        const heuristic = cubeDistance(neighborCoords, goal);
        const existingNode = openMap.get(neighborKey);

        if (!existingNode || newMoveCost < existingNode.moveCost) {
          const newNode: PathNode = {
            coords: { ...neighborCoords },
            cost: newMoveCost + heuristic,
            moveCost: newMoveCost,
            distance: heuristic,
            parent: currentNode,
          };
          openHeap.push(neighborKey, newNode.cost, newNode);
          openMap.set(neighborKey, newNode);
        }
      }
    }

    const result: PathResult = {
      path: [],
      totalCost: 0,
      reachable: false,
      distance: cubeDistance(start, goal),
    };
    this.addToCache(cacheKey, result);
    return result;
  }

  getReachableTiles(
    start: CubeCoords,
    maxMovePoints: number,
    options: PathfindingOptions = {}
  ): Map<string, PathNode> {
    const reachable: Map<string, PathNode> = new Map();
    const startKey = cubeKey(start);

    const startNode: PathNode = {
      coords: { ...start },
      cost: 0,
      moveCost: 0,
      distance: 0,
      parent: null,
    };

    reachable.set(startKey, startNode);

    const heap = new MinHeap();
    heap.push(startKey, 0, startNode);

    while (heap.size > 0) {
      const current = heap.pop()!;
      const currentNode = current.node;

      for (const neighbor of cubeNeighbors(currentNode.coords)) {
        const neighborKey = cubeKey(neighbor);

        if (!this.grid.hasTile(neighbor)) continue;

        const tile = this.grid.getTile(neighbor);
        if (!tile) continue;

        if (options.ignoreTerrain?.includes(tile.terrain)) continue;

        const terrainConfig = terrainRegistry.get(tile.terrain);
        if (terrainConfig.blocksMovement && !options.ignoreTerrain?.includes(tile.terrain)) continue;

        if (!options.ignoreUnits && tile.units.length > 0) {
          if (options.unitId && !tile.units.includes(options.unitId)) {
            continue;
          } else if (!options.unitId) {
            continue;
          }
        }

        const moveCost = this.grid.getMoveCost(currentNode.coords, neighbor);
        if (!isFinite(moveCost)) continue;

        const newMoveCost = currentNode.moveCost + moveCost;

        if (newMoveCost > maxMovePoints) continue;

        const existingNode = reachable.get(neighborKey);
        if (existingNode && existingNode.moveCost <= newMoveCost) continue;

        const newNode: PathNode = {
          coords: { ...neighbor },
          cost: newMoveCost,
          moveCost: newMoveCost,
          distance: cubeDistance(start, neighbor),
          parent: currentNode,
        };

        reachable.set(neighborKey, newNode);
        heap.push(neighborKey, newMoveCost, newNode);
      }
    }

    return reachable;
  }

  findNearest(
    start: CubeCoords,
    predicate: (tile: { coords: CubeCoords; terrain: string; height: number }) => boolean,
    maxDistance: number = Infinity,
    options: PathfindingOptions = {}
  ): PathResult | null {
    const startKey = cubeKey(start);
    const visited: Set<string> = new Set();
    const heap = new MinHeap();

    const startNode: PathNode = {
      coords: { ...start },
      cost: 0,
      moveCost: 0,
      distance: 0,
      parent: null,
    };

    heap.push(startKey, 0, startNode);

    while (heap.size > 0) {
      const current = heap.pop()!;
      const currentKey = current.key;
      const currentNode = current.node;

      if (visited.has(currentKey)) continue;
      visited.add(currentKey);

      const tile = this.grid.getTile(currentNode.coords);
      if (tile && predicate({ coords: currentNode.coords, terrain: tile.terrain, height: tile.height })) {
        return this.reconstructPath(currentNode);
      }

      if (currentNode.distance >= maxDistance) continue;

      for (const neighbor of cubeNeighbors(currentNode.coords)) {
        const neighborKey = cubeKey(neighbor);

        if (visited.has(neighborKey)) continue;
        if (!this.grid.hasTile(neighbor)) continue;

        const neighborTile = this.grid.getTile(neighbor);
        if (!neighborTile) continue;

        if (options.ignoreTerrain?.includes(neighborTile.terrain)) continue;

        const terrainConfig = terrainRegistry.get(neighborTile.terrain);
        if (terrainConfig.blocksMovement && !options.ignoreTerrain?.includes(neighborTile.terrain)) continue;

        if (!options.ignoreUnits && neighborTile.units.length > 0) {
          if (options.unitId && !neighborTile.units.includes(options.unitId)) {
            continue;
          } else if (!options.unitId) {
            continue;
          }
        }

        const moveCost = this.grid.getMoveCost(currentNode.coords, neighbor);
        if (!isFinite(moveCost)) continue;

        const newMoveCost = currentNode.moveCost + moveCost;
        const newDistance = cubeDistance(start, neighbor);

        const newNode: PathNode = {
          coords: { ...neighbor },
          cost: newMoveCost + newDistance,
          moveCost: newMoveCost,
          distance: newDistance,
          parent: currentNode,
        };

        heap.push(neighborKey, newNode.cost, newNode);
      }
    }

    return null;
  }

  queryReachableTiles(
    start: CubeCoords,
    maxMovePoints: number,
    queryOptions: GridQueryOptions,
    pathOptions: PathfindingOptions = {}
  ): PathNode[] {
    const reachable = this.getReachableTiles(start, maxMovePoints, pathOptions);
    const result: PathNode[] = [];

    for (const node of reachable.values()) {
      const tile = this.grid.getTile(node.coords);
      if (!tile) continue;

      if (queryOptions.includeTerrain && !queryOptions.includeTerrain.includes(tile.terrain)) continue;
      if (queryOptions.excludeTerrain && queryOptions.excludeTerrain.includes(tile.terrain)) continue;
      if (queryOptions.hasUnit !== undefined && tile.units.length > 0 !== queryOptions.hasUnit) continue;
      if (queryOptions.hasObject !== undefined && tile.objects.length > 0 !== queryOptions.hasObject) continue;
      if (queryOptions.isVisible !== undefined && tile.isVisible !== queryOptions.isVisible) continue;
      if (queryOptions.minHeight !== undefined && tile.height < queryOptions.minHeight) continue;
      if (queryOptions.maxHeight !== undefined && tile.height > queryOptions.maxHeight) continue;
      if (queryOptions.maxDistance !== undefined && node.distance > queryOptions.maxDistance) continue;

      result.push(node);
    }

    return result;
  }

  clearCache(): void {
    this.cache.clear();
  }

  private reconstructPath(node: PathNode): PathResult {
    const path: CubeCoords[] = [];
    let current: PathNode | null = node;
    let totalCost = 0;
    let startTileCost = 0;

    while (current) {
      path.unshift({ ...current.coords });
      totalCost = current.moveCost;
      if (!current.parent) {
        const startTile = this.grid.getTile(current.coords);
        if (startTile) {
          startTileCost = this.grid.getMoveCost(current.coords, current.coords);
          if (!isFinite(startTileCost) || startTileCost === Infinity) {
            startTileCost = 0;
          }
        }
      }
      current = current.parent;
    }

    return {
      path,
      totalCost: totalCost > 0 ? totalCost : (path.length > 1 ? path.length - 1 : 0),
      reachable: true,
      distance: path.length - 1,
    };
  }

  private getPathCacheKey(
    start: CubeCoords,
    goal: CubeCoords,
    maxMovePoints: number,
    options: PathfindingOptions
  ): string {
    const parts = [
      cubeKey(start),
      cubeKey(goal),
      String(maxMovePoints),
      options.ignoreTerrain?.join('|') ?? '',
      String(options.ignoreUnits ?? false),
      options.unitId ?? '',
      options.preferTerrain?.join('|') ?? '',
      options.avoidTerrain?.join('|') ?? '',
      String(options.terrainPreferenceWeight ?? 0.5),
    ];
    return parts.join(';');
  }

  private addToCache(key: string, result: PathResult): void {
    this.cache.set(key, result);
    if (this.cache.size > this.cacheMaxSize) {
      const firstKey = this.cache.keys().next().value;
      if (firstKey !== undefined) {
        this.cache.delete(firstKey);
      }
    }
  }

  toJSON(): Record<string, unknown> {
    return {
      cache: serializeMap(
        this.cache,
        (key: string) => key
      ),
      cacheMaxSize: this.cacheMaxSize,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.cache = deserializeMap(
      data.cache as Array<{ key: string; value: PathResult }>,
      (key: string) => key
    );
    this.cacheMaxSize = (data.cacheMaxSize as number) ?? 1000;
  }

  static fromJSON(grid: HexGrid, data: Record<string, unknown>): Pathfinder {
    const pathfinder = new Pathfinder(grid);
    pathfinder.fromJSON(data);
    return pathfinder;
  }
}
