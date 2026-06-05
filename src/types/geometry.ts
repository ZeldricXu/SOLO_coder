export interface Point2D {
  x: number;
  y: number;
}

export interface Point3D {
  x: number;
  y: number;
  z: number;
}

export interface RGB {
  r: number;
  g: number;
  b: number;
}

export interface Vector2 {
  x: number;
  y: number;
}

export interface Vector3 {
  x: number;
  y: number;
  z: number;
}

export interface Line2D {
  start: Point2D;
  end: Point2D;
}

export interface Arc2D {
  center: Point2D;
  radius: number;
  startAngle: number;
  endAngle: number;
}

export interface Rect2D {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Polygon2D {
  points: Point2D[];
}

export interface Transform2D {
  position: Point2D;
  rotation: number;
  scale: number;
}

export interface AABB {
  min: Point2D;
  max: Point2D;
}
