import type { Point3D } from './geometry';

export type DrawingPrimitiveType = 'line' | 'arrow' | 'freehand' | 'text';

export interface DrawingVertex {
  position: Point3D;
  normal?: Point3D;
  surfaceId?: string;
}

export interface DrawingPrimitive {
  id: string;
  type: DrawingPrimitiveType;
  vertices: DrawingVertex[];
  color: string;
  lineWidth: number;
  text?: string;
  createdAt: number;
  updatedAt: number;
}

export interface DrawingAnnotation {
  id: string;
  annotationId?: string;
  author: string;
  color: string;
  lineWidth: number;
  primitives: DrawingPrimitive[];
  createdAt: number;
  updatedAt: number;
  visible: boolean;
}

export interface DrawingSession {
  active: boolean;
  color: string;
  lineWidth: number;
  tool: DrawingPrimitiveType;
  currentPrimitive: DrawingPrimitive | null;
}

export interface DrawingSurfaceHit {
  point: Point3D;
  normal: Point3D;
  distance: number;
  surfaceId?: string;
  objectType: 'wall' | 'floor' | 'ceiling' | 'furniture' | 'opening';
}

export const DEFAULT_DRAWING_COLORS = [
  '#ff4444',
  '#ff9800',
  '#ffeb3b',
  '#4caf50',
  '#2196f3',
  '#9c27b0',
  '#ffffff',
  '#000000',
] as const;

export const DEFAULT_DRAWING_LINE_WIDTHS = [1, 2, 3, 4, 6, 8] as const;
