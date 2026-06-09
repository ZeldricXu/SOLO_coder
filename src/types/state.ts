import type {
  Wall,
  Room,
  Opening,
  FurnitureItem,
  LightSource,
  Annotation,
  ProjectSettings,
  Project,
  FloorPlan,
} from './floorplan';

export type PlaneAction =
  | { type: 'WALL_ADD'; payload: Omit<Wall, 'id'> }
  | { type: 'WALL_UPDATE'; payload: { id: string; updates: Partial<Wall> } }
  | { type: 'WALL_REMOVE'; payload: string }
  | { type: 'ROOM_ADD'; payload: Omit<Room, 'id'> }
  | { type: 'ROOM_UPDATE'; payload: { id: string; updates: Partial<Room> } }
  | { type: 'ROOM_REMOVE'; payload: string }
  | { type: 'OPENING_ADD'; payload: Omit<Opening, 'id'> }
  | { type: 'OPENING_UPDATE'; payload: { id: string; updates: Partial<Opening> } }
  | { type: 'OPENING_REMOVE'; payload: string }
  | { type: 'FURNITURE_ADD'; payload: Omit<FurnitureItem, 'id'> }
  | { type: 'FURNITURE_UPDATE'; payload: { id: string; updates: Partial<FurnitureItem> } }
  | { type: 'FURNITURE_REMOVE'; payload: string }
  | { type: 'LIGHT_ADD'; payload: Omit<LightSource, 'id'> }
  | { type: 'LIGHT_UPDATE'; payload: { id: string; updates: Partial<LightSource> } }
  | { type: 'LIGHT_REMOVE'; payload: string }
  | { type: 'ANNOTATION_ADD'; payload: Omit<Annotation, 'id'> }
  | { type: 'ANNOTATION_UPDATE'; payload: { id: string; updates: Partial<Annotation> } }
  | { type: 'ANNOTATION_REMOVE'; payload: string }
  | { type: 'SETTINGS_UPDATE'; payload: Partial<ProjectSettings> }
  | { type: 'PROJECT_UPDATE'; payload: Partial<Project> }
  | { type: 'PLAN_REPLACE'; payload: FloorPlan }
  | { type: 'PLAN_RESET' };

export interface PlaneState {
  version: string;
  project: Project;
  walls: Wall[];
  rooms: Room[];
  openings: Opening[];
  furniture: FurnitureItem[];
  lights: LightSource[];
  annotations: Annotation[];
  lastUpdatedAt: number;
  lastActionType?: PlaneAction['type'];
}
