export interface Point {
  x: number;
  y: number;
  pressure?: number;
  timestamp?: number;
}

export interface BoundingBox {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

export type StrokeCap = 'round' | 'square' | 'butt';
export type StrokeJoin = 'round' | 'bevel' | 'miter';

export interface StrokeStyle {
  color: string;
  width: number;
  opacity: number;
  cap?: StrokeCap;
  join?: StrokeJoin;
  dashPattern?: number[];
}

export interface Stroke {
  id: string;
  points: Point[];
  style: StrokeStyle;
  layerId: string;
  userId: string;
  createdAt: number;
  updatedAt: number;
  bounds?: BoundingBox;
}

export type ShapeType = 'rectangle' | 'ellipse' | 'line' | 'arrow' | 'triangle' | 'polygon';

export interface ShapeStyle {
  fill?: string;
  stroke?: string;
  strokeWidth?: number;
  opacity?: number;
}

export interface Shape {
  id: string;
  type: ShapeType;
  x: number;
  y: number;
  width: number;
  height: number;
  rotation?: number;
  points?: Point[];
  style: ShapeStyle;
  layerId: string;
  userId: string;
  createdAt: number;
  updatedAt: number;
}

export type LayerType = 'stroke' | 'shape' | 'text' | 'image';

export interface Layer {
  id: string;
  name: string;
  type: LayerType;
  visible: boolean;
  locked: boolean;
  opacity: number;
  order: number;
  objectIds: string[];
}

export interface User {
  id: string;
  name: string;
  avatar?: string;
  color: string;
  cursor?: Point;
  isOnline: boolean;
  lastActive?: number;
}

export type PermissionRole = 'owner' | 'editor' | 'viewer' | 'commenter';

export interface Permission {
  userId: string;
  role: PermissionRole;
  canEdit: boolean;
  canComment: boolean;
  canExport: boolean;
  canShare: boolean;
  grantedAt: number;
  grantedBy: string;
}

export type CRDTOperationType = 'insert' | 'update' | 'delete' | 'move';

export interface CRDTOperation {
  id: string;
  type: CRDTOperationType;
  userId: string;
  boardId: string;
  objectId: string;
  objectType: LayerType;
  payload: Record<string, unknown>;
  timestamp: number;
  vectorClock: Record<string, number>;
}

export interface Comment {
  id: string;
  threadId: string;
  userId: string;
  content: string;
  position: Point;
  resolved: boolean;
  createdAt: number;
  updatedAt: number;
  replies?: Comment[];
}

export interface CommentThread {
  id: string;
  boardId: string;
  comments: Comment[];
  resolved: boolean;
  position: Point;
  createdAt: number;
}

export interface Version {
  id: string;
  boardId: string;
  name: string;
  description?: string;
  snapshot: string;
  userId: string;
  createdAt: number;
  parentId?: string;
  childrenIds: string[];
}

export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

export type ToolType = 'select' | 'pen' | 'eraser' | 'shape' | 'text' | 'comment' | 'pan';

export interface ToolState {
  activeTool: ToolType;
  strokeStyle: StrokeStyle;
  shapeType: ShapeType;
  shapeStyle: ShapeStyle;
}

export interface BoardState {
  id: string;
  name: string;
  viewport: Viewport;
  layers: Layer[];
  strokes: Stroke[];
  shapes: Shape[];
  selectedIds: string[];
  tool: ToolState;
  showGrid: boolean;
  showExportPanel: boolean;
  showVersionTree: boolean;
  showComments: boolean;
  history: Version[];
}

export interface CollaborationState {
  isConnected: boolean;
  roomId: string;
  operations: CRDTOperation[];
  lastSyncTime?: number;
  pendingOperations: CRDTOperation[];
}

export interface WASMBindings {
  init: () => Promise<void>;
  createStroke: (points: Point[], style: StrokeStyle) => Stroke;
  simplifyStroke: (points: Point[], tolerance: number) => Point[];
  intersects: (a: BoundingBox, b: BoundingBox) => boolean;
  computeBounds: (points: Point[]) => BoundingBox;
  transformPoints: (points: Point[], matrix: number[]) => Point[];
  applyCRDTOperation: (op: CRDTOperation) => void;
  mergeCRDTOperations: (ops: CRDTOperation[]) => CRDTOperation[];
}

export type ExportFormat = 'png' | 'svg' | 'pdf';

export interface ExportOptions {
  format: ExportFormat;
  quality?: number;
  scale?: number;
  includeBackground?: boolean;
  background?: string;
  onlySelected?: boolean;
}
