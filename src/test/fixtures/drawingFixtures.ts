import type { Point2D } from '@/types/geometry';
import type { FloorPlan, Wall, Room } from '@/types/floorplan';
import { generateId } from '@/utils/geometry';
import { createDefaultFloorPlan } from '@/store/useFloorPlanStore';

export const createTestPoint = (x: number, y: number): Point2D => ({ x, y });

export const createTestWall = (
  start: Point2D,
  end: Point2D,
  options: Partial<Wall> = {}
): Wall => ({
  id: generateId(),
  type: 'straight',
  start,
  end,
  thickness: 0.2,
  height: 2.8,
  materialId: 'mat-wall-white',
  ...options,
});

export const createTestArcWall = (
  center: Point2D,
  radius: number,
  startAngle: number,
  endAngle: number,
  options: Partial<Wall> = {}
): Wall => ({
  id: generateId(),
  type: 'arc',
  start: createTestPoint(
    center.x + Math.cos(startAngle) * radius,
    center.y + Math.sin(startAngle) * radius
  ),
  end: createTestPoint(
    center.x + Math.cos(endAngle) * radius,
    center.y + Math.sin(endAngle) * radius
  ),
  center,
  radius,
  thickness: 0.2,
  height: 2.8,
  materialId: 'mat-wall-white',
  ...options,
});

export const createRectangularRoomWalls = (
  origin: Point2D = createTestPoint(0, 0),
  width: number = 5,
  depth: number = 4
): Wall[] => {
  const { x, y } = origin;
  return [
    createTestWall(createTestPoint(x, y), createTestPoint(x + width, y)),
    createTestWall(createTestPoint(x + width, y), createTestPoint(x + width, y + depth)),
    createTestWall(createTestPoint(x + width, y + depth), createTestPoint(x, y + depth)),
    createTestWall(createTestPoint(x, y + depth), createTestPoint(x, y)),
  ];
};

export const createTestRoom = (
  boundary: Point2D[],
  options: Partial<Room> = {}
): Room => ({
  id: generateId(),
  boundary,
  height: 2.8,
  floorMaterialId: 'mat-floor-wood',
  ceilingMaterialId: 'mat-wall-white',
  name: '客厅',
  ...options,
});

export const createTestFloorPlanWithRoom = (): FloorPlan => {
  const floorPlan = createDefaultFloorPlan();
  const walls = createRectangularRoomWalls();
  floorPlan.walls = walls;
  
  const room: Room = {
    id: generateId(),
    boundary: [
      createTestPoint(0, 0),
      createTestPoint(5, 0),
      createTestPoint(5, 4),
      createTestPoint(0, 4),
    ],
    height: 2.8,
    floorMaterialId: 'mat-floor-wood',
    ceilingMaterialId: 'mat-wall-white',
    name: '客厅',
  };
  floorPlan.rooms = [room];
  
  return floorPlan;
};

export const createSelfIntersectingWalls = (): Wall[] => [
  createTestWall(createTestPoint(0, 0), createTestPoint(5, 5)),
  createTestWall(createTestPoint(0, 5), createTestPoint(5, 0)),
];

export const createLShapedRoomWalls = (): Wall[] => {
  return [
    createTestWall(createTestPoint(0, 0), createTestPoint(6, 0)),
    createTestWall(createTestPoint(6, 0), createTestPoint(6, 6)),
    createTestWall(createTestPoint(6, 6), createTestPoint(3, 6)),
    createTestWall(createTestPoint(3, 6), createTestPoint(3, 3)),
    createTestWall(createTestPoint(3, 3), createTestPoint(0, 3)),
    createTestWall(createTestPoint(0, 3), createTestPoint(0, 0)),
  ];
};

export const drawingTestFixtures = {
  createTestPoint,
  createTestWall,
  createTestArcWall,
  createRectangularRoomWalls,
  createTestRoom,
  createTestFloorPlanWithRoom,
  createSelfIntersectingWalls,
  createLShapedRoomWalls,
};

export default drawingTestFixtures;
