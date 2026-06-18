import { describe, it, expect } from 'vitest';
import {
  cubeDistance,
  cubeKey,
  cubeEquals,
  cubeNeighbors,
  cubeRing,
  cubeSpiral,
  getTilesInRange,
  getTilesAtRange,
  cubeCoords,
  cubeLine,
} from '../src/grid/coords';
import { HexGrid } from '../src/grid/HexGrid';
import { Pathfinder } from '../src/grid/Pathfinding';
import { createEmptyGrid } from './factories';
import type { CubeCoords } from '../src/types/grid';

function randomCube(maxAbs: number = 10): CubeCoords {
  const q = Math.floor(Math.random() * (maxAbs * 2 + 1)) - maxAbs;
  const r = Math.floor(Math.random() * (maxAbs * 2 + 1)) - maxAbs;
  const s = -q - r;
  return { q, r, s };
}

describe('Test 1: 距离函数验证', () => {
  it('已知坐标对的精确距离', () => {
    const knownCases: Array<[CubeCoords, CubeCoords, number]> = [
      [{ q: 0, r: 0, s: 0 }, { q: 0, r: 0, s: 0 }, 0],
      [{ q: 0, r: 0, s: 0 }, { q: 1, r: 0, s: -1 }, 1],
      [{ q: 0, r: 0, s: 0 }, { q: -1, r: 1, s: 0 }, 1],
      [{ q: 0, r: 0, s: 0 }, { q: 2, r: -1, s: -1 }, 2],
      [{ q: 2, r: -1, s: -1 }, { q: -3, r: 2, s: 1 }, 5],
      [{ q: 3, r: -1, s: -2 }, { q: -2, r: 3, s: -1 }, 5],
      [{ q: 5, r: -2, s: -3 }, { q: 0, r: 0, s: 0 }, 5],
      [{ q: -4, r: 2, s: 2 }, { q: 1, r: -3, s: 2 }, 5],
      [{ q: 10, r: 0, s: -10 }, { q: 0, r: 0, s: 0 }, 10],
      [{ q: 1, r: -2, s: 1 }, { q: -2, r: 1, s: 1 }, 3],
      [{ q: 7, r: -3, s: -4 }, { q: -1, r: 2, s: -1 }, 8],
      [{ q: -5, r: -3, s: 8 }, { q: 2, r: 4, s: -6 }, 14],
    ];

    for (const [a, b, expected] of knownCases) {
      expect(cubeDistance(a, b)).toBe(expected);
    }
  });

  it('距离对称性 dist(A,B) == dist(B,A)', () => {
    for (let i = 0; i < 10; i++) {
      const a = randomCube(15);
      const b = randomCube(15);
      const d1 = cubeDistance(a, b);
      const d2 = cubeDistance(b, a);
      expect(d1).toBe(d2);
    }
  });

  it('三角不等式 dist(A,C) <= dist(A,B) + dist(B,C)', () => {
    for (let i = 0; i < 10; i++) {
      const a = randomCube(10);
      const b = randomCube(10);
      const c = randomCube(10);
      const dAC = cubeDistance(a, c);
      const dAB = cubeDistance(a, b);
      const dBC = cubeDistance(b, c);
      expect(dAC).toBeLessThanOrEqual(dAB + dBC);
    }
  });

  it('邻居距离恰为1且无重复', () => {
    const center = { q: 0, r: 0, s: 0 } as CubeCoords;
    const neighbors = cubeNeighbors(center);
    expect(neighbors.length).toBe(6);
    for (const n of neighbors) {
      expect(cubeDistance(center, n)).toBe(1);
    }
    const uniqueKeys = new Set(neighbors.map(cubeKey));
    expect(uniqueKeys.size).toBe(6);
  });
});

describe('Test 2: 范围格子数量公式 3*R*(R+1)+1', () => {
  const center = { q: 0, r: 0, s: 0 } as CubeCoords;

  it('radius=0,1,2,3 的格子总数验证', () => {
    const cases: Array<[number, number, number]> = [
      [0, 1, 1],
      [1, 7, 6],
      [2, 19, 12],
      [3, 37, 18],
    ];
    for (const [r, expectedTotal, expectedRing] of cases) {
      const total = getTilesInRange(center, r);
      const ring = getTilesAtRange(center, r);
      expect(total.length).toBe(expectedTotal);
      expect(ring.length).toBe(expectedRing);
    }
  });

  it('radius=5 和 radius=10 的大半径验证', () => {
    const r5 = getTilesInRange(center, 5);
    expect(r5.length).toBe(3 * 5 * 6 + 1);
    expect(getTilesAtRange(center, 5).length).toBe(6 * 5);

    const r10 = getTilesInRange(center, 10);
    expect(r10.length).toBe(3 * 10 * 11 + 1);
    expect(getTilesAtRange(center, 10).length).toBe(6 * 10);
  });

  it('每个格子距离中心 <= R 且无重复', () => {
    const radii = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    for (const r of radii) {
      const tiles = getTilesInRange(center, r);
      for (const t of tiles) {
        expect(cubeDistance(center, t)).toBeLessThanOrEqual(r);
      }
      const unique = new Set(tiles.map(cubeKey));
      expect(unique.size).toBe(tiles.length);
    }
  });

  it('getTilesAtRange 最外圈距离恰为 R', () => {
    for (let r = 0; r <= 10; r++) {
      const ring = getTilesAtRange(center, r);
      for (const t of ring) {
        expect(cubeDistance(center, t)).toBe(r);
      }
      const unique = new Set(ring.map(cubeKey));
      expect(unique.size).toBe(ring.length);
    }
  });
});

describe('Test 3: cubeRing 与 cubeSpiral 不重复', () => {
  const center = { q: 0, r: 0, s: 0 } as CubeCoords;

  it('cubeRing radius=5 返回 30 个互不重复坐标', () => {
    const ring = cubeRing(center, 5);
    expect(ring.length).toBe(30);
    const unique = new Set(ring.map(cubeKey));
    expect(unique.size).toBe(30);
    for (const c of ring) {
      expect(cubeDistance(center, c)).toBe(5);
    }
  });

  it('cubeSpiral(center, 5) 返回 91 个互不重复坐标', () => {
    const spiral = cubeSpiral(center, 5);
    expect(spiral.length).toBe(91);
    const unique = new Set(spiral.map(cubeKey));
    expect(unique.size).toBe(91);
  });

  it('cubeSpiral 等价于 union of cubeRing(center, 0..5)', () => {
    const spiral = cubeSpiral(center, 5);
    const spiralSet = new Set(spiral.map(cubeKey));

    const ringUnion: string[] = [];
    for (let r = 0; r <= 5; r++) {
      const ring = cubeRing(center, r);
      ringUnion.push(...ring.map(cubeKey));
    }
    const ringUnionSet = new Set(ringUnion);

    expect(spiralSet.size).toBe(ringUnionSet.size);
    for (const key of spiralSet) {
      expect(ringUnionSet.has(key)).toBe(true);
    }
    for (const key of ringUnionSet) {
      expect(spiralSet.has(key)).toBe(true);
    }
  });

  it('各 radius 的 cubeRing 互不相交', () => {
    const seen = new Set<string>();
    for (let r = 0; r <= 5; r++) {
      const ring = cubeRing(center, r);
      for (const c of ring) {
        const key = cubeKey(c);
        expect(seen.has(key)).toBe(false);
        seen.add(key);
      }
    }
  });
});

describe('Test 4: A* 无障碍地图上的路径正确性与代价最小性', () => {
  it('center 到 opposite(10,0,-10) 路径长度恰好等于距离', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);

    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(10, 0, -10);
    const result = pathfinder.findPath(start, goal);

    expect(result.reachable).toBe(true);
    expect(result.path.length - 1).toBe(cubeDistance(start, goal));
    expect(result.totalCost).toBe(result.path.length - 1);
  });

  it('center 到 (5,3,-8) 路径恰好 dist=8', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);

    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(5, 3, -8);
    const expectedDist = cubeDistance(start, goal);
    expect(expectedDist).toBe(8);

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);
    expect(result.path.length - 1).toBe(expectedDist);
    expect(result.totalCost).toBe(result.path.length - 1);
  });

  it('10组随机 start/goal 对路径完整性验证', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);

    for (let i = 0; i < 10; i++) {
      const allTiles = grid.getAllTiles();
      const startTile = allTiles[Math.floor(Math.random() * allTiles.length)];
      const goalTile = allTiles[Math.floor(Math.random() * allTiles.length)];
      const start = startTile.coords;
      const goal = goalTile.coords;

      const result = pathfinder.findPath(start, goal);
      const dist = cubeDistance(start, goal);

      expect(result.reachable).toBe(true);
      expect(result.path.length - 1).toBe(dist);
      expect(result.totalCost).toBe(result.path.length - 1);

      for (let j = 0; j < result.path.length - 1; j++) {
        expect(cubeDistance(result.path[j], result.path[j + 1])).toBe(1);
      }

      expect(cubeEquals(result.path[0], start)).toBe(true);
      expect(cubeEquals(result.path[result.path.length - 1], goal)).toBe(true);
    }
  });

  it('同点寻路返回单元素路径且cost=0', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);
    const start = cubeCoords(0, 0, 0);
    const result = pathfinder.findPath(start, start);
    expect(result.reachable).toBe(true);
    expect(result.path.length).toBe(1);
    expect(result.totalCost).toBe(0);
    expect(result.distance).toBe(0);
  });
});

describe('Test 5: A* 有障碍地图代价最优', () => {
  it('直线路径有墙时必须绕行', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);

    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(10, 0, -10);

    const straightLine = cubeLine(start, goal);
    const wallPositions = straightLine.slice(1, 6);
    for (const pos of wallPositions) {
      if (grid.hasTile(pos)) {
        grid.setTileTerrain(pos, 'wall');
      }
    }

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);
    expect(result.totalCost).toBeGreaterThan(10);
  });

  it('A* 代价与 Dijkstra (getReachableTiles) 一致', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);

    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(10, 0, -10);

    const straightLine = cubeLine(start, goal);
    for (let i = 1; i <= 5; i++) {
      const pos = straightLine[i];
      if (grid.hasTile(pos)) {
        grid.setTileTerrain(pos, 'wall');
      }
    }

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);

    const reachable = pathfinder.getReachableTiles(start, Infinity);
    const goalKey = cubeKey(goal);
    expect(reachable.has(goalKey)).toBe(true);

    const dijkstraCost = reachable.get(goalKey)!.moveCost;
    expect(result.totalCost).toBeCloseTo(dijkstraCost, 5);
  });

  it('路径不经过 wall 地形', () => {
    const grid = createEmptyGrid(20, 20, 'plain');
    const pathfinder = new Pathfinder(grid);

    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(10, 0, -10);

    const straightLine = cubeLine(start, goal);
    for (let i = 1; i <= 5; i++) {
      const pos = straightLine[i];
      if (grid.hasTile(pos)) {
        grid.setTileTerrain(pos, 'wall');
      }
    }

    const result = pathfinder.findPath(start, goal);
    for (const c of result.path) {
      const tile = grid.getTile(c);
      expect(tile).toBeDefined();
      expect(tile!.terrain).not.toBe('wall');
    }
  });
});

describe('Test 6: A* 不同地形成本加权', () => {
  it('A* 选择 cost 更低的绕路 (forest直线路径 vs road绕行)', () => {
    const grid = createEmptyGrid(20, 5, 'plain');
    const pathfinder = new Pathfinder(grid);

    for (let col = 0; col < 20; col++) {
      const midCoord = grid.getTileByOffset({ col, row: 2 });
      if (midCoord) {
        grid.setTileTerrain(midCoord.coords, 'forest');
      }
      const upCoord = grid.getTileByOffset({ col, row: 1 });
      if (upCoord) {
        grid.setTileTerrain(upCoord.coords, 'road');
      }
      const downCoord = grid.getTileByOffset({ col, row: 3 });
      if (downCoord) {
        grid.setTileTerrain(downCoord.coords, 'road');
      }
    }

    const startTile = grid.getTileByOffset({ col: 0, row: 2 });
    const goalTile = grid.getTileByOffset({ col: 19, row: 2 });
    expect(startTile).toBeDefined();
    expect(goalTile).toBeDefined();

    const start = startTile!.coords;
    const goal = goalTile!.coords;

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);

    let forestCount = 0;
    let roadCount = 0;
    for (const c of result.path) {
      const tile = grid.getTile(c);
      if (tile?.terrain === 'forest') forestCount++;
      if (tile?.terrain === 'road') roadCount++;
    }

    expect(roadCount).toBeGreaterThan(forestCount);
    expect(result.totalCost).toBeLessThan(20);
  });

  it('直穿 forest cost=2*10=20，绕行 road 应该更低', () => {
    const grid = createEmptyGrid(20, 5, 'plain');
    const pathfinder = new Pathfinder(grid);

    for (let col = 0; col < 20; col++) {
      const midCoord = grid.getTileByOffset({ col, row: 2 });
      if (midCoord) grid.setTileTerrain(midCoord.coords, 'forest');
      const upCoord = grid.getTileByOffset({ col, row: 1 });
      if (upCoord) grid.setTileTerrain(upCoord.coords, 'road');
      const downCoord = grid.getTileByOffset({ col, row: 3 });
      if (downCoord) grid.setTileTerrain(downCoord.coords, 'road');
    }

    const startTile = grid.getTileByOffset({ col: 0, row: 2 });
    const goalTile = grid.getTileByOffset({ col: 19, row: 2 });
    const result = pathfinder.findPath(startTile!.coords, goalTile!.coords);

    const straightForestCost = 20;
    expect(result.totalCost).toBeLessThan(straightForestCost);
  });

  it('路径每步移动成本累加等于 totalCost', () => {
    const grid = createEmptyGrid(20, 5, 'plain');
    const pathfinder = new Pathfinder(grid);

    for (let col = 0; col < 20; col++) {
      const midCoord = grid.getTileByOffset({ col, row: 2 });
      if (midCoord) grid.setTileTerrain(midCoord.coords, 'forest');
      const upCoord = grid.getTileByOffset({ col, row: 1 });
      if (upCoord) grid.setTileTerrain(upCoord.coords, 'road');
    }

    const startTile = grid.getTileByOffset({ col: 0, row: 2 });
    const goalTile = grid.getTileByOffset({ col: 19, row: 2 });
    const result = pathfinder.findPath(startTile!.coords, goalTile!.coords);

    let sumCost = 0;
    for (let i = 1; i < result.path.length; i++) {
      const stepCost = grid.getMoveCost(result.path[i - 1], result.path[i]);
      sumCost += stepCost;
    }
    expect(sumCost).toBeCloseTo(result.totalCost, 5);
  });
});

describe('Test 7: A* 不可达目标正确处理', () => {
  it('中心被墙完全包围，从中心到外圈不可达', () => {
    const grid = createEmptyGrid(10, 10, 'plain');
    const pathfinder = new Pathfinder(grid);

    const centerTile = grid.getTileByOffset({ col: 5, row: 5 });
    const center = centerTile!.coords;

    const innerRing = cubeRing(center, 1);
    for (const pos of innerRing) {
      if (grid.hasTile(pos)) {
        grid.setTileTerrain(pos, 'wall');
      }
    }

    const outerTile = grid.getTileByOffset({ col: 0, row: 0 });
    const result = pathfinder.findPath(center, outerTile!.coords);

    expect(result.reachable).toBe(false);
    expect(result.path.length).toBe(0);
    expect(result.totalCost).toBe(0);
  });

  it('100次不可达对无死循环、立即返回', () => {
    const grid = createEmptyGrid(10, 10, 'plain');
    const pathfinder = new Pathfinder(grid);

    const centerTile = grid.getTileByOffset({ col: 5, row: 5 });
    const center = centerTile!.coords;

    const innerRing = cubeRing(center, 1);
    for (const pos of innerRing) {
      if (grid.hasTile(pos)) {
        grid.setTileTerrain(pos, 'wall');
      }
    }

    const allTiles = grid.getAllTiles();
    const innerTiles = cubeSpiral(center, 0).map(c => grid.getTile(c)!).filter(Boolean);
    const outerTiles = allTiles.filter(t => !innerTiles.some(it => cubeEquals(it.coords, t.coords)));

    for (let i = 0; i < 100; i++) {
      const start = innerTiles[Math.floor(Math.random() * innerTiles.length)].coords;
      const goal = outerTiles[Math.floor(Math.random() * outerTiles.length)].coords;

      const t0 = performance.now();
      const result = pathfinder.findPath(start, goal);
      const t1 = performance.now();

      expect(result.reachable).toBe(false);
      expect(result.path.length).toBe(0);
      expect(result.totalCost).toBe(0);
      expect(t1 - t0).toBeLessThan(10);
    }
  });

  it('不存在的瓦片立即返回不可达', () => {
    const grid = createEmptyGrid(10, 10, 'plain');
    const pathfinder = new Pathfinder(grid);

    const validStart = cubeCoords(0, 0, 0);
    const invalidGoal = { q: 999, r: 999, s: -1998 } as CubeCoords;

    const t0 = performance.now();
    const result1 = pathfinder.findPath(validStart, invalidGoal);
    const t1 = performance.now();

    expect(result1.reachable).toBe(false);
    expect(result1.path.length).toBe(0);
    expect(t1 - t0).toBeLessThan(10);

    const result2 = pathfinder.findPath(invalidGoal, validStart);
    expect(result2.reachable).toBe(false);
    expect(result2.path.length).toBe(0);
  });

  it('完全被墙分隔的两区域互不可达', () => {
    const grid = createEmptyGrid(10, 10, 'plain');
    const pathfinder = new Pathfinder(grid);

    for (let col = 0; col < 10; col++) {
      const wallTile = grid.getTileByOffset({ col, row: 5 });
      if (wallTile && col !== 100) {
        grid.setTileTerrain(wallTile.coords, 'wall');
      }
    }

    const topTile = grid.getTileByOffset({ col: 0, row: 0 });
    const bottomTile = grid.getTileByOffset({ col: 0, row: 9 });

    const result = pathfinder.findPath(topTile!.coords, bottomTile!.coords);
    expect(result.reachable).toBe(false);
    expect(result.path.length).toBe(0);
  });
});
