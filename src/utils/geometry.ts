import type { Point2D, Point3D, Line2D, Polygon2D, AABB } from '@/types/geometry';
import type { Wall } from '@/types/floorplan';

export const distance = (a: Point2D, b: Point2D): number => {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  return Math.sqrt(dx * dx + dy * dy);
};

export const distance3D = (a: Point3D, b: Point3D): number => {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const dz = b.z - a.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
};

export const angle = (from: Point2D, to: Point2D): number => {
  return Math.atan2(to.y - from.y, to.x - from.x);
};

export const snapToGrid = (point: Point2D, gridSize: number): Point2D => {
  return {
    x: Math.round(point.x / gridSize) * gridSize,
    y: Math.round(point.y / gridSize) * gridSize,
  };
};

export const snapAngle = (from: Point2D, to: Point2D, constraint: number): Point2D => {
  const ang = angle(from, to);
  const constrainedAng = Math.round(ang / (constraint * Math.PI / 180)) * (constraint * Math.PI / 180);
  const dist = distance(from, to);
  return {
    x: from.x + Math.cos(constrainedAng) * dist,
    y: from.y + Math.sin(constrainedAng) * dist,
  };
};

export const lineLength = (line: Line2D): number => {
  return distance(line.start, line.end);
};

export const pointToLineDistance = (point: Point2D, line: Line2D): number => {
  const { start, end } = line;
  const A = point.x - start.x;
  const B = point.y - start.y;
  const C = end.x - start.x;
  const D = end.y - start.y;

  const dot = A * C + B * D;
  const lenSq = C * C + D * D;
  let param = -1;

  if (lenSq !== 0) param = dot / lenSq;

  let xx, yy;

  if (param < 0) {
    xx = start.x;
    yy = start.y;
  } else if (param > 1) {
    xx = end.x;
    yy = end.y;
  } else {
    xx = start.x + param * C;
    yy = start.y + param * D;
  }

  return distance(point, { x: xx, y: yy });
};

export const closestPointOnLine = (point: Point2D, line: Line2D): Point2D => {
  const { start, end } = line;
  const A = point.x - start.x;
  const B = point.y - start.y;
  const C = end.x - start.x;
  const D = end.y - start.y;

  const dot = A * C + B * D;
  const lenSq = C * C + D * D;
  let param = -1;

  if (lenSq !== 0) param = dot / lenSq;

  if (param < 0) return { ...start };
  if (param > 1) return { ...end };

  return {
    x: start.x + param * C,
    y: start.y + param * D,
  };
};

export const linesIntersect = (line1: Line2D, line2: Line2D): Point2D | null => {
  const { start: p1, end: p2 } = line1;
  const { start: p3, end: p4 } = line2;

  const denom = (p4.y - p3.y) * (p2.x - p1.x) - (p4.x - p3.x) * (p2.y - p1.y);
  if (Math.abs(denom) < 0.0001) return null;

  const ua = ((p4.x - p3.x) * (p1.y - p3.y) - (p4.y - p3.y) * (p1.x - p3.x)) / denom;
  const ub = ((p2.x - p1.x) * (p1.y - p3.y) - (p2.y - p1.y) * (p1.x - p3.x)) / denom;

  if (ua >= 0 && ua <= 1 && ub >= 0 && ub <= 1) {
    return {
      x: p1.x + ua * (p2.x - p1.x),
      y: p1.y + ua * (p2.y - p1.y),
    };
  }

  return null;
};

export const polygonArea = (points: Point2D[]): number => {
  let area = 0;
  const n = points.length;
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n;
    area += points[i].x * points[j].y;
    area -= points[j].x * points[i].y;
  }
  return Math.abs(area / 2);
};

export const isPointInPolygon = (point: Point2D, polygon: Point2D[]): boolean => {
  let inside = false;
  const n = polygon.length;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = polygon[i].x, yi = polygon[i].y;
    const xj = polygon[j].x, yj = polygon[j].y;

    if (((yi > point.y) !== (yj > point.y)) &&
        (point.x < (xj - xi) * (point.y - yi) / (yj - yi) + xi)) {
      inside = !inside;
    }
  }
  return inside;
};

export const getWallPoints = (wall: Wall): Point2D[] => {
  if (wall.type === 'arc' && wall.center && wall.radius) {
    const segments = 32;
    const startAng = angle(wall.center, wall.start);
    const endAng = angle(wall.center, wall.end);
    const points: Point2D[] = [];
    for (let i = 0; i <= segments; i++) {
      const t = i / segments;
      const ang = startAng + (endAng - startAng) * t;
      points.push({
        x: wall.center.x + Math.cos(ang) * wall.radius,
        y: wall.center.y + Math.sin(ang) * wall.radius,
      });
    }
    return points;
  }
  return [wall.start, wall.end];
};

export const getWallAABB = (wall: Wall, offset: number = 0): AABB => {
  const points = getWallPoints(wall);
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of points) {
    minX = Math.min(minX, p.x);
    minY = Math.min(minY, p.y);
    maxX = Math.max(maxX, p.x);
    maxY = Math.max(maxY, p.y);
  }
  return {
    min: { x: minX - offset, y: minY - offset },
    max: { x: maxX + offset, y: maxY + offset },
  };
};

export const polygonCenter = (points: Point2D[]): Point2D => {
  let sx = 0, sy = 0;
  for (const p of points) {
    sx += p.x;
    sy += p.y;
  }
  return { x: sx / points.length, y: sy / points.length };
};

export const generateId = (): string => {
  return `${Date.now().toString(36)}-${Math.random().toString(36).substr(2, 9)}`;
};

export const normalizeAngle = (ang: number): number => {
  while (ang < 0) ang += Math.PI * 2;
  while (ang >= Math.PI * 2) ang -= Math.PI * 2;
  return ang;
};

export const midpoint = (a: Point2D, b: Point2D): Point2D => ({
  x: (a.x + b.x) / 2,
  y: (a.y + b.y) / 2,
});

export const offsetPoint = (p: Point2D, dist: number, ang: number): Point2D => ({
  x: p.x + Math.cos(ang) * dist,
  y: p.y + Math.sin(ang) * dist,
});

export const pointsEqual = (a: Point2D, b: Point2D, tolerance: number = 0.001): boolean => {
  return Math.abs(a.x - b.x) < tolerance && Math.abs(a.y - b.y) < tolerance;
};

export const simplifyPolygon = (points: Point2D[], tolerance: number = 0.01): Point2D[] => {
  if (points.length < 3) return points;
  
  const result: Point2D[] = [points[0]];
  for (let i = 1; i < points.length - 1; i++) {
    const prev = result[result.length - 1];
    const curr = points[i];
    const next = points[i + 1];
    
    const ang1 = angle(prev, curr);
    const ang2 = angle(curr, next);
    
    if (Math.abs(normalizeAngle(ang2 - ang1)) > tolerance) {
      result.push(curr);
    }
  }
  result.push(points[points.length - 1]);
  
  return result;
};
