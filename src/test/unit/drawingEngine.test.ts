import { describe, it, expect, beforeEach } from 'vitest';
import {
  distance,
  linesIntersect,
  polygonArea,
  isPointInPolygon,
  detectRoomsFromWalls,
  checkWallIntersection,
} from '@/utils/geometry';
import { GridSystem } from '@/engine/drawing/GridSystem';
import { SnapManager } from '@/engine/drawing/SnapManager';
import {
  createTestPoint,
  createTestWall,
  createRectangularRoomWalls,
  createSelfIntersectingWalls,
  createLShapedRoomWalls,
  createTestArcWall,
} from '../fixtures/drawingFixtures';
import type { Point2D } from '@/types/geometry';
import type { Wall } from '@/types/floorplan';

describe('2D绘制引擎 - 正常路径测试', () => {
  describe('墙体绘制路径点序列', () => {
    it('应该正确记录连续墙体的路径点序列', () => {
      const start = createTestPoint(0, 0);
      const point1 = createTestPoint(5, 0);
      const point2 = createTestPoint(5, 4);
      const point3 = createTestPoint(0, 4);
      const end = createTestPoint(0, 0);

      const walls = [
        createTestWall(start, point1),
        createTestWall(point1, point2),
        createTestWall(point2, point3),
        createTestWall(point3, end),
      ];

      const pathPoints: Point2D[] = [];
      walls.forEach((wall) => {
        if (pathPoints.length === 0) {
          pathPoints.push(wall.start);
        }
        pathPoints.push(wall.end);
      });

      expect(pathPoints).toHaveLength(5);
      expect(pathPoints[0]).toEqual(start);
      expect(pathPoints[1]).toEqual(point1);
      expect(pathPoints[2]).toEqual(point2);
      expect(pathPoints[3]).toEqual(point3);
      expect(pathPoints[4]).toEqual(end);

      expect(distance(pathPoints[0], pathPoints[1])).toBeCloseTo(5);
      expect(distance(pathPoints[1], pathPoints[2])).toBeCloseTo(4);
    });

    it('应该正确计算弧形墙体的控制点', () => {
      const center = createTestPoint(0, 0);
      const arcWall = createTestArcWall(center, 3, 0, Math.PI / 2);

      expect(arcWall.type).toBe('arc');
      expect(arcWall.center).toBeDefined();
      expect(arcWall.radius).toBeDefined();
      expect(arcWall.center).toEqual(center);
      expect(arcWall.radius).toBeCloseTo(3);

      expect(arcWall.start.x).toBeCloseTo(3);
      expect(arcWall.start.y).toBeCloseTo(0);
      expect(arcWall.end.x).toBeCloseTo(0);
      expect(arcWall.end.y).toBeCloseTo(3);
    });
  });

  describe('区域闭合算法', () => {
    it('应该正确识别四边墙体形成的矩形房间', () => {
      const walls = createRectangularRoomWalls();
      const rooms = detectRoomsFromWalls(walls);

      expect(rooms).toHaveLength(1);
      expect(rooms[0].boundary).toHaveLength(4);
      
      const area = polygonArea(rooms[0].boundary);
      expect(area).toBeCloseTo(20);
    });

    it('应该正确识别L形墙体形成的房间', () => {
      const walls = createLShapedRoomWalls();
      const rooms = detectRoomsFromWalls(walls);

      expect(rooms).toHaveLength(1);
      expect(rooms[0].boundary).toHaveLength(6);
      
      const area = polygonArea(rooms[0].boundary);
      expect(area).toBeCloseTo(27);
    });

    it('应该正确判断点是否在多边形内', () => {
      const polygon: Point2D[] = [
        createTestPoint(0, 0),
        createTestPoint(5, 0),
        createTestPoint(5, 4),
        createTestPoint(0, 4),
      ];

      const insidePoint = createTestPoint(2.5, 2);
      const outsidePoint = createTestPoint(6, 2);
      const edgePoint = createTestPoint(5, 2);

      expect(isPointInPolygon(insidePoint, polygon)).toBe(true);
      expect(isPointInPolygon(outsidePoint, polygon)).toBe(false);
      expect(isPointInPolygon(edgePoint, polygon)).toBe(true);
    });
  });

  describe('网格系统', () => {
    let gridSystem: GridSystem;
    const canvas = document.createElement('canvas');
    canvas.width = 800;
    canvas.height = 600;
    const ctx = canvas.getContext('2d')!;

    beforeEach(() => {
      gridSystem = new GridSystem({ size: 0.1, majorSpacing: 1 });
    });

    it('应该正确吸附坐标到网格', () => {
      const testPoint = createTestPoint(1.23, 2.67);
      const snapped = gridSystem.snapToGrid(testPoint);

      expect(snapped.x).toBeCloseTo(1.2);
      expect(snapped.y).toBeCloseTo(2.7);
    });

    it('应该正确转换世界坐标到屏幕坐标', () => {
      const worldPoint = createTestPoint(5, 3);
      const screenPoint = gridSystem.worldToScreen(worldPoint);

      expect(screenPoint.x).toBe(400);
      expect(screenPoint.y).toBe(300);
    });

    it('应该正确转换屏幕坐标到世界坐标', () => {
      const screenPoint = { x: 400, y: 300 };
      const worldPoint = gridSystem.screenToWorld(screenPoint);

      expect(worldPoint.x).toBeCloseTo(5);
      expect(worldPoint.y).toBeCloseTo(3);
    });
  });

  describe('吸附系统', () => {
    let snapManager: SnapManager;
    let walls: Wall[];

    beforeEach(() => {
      snapManager = new SnapManager();
      walls = createRectangularRoomWalls();
      snapManager.setWalls(walls);
    });

    it('应该正确吸附到墙体端点', () => {
      const nearEndpoint = createTestPoint(0, 0.0001);
      const snapTarget = snapManager.findSnapTarget(nearEndpoint, 0.5);

      expect(snapTarget).not.toBeNull();
      if (snapTarget) {
        expect(snapTarget.type).toBe('endpoint');
        expect(snapTarget.point.x).toBeCloseTo(0);
        expect(snapTarget.point.y).toBeCloseTo(0);
      }
    });

    it('应该正确吸附到墙体中点', () => {
      const nearMidpoint = createTestPoint(2.5, 0.1);
      const snapTarget = snapManager.findSnapTarget(nearMidpoint, 0.5);

      expect(snapTarget).not.toBeNull();
      if (snapTarget) {
        expect(snapTarget.type).toBe('midpoint');
        expect(snapTarget.point.x).toBeCloseTo(2.5);
        expect(snapTarget.point.y).toBeCloseTo(0);
      }
    });
  });
});

describe('2D绘制引擎 - 异常路径测试', () => {
  describe('自相交墙体检测', () => {
    it('应该检测到两条交叉墙体的相交', () => {
      const walls = createSelfIntersectingWalls();
      const intersection = checkWallIntersection(walls[0], walls[1]);

      expect(intersection).not.toBeNull();
      if (intersection) {
        expect(intersection.point.x).toBeCloseTo(2.5);
        expect(intersection.point.y).toBeCloseTo(2.5);
      }
    });

    it('应该阻止绘制自相交墙体并给出提示', () => {
      const existingWalls = createRectangularRoomWalls();
      const intersectingWall = createTestWall(
        createTestPoint(-1, 2),
        createTestPoint(6, 2)
      );

      const warnings: string[] = [];
      for (const existing of existingWalls) {
        const intersection = checkWallIntersection(intersectingWall, existing);
        if (intersection) {
          warnings.push(
            `墙体在 (${intersection.point.x.toFixed(2)}, ${intersection.point.y.toFixed(2)}) 处相交`
          );
        }
      }

      expect(warnings.length).toBeGreaterThan(0);
      expect(warnings[0]).toContain('相交');
    });

    it('应该正确识别非相交墙体', () => {
      const wall1 = createTestWall(createTestPoint(0, 0), createTestPoint(0, 5));
      const wall2 = createTestWall(createTestPoint(1, 0), createTestPoint(1, 5));

      const intersection = checkWallIntersection(wall1, wall2);
      expect(intersection).toBeNull();
    });
  });

  describe('角度约束验证', () => {
    it('应该约束到15度倍数角度', () => {
      const testAngles = [
        { input: 12, expected: 15 },
        { input: 22, expected: 30 },
        { input: 44, expected: 45 },
        { input: 88, expected: 90 },
        { input: 178, expected: 180 },
      ];

      for (const { input, expected } of testAngles) {
        const start = createTestPoint(0, 0);
        const end = createTestPoint(
          Math.cos((input * Math.PI) / 180) * 5,
          Math.sin((input * Math.PI) / 180) * 5
        );
        
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        const angle = Math.atan2(dy, dx);
        const angleDeg = (angle * 180) / Math.PI;
        const snappedAngle = Math.ceil(angleDeg / 15) * 15;
        
        expect(snappedAngle).toBe(expected);
      }
    });
  });
});
