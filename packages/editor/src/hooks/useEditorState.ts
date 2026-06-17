import { useState, useCallback, useMemo } from 'react';
import type { HexGrid } from '@tactics/core';
import type {
  EditorTool,
  TerrainType,
  CubeCoords,
  EditorState,
  EditorSnapshot,
  UnitTemplate,
} from '../types/editor';

const MAX_HISTORY = 50;

export function useEditorState(grid: HexGrid) {
  const [state, setState] = useState<EditorState>({
    currentTool: 'select',
    selectedTile: null,
    currentTerrain: 'plain',
    selectedUnitTemplate: null,
    brushSize: 1,
    zoom: 1,
    panX: 400,
    panY: 300,
    isDragging: false,
    undoStack: [],
    redoStack: [],
  });

  const createSnapshot = useCallback((): EditorSnapshot => {
    return {
      tilesJson: JSON.stringify(grid.toJSON()),
      timestamp: Date.now(),
    };
  }, [grid]);

  const restoreSnapshot = useCallback((snapshot: EditorSnapshot) => {
    const data = JSON.parse(snapshot.tilesJson);
    const restoredGrid = HexGrid.fromJSON(data);
    const tiles = restoredGrid.getAllTiles();
    for (const tile of tiles) {
      const originalTile = grid.getTile(tile.coords);
      if (originalTile) {
        originalTile.terrain = tile.terrain;
        originalTile.height = tile.height;
        originalTile.units = [...tile.units];
        originalTile.objects = [...tile.objects];
      }
    }
  }, [grid]);

  const saveSnapshot = useCallback(() => {
    setState(prev => {
      const newStack = [...prev.undoStack, createSnapshot()];
      if (newStack.length > MAX_HISTORY) {
        newStack.shift();
      }
      return {
        ...prev,
        undoStack: newStack,
        redoStack: [],
      };
    });
  }, [createSnapshot]);

  const undo = useCallback(() => {
    setState(prev => {
      if (prev.undoStack.length === 0) return prev;
      const newUndo = [...prev.undoStack];
      const snapshot = newUndo.pop()!;
      const currentSnapshot = createSnapshot();
      restoreSnapshot(snapshot);
      return {
        ...prev,
        undoStack: newUndo,
        redoStack: [...prev.redoStack, currentSnapshot].slice(-MAX_HISTORY),
      };
    });
  }, [createSnapshot, restoreSnapshot]);

  const redo = useCallback(() => {
    setState(prev => {
      if (prev.redoStack.length === 0) return prev;
      const newRedo = [...prev.redoStack];
      const snapshot = newRedo.pop()!;
      const currentSnapshot = createSnapshot();
      restoreSnapshot(snapshot);
      return {
        ...prev,
        undoStack: [...prev.undoStack, currentSnapshot].slice(-MAX_HISTORY),
        redoStack: newRedo,
      };
    });
  }, [createSnapshot, restoreSnapshot]);

  const setCurrentTool = useCallback((tool: EditorTool) => {
    setState(prev => ({ ...prev, currentTool: tool }));
  }, []);

  const setSelectedTile = useCallback((tile: CubeCoords | null) => {
    setState(prev => ({ ...prev, selectedTile: tile }));
  }, []);

  const setCurrentTerrain = useCallback((terrain: TerrainType) => {
    setState(prev => ({ ...prev, currentTerrain: terrain }));
  }, []);

  const setSelectedUnitTemplate = useCallback((template: UnitTemplate | null) => {
    setState(prev => ({ ...prev, selectedUnitTemplate: template }));
  }, []);

  const setBrushSize = useCallback((size: number) => {
    setState(prev => ({ ...prev, brushSize: Math.max(1, Math.min(10, size)) }));
  }, []);

  const setZoom = useCallback((zoom: number) => {
    setState(prev => ({ ...prev, zoom: Math.max(0.2, Math.min(5, zoom)) }));
  }, []);

  const setPan = useCallback((x: number, y: number) => {
    setState(prev => ({ ...prev, panX: x, panY: y }));
  }, []);

  const setIsDragging = useCallback((dragging: boolean) => {
    setState(prev => ({ ...prev, isDragging: dragging }));
  }, []);

  const canUndo = useMemo(() => state.undoStack.length > 0, [state.undoStack]);
  const canRedo = useMemo(() => state.redoStack.length > 0, [state.redoStack]);

  return {
    ...state,
    setCurrentTool,
    setSelectedTile,
    setCurrentTerrain,
    setSelectedUnitTemplate,
    setBrushSize,
    setZoom,
    setPan,
    setIsDragging,
    undo,
    redo,
    saveSnapshot,
    canUndo,
    canRedo,
  };
}
