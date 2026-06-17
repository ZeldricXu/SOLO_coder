import type { CubeCoords, TerrainType, Direction, Faction, UnitStats } from '@tactics/core';

export type EditorTool =
  | 'select'
  | 'brush'
  | 'eraser'
  | 'placeUnit'
  | 'placeObject'
  | 'generateMap';

export type ThemeCategory = 'scifi' | 'fantasy' | 'modern';

export interface UnitTemplate {
  id: string;
  name: string;
  theme: ThemeCategory;
  icon: string;
  color: string;
  faction: Faction;
  baseStats: Partial<UnitStats>;
}

export interface ObjectTemplate {
  id: string;
  name: string;
  icon: string;
  blocksMovement: boolean;
  blocksVision: boolean;
}

export interface EditorState {
  currentTool: EditorTool;
  selectedTile: CubeCoords | null;
  currentTerrain: TerrainType;
  selectedUnitTemplate: UnitTemplate | null;
  brushSize: number;
  zoom: number;
  panX: number;
  panY: number;
  isDragging: boolean;
  undoStack: EditorSnapshot[];
  redoStack: EditorSnapshot[];
}

export interface EditorSnapshot {
  tilesJson: string;
  timestamp: number;
}

export interface EditorContextValue extends EditorState {
  setCurrentTool: (tool: EditorTool) => void;
  setSelectedTile: (tile: CubeCoords | null) => void;
  setCurrentTerrain: (terrain: TerrainType) => void;
  setSelectedUnitTemplate: (template: UnitTemplate | null) => void;
  setBrushSize: (size: number) => void;
  setZoom: (zoom: number) => void;
  setPan: (x: number, y: number) => void;
  setIsDragging: (dragging: boolean) => void;
  undo: () => void;
  redo: () => void;
  saveSnapshot: () => void;
  canUndo: boolean;
  canRedo: boolean;
}
