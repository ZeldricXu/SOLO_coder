import type { Point2D, Point3D, RGB } from './geometry';
import type { DrawingPrimitive } from './drawing';

export const FLOOR_PLAN_VERSION = '1.0.0';

export type WallType = 'straight' | 'arc';
export type OpeningType = 'door' | 'window';
export type LightType = 'point' | 'area' | 'spot' | 'ambient';
export type ViewMode = '2d' | '3d' | 'split';
export type ToolType = 'select' | 'wall-straight' | 'wall-arc' | 'door' | 'window' | 'measure' | 'annotation' | 'annotation-draw' | 'furniture' | 'sketchfab';

export interface Project {
  id: string;
  name: string;
  version: string;
  createdAt: string;
  updatedAt: string;
  settings: ProjectSettings;
}

export interface ProjectSettings {
  gridSize: number;
  wallThickness: number;
  wallHeight: number;
  snapToGrid: boolean;
  angleConstraint: number;
  units: 'metric' | 'imperial';
}

export interface Wall {
  id: string;
  type: WallType;
  start: Point2D;
  end: Point2D;
  center?: Point2D;
  radius?: number;
  thickness: number;
  height: number;
  materialId: string;
  name?: string;
}

export interface Room {
  id: string;
  name: string;
  boundary: Point2D[];
  floorMaterialId: string;
  ceilingMaterialId: string;
  height: number;
}

export interface Opening {
  id: string;
  type: OpeningType;
  wallId: string;
  positionX: number;
  width: number;
  height: number;
  sillHeight?: number;
  swingAngle?: number;
  position?: Point2D;
}

export interface FurnitureItem {
  id: string;
  modelId: string;
  name: string;
  category: string;
  position: Point3D;
  rotation: number;
  scale: number;
  materialOverrides?: Record<string, string>;
}

export interface LightSource {
  id: string;
  type: LightType;
  name: string;
  position: Point3D;
  target?: Point3D;
  color: RGB;
  intensity: number;
  castShadow: boolean;
  params: LightParams;
}

export interface LightParams {
  distance?: number;
  decay?: number;
  angle?: number;
  penumbra?: number;
  width?: number;
  height?: number;
}

export interface Material {
  id: string;
  name: string;
  type: 'pbr' | 'standard';
  properties: PBRProperties | StandardProperties;
}

export interface PBRProperties {
  color: RGB;
  roughness: number;
  metalness: number;
  emissive?: RGB;
  emissiveIntensity?: number;
  normalMap?: string;
  roughnessMap?: string;
  metalnessMap?: string;
}

export interface StandardProperties {
  color: RGB;
  opacity: number;
  transparent: boolean;
}

export interface Annotation {
  id: string;
  projectId?: string;
  position: Point3D;
  author: string;
  content: string;
  screenshot?: string;
  createdAt: number;
  status: 'open' | 'resolved';
  drawings?: DrawingPrimitive[];
}

export interface FloorPlan {
  version: typeof FLOOR_PLAN_VERSION;
  project: Project;
  name?: string;
  thumbnail?: string;
  description?: string;
  walls: Wall[];
  rooms: Room[];
  openings: Opening[];
  furniture: FurnitureItem[];
  lights: LightSource[];
  materials: Material[];
  annotations: Annotation[];
}

export interface Measurement {
  id: string;
  type: 'distance' | 'area' | 'volume';
  points: Point3D[];
  value: number;
  unit: string;
}

export interface QuoteItem {
  id: string;
  name: string;
  quantity: number;
  unit: string;
  unitPrice: number;
  totalPrice: number;
  materialId?: string;
}

export interface Quote {
  id: string;
  projectId: string;
  items: QuoteItem[];
  total: number;
  currency: string;
  createdAt: string;
}
