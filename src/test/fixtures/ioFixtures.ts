import type { FloorPlan, Wall, Opening, Room } from '@/types/floorplan';
import type { Point2D } from '@/types/geometry';
import { generateId } from '@/utils/geometry';
import { createDefaultFloorPlan } from '@/store/useFloorPlanStore';
import { createTestPoint, createTestWall, createRectangularRoomWalls } from './drawingFixtures';

export const createTestFloorPlan = (): FloorPlan => {
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

export const createComplexFloorPlan = (): FloorPlan => {
  const floorPlan = createDefaultFloorPlan();
  
  const wall1 = createTestWall(createTestPoint(0, 0), createTestPoint(8, 0));
  const wall2 = createTestWall(createTestPoint(8, 0), createTestPoint(8, 6));
  const wall3 = createTestWall(createTestPoint(8, 6), createTestPoint(0, 6));
  const wall4 = createTestWall(createTestPoint(0, 6), createTestPoint(0, 0));
  const wall5 = createTestWall(createTestPoint(4, 0), createTestPoint(4, 6));
  
  const door: Opening = {
    id: generateId(),
    type: 'door',
    wallId: wall5.id,
    positionX: 2,
    width: 0.9,
    height: 2.1,
    sillHeight: 0,
    swingAngle: 90,
  };
  
  const window1: Opening = {
    id: generateId(),
    type: 'window',
    wallId: wall2.id,
    positionX: 2,
    width: 1.2,
    height: 1.5,
    sillHeight: 0.9,
  };
  
  const window2: Opening = {
    id: generateId(),
    type: 'window',
    wallId: wall2.id,
    positionX: 4,
    width: 1.2,
    height: 1.5,
    sillHeight: 0.9,
  };
  
  const room1: Room = {
    id: generateId(),
    boundary: [
      createTestPoint(0, 0),
      createTestPoint(4, 0),
      createTestPoint(4, 6),
      createTestPoint(0, 6),
    ],
    height: 2.8,
    floorMaterialId: 'mat-floor-wood',
    ceilingMaterialId: 'mat-wall-white',
    name: '客厅',
  };
  
  const room2: Room = {
    id: generateId(),
    boundary: [
      createTestPoint(4, 0),
      createTestPoint(8, 0),
      createTestPoint(8, 6),
      createTestPoint(4, 6),
    ],
    height: 2.8,
    floorMaterialId: 'mat-floor-marble',
    ceilingMaterialId: 'mat-wall-white',
    name: '卧室',
  };
  
  floorPlan.walls = [wall1, wall2, wall3, wall4, wall5];
  floorPlan.openings = [door, window1, window2];
  floorPlan.rooms = [room1, room2];
  floorPlan.name = '两室一厅';
  floorPlan.description = '测试用复杂户型';
  
  return floorPlan;
};

export const SAMPLE_DXF_CONTENT = `0
SECTION
2
HEADER
9
$ACADVER
1
AC1009
0
ENDSEC
0
SECTION
2
TABLES
0
TABLE
2
LAYER
70
6
0
LAYER
2
WALL
70
0
62
7
6
CONTINUOUS
0
LAYER
2
DOOR
70
0
62
1
6
CONTINUOUS
0
LAYER
2
WINDOW
70
0
62
2
6
CONTINUOUS
0
ENDTAB
0
ENDSEC
0
SECTION
2
ENTITIES
0
LINE
8
WALL
10
0.0
20
0.0
30
0.0
11
5000.0
21
0.0
31
0.0
0
LINE
8
WALL
10
5000.0
20
0.0
30
0.0
11
5000.0
21
4000.0
31
0.0
0
LINE
8
WALL
10
5000.0
20
4000.0
30
0.0
11
0.0
21
4000.0
31
0.0
0
LINE
8
WALL
10
0.0
20
4000.0
30
0.0
11
0.0
21
0.0
31
0.0
0
INSERT
8
DOOR
10
2000.0
20
2000.0
30
0.0
2
DOOR_900
0
INSERT
8
WINDOW
10
3000.0
20
0.0
30
0.0
2
WINDOW_1200
0
ENDSEC
0
EOF
`;

export const CORRUPTED_DXF_CONTENT = `0
SECTION
2
HEADER
9
$ACADVER
1
AC1009
0
ENDSEC
0
SECTION
2
ENTITIES
0
LINE
8
WALL
10
0.0
20
0.0
30
MALFORMED_DATA
11
5000.0
21
0.0
31
0.0
0
LINE
8
WALL
THIS_IS_AN_ERROR
20
0.0
30
0.0
11
5000.0
21
4000.0
31
0.0
0
ENDSEC
0
EOF
`;

export const createDXFTestCase = (wallCount: number) => {
  const entities: string[] = [];
  
  for (let i = 0; i < wallCount; i++) {
    entities.push(`0
LINE
8
WALL
10
${i * 1000}.0
20
0.0
30
0.0
11
${(i + 1) * 1000}.0
21
0.0
31
0.0`);
  }
  
  return `0
SECTION
2
ENTITIES
${entities.join('\n')}
0
ENDSEC
0
EOF
`;
};

export const validateFloorPlanData = (
  actual: FloorPlan,
  expected: FloorPlan
): { valid: boolean; errors: string[] } => {
  const errors: string[] = [];
  
  if (actual.walls.length !== expected.walls.length) {
    errors.push(`Wall count mismatch: expected ${expected.walls.length}, got ${actual.walls.length}`);
  }
  
  if (actual.rooms.length !== expected.rooms.length) {
    errors.push(`Room count mismatch: expected ${expected.rooms.length}, got ${actual.rooms.length}`);
  }
  
  if (actual.openings.length !== expected.openings.length) {
    errors.push(`Opening count mismatch: expected ${expected.openings.length}, got ${actual.openings.length}`);
  }
  
  actual.walls.forEach((wall, index) => {
    const expectedWall = expected.walls[index];
    if (expectedWall) {
      if (Math.abs(wall.start.x - expectedWall.start.x) > 0.001) {
        errors.push(`Wall ${index} start.x mismatch`);
      }
      if (Math.abs(wall.start.y - expectedWall.start.y) > 0.001) {
        errors.push(`Wall ${index} start.y mismatch`);
      }
    }
  });
  
  return { valid: errors.length === 0, errors };
};

export const ioTestFixtures = {
  createTestFloorPlan,
  createComplexFloorPlan,
  SAMPLE_DXF_CONTENT,
  CORRUPTED_DXF_CONTENT,
  createDXFTestCase,
  validateFloorPlanData,
};

export default ioTestFixtures;
