import { produce } from 'immer';
import type { PlaneAction, PlaneState } from '@/types/state';
import type { FloorPlan } from '@/types/floorplan';
import { generateId } from '@/utils/geometry';
import { FLOOR_PLAN_VERSION } from '@/types/floorplan';

export const floorPlanToPlaneState = (plan: FloorPlan): PlaneState => ({
  version: FLOOR_PLAN_VERSION,
  project: plan.project,
  walls: [...plan.walls],
  rooms: [...plan.rooms],
  openings: [...plan.openings],
  furniture: [...plan.furniture],
  lights: [...plan.lights],
  annotations: [...plan.annotations],
  lastUpdatedAt: Date.now(),
});

export const planeStateToFloorPlan = (
  state: PlaneState,
  materials: FloorPlan['materials'] = []
): FloorPlan => ({
  version: FLOOR_PLAN_VERSION,
  project: state.project,
  walls: state.walls,
  rooms: state.rooms,
  openings: state.openings,
  furniture: state.furniture,
  lights: state.lights,
  annotations: state.annotations,
  materials,
});

const touchProject = (draft: PlaneState) => {
  draft.project.updatedAt = new Date().toISOString();
  draft.lastUpdatedAt = Date.now();
};

export const planeReducer = (
  state: PlaneState,
  action: PlaneAction
): PlaneState => {
  return produce(state, (draft) => {
    draft.lastActionType = action.type;

    switch (action.type) {
      case 'PLAN_REPLACE':
        draft.version = FLOOR_PLAN_VERSION;
        draft.project = action.payload.project;
        draft.walls = action.payload.walls || [];
        draft.rooms = action.payload.rooms || [];
        draft.openings = action.payload.openings || [];
        draft.furniture = action.payload.furniture || [];
        draft.lights = action.payload.lights || [];
        draft.annotations = action.payload.annotations || [];
        draft.lastUpdatedAt = Date.now();
        return;

      case 'PLAN_RESET':
        draft.walls = [];
        draft.rooms = [];
        draft.openings = [];
        draft.furniture = [];
        draft.lights = [];
        draft.annotations = [];
        draft.lastUpdatedAt = Date.now();
        touchProject(draft);
        return;

      case 'WALL_ADD': {
        const newWall = { ...action.payload, id: generateId() };
        draft.walls.push(newWall);
        touchProject(draft);
        return;
      }

      case 'WALL_UPDATE': {
        const idx = draft.walls.findIndex((w) => w.id === action.payload.id);
        if (idx !== -1) {
          draft.walls[idx] = { ...draft.walls[idx], ...action.payload.updates };
          touchProject(draft);
        }
        return;
      }

      case 'WALL_REMOVE': {
        draft.walls = draft.walls.filter((w) => w.id !== action.payload);
        draft.openings = draft.openings.filter((o) => o.wallId !== action.payload);
        touchProject(draft);
        return;
      }

      case 'ROOM_ADD': {
        const newRoom = { ...action.payload, id: generateId() };
        draft.rooms.push(newRoom);
        touchProject(draft);
        return;
      }

      case 'ROOM_UPDATE': {
        const idx = draft.rooms.findIndex((r) => r.id === action.payload.id);
        if (idx !== -1) {
          draft.rooms[idx] = { ...draft.rooms[idx], ...action.payload.updates };
          touchProject(draft);
        }
        return;
      }

      case 'ROOM_REMOVE': {
        draft.rooms = draft.rooms.filter((r) => r.id !== action.payload);
        touchProject(draft);
        return;
      }

      case 'OPENING_ADD': {
        const newOpening = { ...action.payload, id: generateId() };
        draft.openings.push(newOpening);
        touchProject(draft);
        return;
      }

      case 'OPENING_UPDATE': {
        const idx = draft.openings.findIndex((o) => o.id === action.payload.id);
        if (idx !== -1) {
          draft.openings[idx] = { ...draft.openings[idx], ...action.payload.updates };
          touchProject(draft);
        }
        return;
      }

      case 'OPENING_REMOVE': {
        draft.openings = draft.openings.filter((o) => o.id !== action.payload);
        touchProject(draft);
        return;
      }

      case 'FURNITURE_ADD': {
        const newItem = { ...action.payload, id: generateId() };
        draft.furniture.push(newItem);
        touchProject(draft);
        return;
      }

      case 'FURNITURE_UPDATE': {
        const idx = draft.furniture.findIndex((f) => f.id === action.payload.id);
        if (idx !== -1) {
          draft.furniture[idx] = { ...draft.furniture[idx], ...action.payload.updates };
          touchProject(draft);
        }
        return;
      }

      case 'FURNITURE_REMOVE': {
        draft.furniture = draft.furniture.filter((f) => f.id !== action.payload);
        touchProject(draft);
        return;
      }

      case 'LIGHT_ADD': {
        const newLight = { ...action.payload, id: generateId() };
        draft.lights.push(newLight);
        touchProject(draft);
        return;
      }

      case 'LIGHT_UPDATE': {
        const idx = draft.lights.findIndex((l) => l.id === action.payload.id);
        if (idx !== -1) {
          draft.lights[idx] = { ...draft.lights[idx], ...action.payload.updates };
          touchProject(draft);
        }
        return;
      }

      case 'LIGHT_REMOVE': {
        draft.lights = draft.lights.filter((l) => l.id !== action.payload);
        touchProject(draft);
        return;
      }

      case 'ANNOTATION_ADD': {
        const newAnnotation = {
          ...action.payload,
          id: generateId(),
          status: action.payload.status || 'open',
          createdAt: action.payload.createdAt || Date.now(),
        };
        draft.annotations.push(newAnnotation);
        touchProject(draft);
        return;
      }

      case 'ANNOTATION_UPDATE': {
        const idx = draft.annotations.findIndex((a) => a.id === action.payload.id);
        if (idx !== -1) {
          draft.annotations[idx] = { ...draft.annotations[idx], ...action.payload.updates };
          touchProject(draft);
        }
        return;
      }

      case 'ANNOTATION_REMOVE': {
        draft.annotations = draft.annotations.filter((a) => a.id !== action.payload);
        touchProject(draft);
        return;
      }

      case 'SETTINGS_UPDATE': {
        draft.project.settings = { ...draft.project.settings, ...action.payload };
        touchProject(draft);
        return;
      }

      case 'PROJECT_UPDATE': {
        draft.project = { ...draft.project, ...action.payload };
        touchProject(draft);
        return;
      }

      default:
        return;
    }
  });
};
