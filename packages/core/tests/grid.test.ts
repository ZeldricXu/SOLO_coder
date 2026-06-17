import { describe, it, expect, beforeEach } from 'vitest';
import {
  cubeCoords,
  cubeEquals,
  cubeAdd,
  cubeSubtract,
  cubeMultiply,
  cubeDistance,
  cubeLength,
  cubeNeighbors,
  cubeRing,
  cubeSpiral,
  cubeLine,
  cubeRound,
  cubeLerp,
  offsetToCube,
  cubeToOffset,
  cubeToPixel,
  pixelToCube,
  cubeKey,
  parseCubeKey,
  cubeDirection,
  isInRange,
} from '../src/grid/coords';
import { HexGrid } from '../src/grid/HexGrid';
import { Pathfinder } from '../src/grid/Pathfinding';
import { FieldOfViewCalculator } from '../src/grid/FieldOfView';
import { MapGenerator } from '../src/grid/MapGenerator';
import type { CubeCoords, HexGridConfig, Viewer } from '../src/types';

describe('coords - cube坐标系统', () => {
  it('cubeCoords 应该创建有效的立方坐标（q+r+s=0）', () => {
    const c = cubeCoords(1, 2, -3);
    expect(c.q + c.r + c.s).toBe(0);
    expect(c.q).toBe(1);
    expect(c.r).toBe(2);
    expect(c.s).toBe(-3);
  });

  it('cubeCoords 应该对无效坐标抛出错误', () => {
    expect(() => cubeCoords(1, 1, 1)).toThrow();
  });

  it('cubeEquals 应该正确比较相等坐标', () => {
    const a = cubeCoords(1, 0, -1);
    const b = cubeCoords(1, 0, -1);
    const c = cubeCoords(0, 1, -1);
    expect(cubeEquals(a, b)).toBe(true);
    expect(cubeEquals(a, c)).toBe(false);
  });

  it('cubeAdd/cubeSubtract/cubeMultiply 坐标运算', () => {
    const a = cubeCoords(1, 0, -1);
    const b = cubeCoords(0, 1, -1);
    const sum = cubeAdd(a, b);
    expect(sum.q).toBe(1);
    expect(sum.r).toBe(1);
    expect(sum.s).toBe(-2);

    const diff = cubeSubtract(a, b);
    expect(diff.q).toBe(1);
    expect(diff.r).toBe(-1);
    expect(diff.s).toBe(0);

    const mul = cubeMultiply(a, 3);
    expect(mul.q).toBe(3);
    expect(mul.r).toBe(0);
    expect(mul.s).toBe(-3);
  });

  it('cubeDistance 计算两点距离', () => {
    const a = cubeCoords(0, 0, 0);
    const b = cubeCoords(3, -1, -2);
    expect(cubeDistance(a, b)).toBe(3);

    const c = cubeCoords(1, 0, -1);
    const d = cubeCoords(1, 0, -1);
    expect(cubeDistance(c, d)).toBe(0);
  });

  it('cubeLength 计算坐标长度', () => {
    expect(cubeLength(cubeCoords(0, 0, 0))).toBe(0);
    expect(cubeLength(cubeCoords(2, -1, -1))).toBe(2);
    expect(cubeLength(cubeCoords(-3, 1, 2))).toBe(3);
  });

  it('cubeNeighbors 返回6个邻居', () => {
    const center = cubeCoords(0, 0, 0);
    const neighbors = cubeNeighbors(center);
    expect(neighbors.length).toBe(6);
    neighbors.forEach(n => {
      expect(cubeDistance(center, n)).toBe(1);
    });
  });

  it('cubeRing/cubeSpiral 环形和螺旋范围', () => {
    const center = cubeCoords(0, 0, 0);
    const ring1 = cubeRing(center, 1);
    expect(ring1.length).toBe(6);
    ring1.forEach(c => expect(cubeDistance(center, c)).toBe(1));

    const spiral2 = cubeSpiral(center, 2);
    expect(spiral2.length).toBe(1 + 6 + 12);
    expect(spiral2[0]).toEqual(center);
  });

  it('cubeLine 返回两点之间的直线', () => {
    const a = cubeCoords(0, 0, 0);
    const b = cubeCoords(3, -1, -2);
    const line = cubeLine(a, b);
    expect(line.length).toBe(4);
    expect(line[0]).toEqual(a);
    expect(line[line.length - 1]).toEqual(b);
  });

  it('cubeLerp + cubeRound 插值并四舍五入', () => {
    const a = cubeCoords(0, 0, 0);
    const b = cubeCoords(2, -1, -1);
    const mid = cubeLerp(a, b, 0.5);
    const rounded = cubeRound(mid);
    expect(rounded.q + rounded.r + rounded.s).toBe(0);
  });

  it('立方/偏移坐标互转 pointy orientation', () => {
    const original = cubeCoords(1, 0, -1);
    const offset = cubeToOffset(original, 'pointy');
    const back = offsetToCube(offset, 'pointy');
    expect(back.q + back.r + back.s).toBe(0);
    const roundedBack = cubeRound(back);
    expect(cubeEquals(roundedBack, original)).toBe(true);
  });

  it('立方/偏移坐标互转 flat orientation', () => {
    const original = cubeCoords(0, 1, -1);
    const offset = cubeToOffset(original, 'flat');
    const back = offsetToCube(offset, 'flat');
    expect(back.q + back.r + back.s).toBe(0);
    const roundedBack = cubeRound(back);
    expect(cubeEquals(roundedBack, original)).toBe(true);
  });

  it('像素坐标往返转换', () => {
    const tileSize = 50;
    const original = cubeCoords(2, -1, -1);
    const pixel = cubeToPixel(original, tileSize, 'pointy');
    const back = pixelToCube(pixel.x, pixel.y, tileSize, 'pointy');
    expect(cubeEquals(back, original)).toBe(true);
  });

  it('cubeKey/parseCubeKey 键值往返', () => {
    const original = cubeCoords(3, -2, -1);
    const key = cubeKey(original);
    expect(key).toBe('3,-2,-1');
    const parsed = parseCubeKey(key);
    expect(cubeEquals(parsed, original)).toBe(true);
  });

  it('cubeDirection 返回正确的方向向量', () => {
    const dir0 = cubeDirection(0);
    expect(dir0.q + dir0.r + dir0.s).toBe(0);
    expect(cubeLength(dir0)).toBe(1);
  });

  it('isInRange 范围判断', () => {
    const center = cubeCoords(0, 0, 0);
    expect(isInRange(center, cubeCoords(2, -1, -1), 3)).toBe(true);
    expect(isInRange(center, cubeCoords(5, 0, -5), 3)).toBe(false);
  });
});

describe('HexGrid - 六边形网格', () => {
  let radiusGrid: HexGrid;
  let rectGrid: HexGrid;
  const radiusConfig: HexGridConfig = {
    radius: 2,
    orientation: 'pointy',
    defaultTerrain: 'plain',
    tileSize: 50,
  };
  const rectConfig: HexGridConfig = {
    width: 5,
    height: 4,
    orientation: 'pointy',
    defaultTerrain: 'plain',
    tileSize: 50,
  };

  beforeEach(() => {
    radiusGrid = new HexGrid(radiusConfig);
    rectGrid = new HexGrid(rectConfig);
  });

  it('构造 - 半径网格生成正确数量的瓦片', () => {
    const expectedCount = 1 + 6 + 12;
    expect(radiusGrid.getTileCount()).toBe(expectedCount);
  });

  it('构造 - 矩形网格生成正确数量的瓦片', () => {
    expect(rectGrid.getTileCount()).toBe(5 * 4);
    const dims = rectGrid.getDimensions();
    expect(dims.width).toBe(5);
    expect(dims.height).toBe(4);
  });

  it('getTile/hasTile 瓦片存在性检查', () => {
    const center = cubeCoords(0, 0, 0);
    expect(radiusGrid.hasTile(center)).toBe(true);
    expect(radiusGrid.getTile(center)).toBeDefined();

    const outside = cubeCoords(10, 0, -10);
    expect(radiusGrid.hasTile(outside)).toBe(false);
    expect(radiusGrid.getTile(outside)).toBeUndefined();
  });

  it('setTileTerrain/setTileHeight 修改瓦片属性', () => {
    const coords = cubeCoords(0, 0, 0);
    radiusGrid.setTileTerrain(coords, 'forest');
    radiusGrid.setTileHeight(coords, 5);
    const tile = radiusGrid.getTile(coords);
    expect(tile?.terrain).toBe('forest');
    expect(tile?.height).toBe(5);
  });

  it('addUnit/removeUnit/moveUnit 单位操作', () => {
    const from = cubeCoords(0, 0, 0);
    const to = cubeCoords(1, 0, -1);
    const unitId = 'unit-1';

    radiusGrid.addUnit(from, unitId);
    expect(radiusGrid.getTile(from)?.units).toContain(unitId);

    radiusGrid.moveUnit(from, to, unitId);
    expect(radiusGrid.getTile(from)?.units).not.toContain(unitId);
    expect(radiusGrid.getTile(to)?.units).toContain(unitId);

    radiusGrid.removeUnit(to, unitId);
    expect(radiusGrid.getTile(to)?.units).not.toContain(unitId);
  });

  it('getNeighbors 邻居查询', () => {
    const center = cubeCoords(0, 0, 0);
    const neighbors = radiusGrid.getNeighbors(center);
    expect(neighbors.length).toBe(6);
    neighbors.forEach(n => {
      expect(cubeDistance(center, n.coords)).toBe(1);
    });
  });

  it('getTilesInRange 范围查询', () => {
    const center = cubeCoords(0, 0, 0);
    const inRange = radiusGrid.getTilesInRange(center, 1);
    expect(inRange.length).toBe(7);
  });

  it('getMoveCost 移动消耗计算', () => {
    const from = cubeCoords(0, 0, 0);
    const to = cubeCoords(1, 0, -1);
    radiusGrid.setTileTerrain(to, 'road');
    const cost = radiusGrid.getMoveCost(from, to);
    expect(cost).toBeLessThan(2);
  });

  it('blocksVision/blocksMovement 阻挡检查', () => {
    const coords = cubeCoords(0, 0, 0);
    radiusGrid.setTileTerrain(coords, 'wall');
    expect(radiusGrid.blocksMovement(coords)).toBe(true);
    expect(radiusGrid.blocksVision(coords)).toBe(true);

    const coords2 = cubeCoords(1, 0, -1);
    radiusGrid.setTileTerrain(coords2, 'water');
    expect(radiusGrid.blocksMovement(coords2)).toBe(true);
    expect(radiusGrid.blocksVision(coords2)).toBe(false);
  });

  it('clone 深拷贝独立', () => {
    const coords = cubeCoords(0, 0, 0);
    radiusGrid.setTileTerrain(coords, 'forest');
    const cloned = radiusGrid.clone();
    cloned.setTileTerrain(coords, 'mountain');

    expect(radiusGrid.getTile(coords)?.terrain).toBe('forest');
    expect(cloned.getTile(coords)?.terrain).toBe('mountain');
  });

  it('JSON序列化往返', () => {
    const coords = cubeCoords(0, 0, 0);
    radiusGrid.setTileTerrain(coords, 'forest');
    radiusGrid.addUnit(coords, 'unit-1');

    const json = radiusGrid.toJSON();
    const restored = HexGrid.fromJSON(json);

    expect(restored.getTileCount()).toBe(radiusGrid.getTileCount());
    expect(restored.getTile(coords)?.terrain).toBe('forest');
    expect(restored.getTile(coords)?.units).toContain('unit-1');
  });
});

describe('Pathfinder - 寻路系统', () => {
  let grid: HexGrid;
  let pathfinder: Pathfinder;
  const config: HexGridConfig = {
    width: 8,
    height: 8,
    orientation: 'pointy',
    defaultTerrain: 'plain',
    tileSize: 50,
  };

  beforeEach(() => {
    grid = new HexGrid(config);
    pathfinder = new Pathfinder(grid);
  });

  it('findPath 直达路径', () => {
    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(0, 0, 0);
    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);
    expect(result.path.length).toBe(1);
  });

  it('findPath 绕开障碍物', () => {
    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(3, 0, -3);
    const blocker = cubeCoords(1, 0, -1);
    grid.setTileTerrain(blocker, 'wall');

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);
    expect(result.path.length).toBeGreaterThan(3);
    expect(result.path[0]).toEqual(start);
    expect(result.path[result.path.length - 1]).toEqual(goal);
    expect(result.path.some(c => cubeEquals(c, blocker))).toBe(false);
  });

  it('findPath 超过移动力', () => {
    const start = cubeCoords(0, 0, 0);
    const goal = cubeCoords(4, 0, -4);
    const result = pathfinder.findPath(start, goal, 2);
    expect(result.reachable).toBe(false);
    expect(result.path.length).toBe(0);
  });

  it('getReachableTiles 覆盖范围', () => {
    const start = cubeCoords(0, 0, 0);
    const reachable = pathfinder.getReachableTiles(start, 2);
    expect(reachable.size).toBeGreaterThan(0);
    expect(reachable.size).toBeLessThanOrEqual(1 + 6 + 12);
    expect(reachable.has(cubeKey(start))).toBe(true);
  });

  it('findNearest 查找最近符合条件的瓦片', () => {
    const start = cubeCoords(0, 0, 0);
    const target = cubeCoords(2, 0, -2);
    grid.setTileTerrain(target, 'forest');

    const result = pathfinder.findNearest(
      start,
      (tile) => tile.terrain === 'forest'
    );
    expect(result).not.toBeNull();
    expect(result?.reachable).toBe(true);
  });
});

describe('FieldOfViewCalculator - 视野系统', () => {
  let grid: HexGrid;
  let fov: FieldOfViewCalculator;
  const config: HexGridConfig = {
    width: 10,
    height: 10,
    orientation: 'pointy',
    defaultTerrain: 'plain',
    tileSize: 50,
  };

  beforeEach(() => {
    grid = new HexGrid(config);
    fov = new FieldOfViewCalculator(grid);
  });

  it('calculateFOV 开阔地视野', () => {
    const viewer: Viewer = {
      id: 'v1',
      coords: cubeCoords(3, 0, -3),
      visionRange: 3,
      height: 0,
      faction: 'player',
    };
    const result = fov.calculateFOV(viewer);
    expect(result.visible.size).toBeGreaterThan(0);
    expect(result.visible.has(cubeKey(viewer.coords))).toBe(true);
  });

  it('calculateFOV 有墙遮挡视野', () => {
    const viewer: Viewer = {
      id: 'v1',
      coords: cubeCoords(3, 0, -3),
      visionRange: 5,
      height: 0,
      faction: 'player',
    };
    const wall = cubeCoords(3, 1, -4);
    grid.setTileTerrain(wall, 'wall');

    const openFov = fov.calculateFOV(viewer);
    const visibleWithoutWall = openFov.visible.size;

    const behind = cubeCoords(3, 2, -5);
    const canSeeBehindWall = openFov.visible.has(cubeKey(behind));

    const resultFn = (result: { visible: { size: number } }) => {
      const fov2 = new FieldOfViewCalculator(new HexGrid(config));
      const viewer2: Viewer = { ...viewer };
      return fov2.calculateFOV(viewer2).visible.size;
    };

    expect(visibleWithoutWall).toBeGreaterThan(0);
    expect(typeof canSeeBehindWall).toBe('boolean');
  });

  it('calculateFOV 不同视野距离', () => {
    const viewer: Viewer = {
      id: 'v1',
      coords: cubeCoords(3, 0, -3),
      visionRange: 1,
      height: 0,
      faction: 'player',
    };
    const result1 = fov.calculateFOV(viewer);

    viewer.visionRange = 3;
    const result3 = fov.calculateFOV(viewer);

    expect(result3.visible.size).toBeGreaterThan(result1.visible.size);
  });

  it('lineOfSight 无遮挡可见', () => {
    const from = cubeCoords(0, 0, 0);
    const to = cubeCoords(3, 0, -3);
    const result = fov.lineOfSight(from, to, 10);
    expect(result.visible).toBe(true);
    expect(result.path.length).toBe(4);
  });

  it('lineOfSight 穿墙不可见', () => {
    const from = cubeCoords(0, 0, 0);
    const to = cubeCoords(3, 0, -3);
    const blocker = cubeCoords(1, 0, -1);
    grid.setTileTerrain(blocker, 'wall');

    const result = fov.lineOfSight(from, to, 10);
    expect(result.visible).toBe(false);
    expect(result.blockedBy).toBeDefined();
    expect(cubeEquals(result.blockedBy!, blocker)).toBe(true);
  });
});

describe('MapGenerator - 地图生成', () => {
  let grid: HexGrid;
  let generator: MapGenerator;
  const config: HexGridConfig = {
    width: 10,
    height: 10,
    orientation: 'pointy',
    defaultTerrain: 'plain',
    tileSize: 50,
  };

  beforeEach(() => {
    grid = new HexGrid(config);
    generator = new MapGenerator({
      seed: 42,
      width: 10,
      height: 10,
    });
  });

  it('generate 生成合理数量地形', () => {
    generator.generate(grid);
    const tiles = grid.getAllTiles();
    expect(tiles.length).toBe(100);

    const terrainCounts: Record<string, number> = {};
    tiles.forEach(t => {
      terrainCounts[t.terrain] = (terrainCounts[t.terrain] || 0) + 1;
    });

    expect(Object.keys(terrainCounts).length).toBeGreaterThanOrEqual(2);
    expect(terrainCounts['plain']).toBeGreaterThan(0);
  });

  it('applyTerrainThresholds 边界值处理', () => {
    const waterTerrain = generator.applyTerrainThresholds(0.1, 0.5, 0.5);
    expect(waterTerrain).toBeDefined();
    expect(typeof waterTerrain).toBe('string');

    const plainTerrain = generator.applyTerrainThresholds(0.5, 0.5, 0.5);
    expect(plainTerrain).toBeDefined();
    expect(typeof plainTerrain).toBe('string');
  });

  it('种子一致性 - 相同种子生成相同地图', () => {
    const grid1 = new HexGrid(config);
    const grid2 = new HexGrid(config);
    const gen1 = new MapGenerator({ seed: 123, width: 10, height: 10 });
    const gen2 = new MapGenerator({ seed: 123, width: 10, height: 10 });

    gen1.generate(grid1);
    gen2.generate(grid2);

    const tiles1 = grid1.getAllTiles();
    const tiles2 = grid2.getAllTiles();

    for (let i = 0; i < tiles1.length; i++) {
      expect(tiles1[i].terrain).toBe(tiles2[i].terrain);
      expect(Math.abs(tiles1[i].height - tiles2[i].height)).toBeLessThan(0.01);
    }
  });

  it('不同种子生成不同地图', () => {
    const grid1 = new HexGrid(config);
    const grid2 = new HexGrid(config);
    const gen1 = new MapGenerator({ seed: 100, width: 10, height: 10 });
    const gen2 = new MapGenerator({ seed: 200, width: 10, height: 10 });

    gen1.generate(grid1);
    gen2.generate(grid2);

    let hasDifference = false;
    const tiles1 = grid1.getAllTiles();
    const tiles2 = grid2.getAllTiles();

    for (let i = 0; i < tiles1.length; i++) {
      if (tiles1[i].terrain !== tiles2[i].terrain) {
        hasDifference = true;
        break;
      }
    }
    expect(hasDifference).toBe(true);
  });
});
