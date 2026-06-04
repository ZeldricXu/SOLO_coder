import { Vec3 } from './vectors';

export type FieldType = 'electric' | 'magnetic' | 'thermal' | 'velocity' | 'pressure' | 'density';

export type BoundaryConditionType = 'dirichlet' | 'neumann';

export interface BoundaryCondition {
  type: BoundaryConditionType;
  value: number | Vec3;
  faceIndex?: number;
}

export interface FieldGrid {
  dimensions: Vec3;
  resolution: Vec3;
  cellSize: Vec3;
  origin: Vec3;
}

export interface ScalarField {
  id: string;
  type: FieldType;
  grid: FieldGrid;
  data: Float32Array;
  time: number;
}

export interface VectorField {
  id: string;
  type: FieldType;
  grid: FieldGrid;
  dataX: Float32Array;
  dataY: Float32Array;
  dataZ: Float32Array;
  time: number;
}

export type Field = ScalarField | VectorField;

export interface Isosurface {
  fieldId: string;
  isovalue: number;
  vertices: Float32Array;
  normals: Float32Array;
  indices: Uint32Array;
}

export interface FieldVisualization {
  fieldId: string;
  showArrows: boolean;
  showIsosurface: boolean;
  showHeatmap: boolean;
  showStreamlines: boolean;
  showParticles: boolean;
  arrowSpacing: number;
  arrowScale: number;
  isovalue: number;
  colormap: string;
  particleCount: number;
  particleColorBy: 'velocity' | 'temperature' | 'pressure' | 'density';
  streamlineDensity: number;
}

export interface StreamlinePoint {
  position: Vec3;
  direction: Vec3;
  magnitude: number;
}

export interface Streamline {
  id: string;
  fieldId: string;
  points: StreamlinePoint[];
  color: string;
}

export interface ParticleState {
  id: string;
  position: Vec3;
  previousPosition: Vec3;
  age: number;
  maxAge: number;
  color: Vec3;
  size: number;
}

export interface ParticleSystemConfig {
  fieldId: string;
  emitRate: number;
  maxParticles: number;
  particleLifetime: number;
  colorBy: 'velocity' | 'temperature' | 'pressure' | 'density';
  particleSize: number;
  speedScale: number;
}

export interface CrossSectionPlane {
  id: string;
  normal: Vec3;
  position: Vec3;
  fieldId: string;
  width: number;
  height: number;
  resolution: Vec3;
  colormap: string;
}
