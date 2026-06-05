import { useCallback, useRef, useEffect } from 'react';
import type { Point2D } from '@/types/geometry';
import type { WallType } from '@/types/floorplan';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { snapToGrid, snapAngle, distance, pointsEqual } from '@/utils/geometry';
import { GridSystem } from '@/engine/drawing/GridSystem';
import { SnapManager } from '@/engine/drawing/SnapManager';

export const useWallDrawing = (
  canvasRef: React.RefObject<HTMLCanvasElement>,
  gridSystem: GridSystem,
  snapManager: SnapManager
) => {
  const {
    addWall,
    currentTool,
    isDrawing,
    setIsDrawing,
    drawingPreview,
    setDrawingPreview,
    floorPlan,
  } = useFloorPlanStore();

  const { zoom, panOffset, showGrid, showDimensions } = useUIStore();

  const startPointRef = useRef<Point2D | null>(null);
  const wallTypeRef = useRef<WallType>('straight');

  useEffect(() => {
    snapManager.setWalls(floorPlan.walls);
  }, [floorPlan.walls, snapManager]);

  const getWorldPoint = useCallback(
    (screenX: number, screenY: number): Point2D => {
      if (!canvasRef.current) return { x: 0, y: 0 };

      const rect = canvasRef.current.getBoundingClientRect();
      const x = screenX - rect.left;
      const y = screenY - rect.top;

      const world = gridSystem.screenToWorld(
        { x, y },
        panOffset,
        zoom
      );

      if (floorPlan.project.settings.snapToGrid) {
        return snapToGrid(world, floorPlan.project.settings.gridSize);
      }

      return world;
    },
    [canvasRef, gridSystem, panOffset, zoom, floorPlan.project.settings]
  );

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      if (currentTool !== 'wall-straight' && currentTool !== 'wall-arc') return;

      const worldPoint = getWorldPoint(e.clientX, e.clientY);
      const snapTarget = snapManager.findSnapTarget(worldPoint, zoom);

      const finalPoint = snapTarget ? snapTarget.point : worldPoint;

      wallTypeRef.current = currentTool === 'wall-arc' ? 'arc' : 'straight';
      startPointRef.current = finalPoint;
      setIsDrawing(true);
    },
    [currentTool, getWorldPoint, snapManager, zoom, setIsDrawing]
  );

  const handleMouseMove = useCallback(
    (e: React.MouseEvent) => {
      if (!isDrawing || !startPointRef.current) return;

      const worldPoint = getWorldPoint(e.clientX, e.clientY);
      const snapTarget = snapManager.findSnapTarget(worldPoint, zoom);

      let finalPoint = snapTarget ? snapTarget.point : worldPoint;

      if (floorPlan.project.settings.angleConstraint > 0) {
        finalPoint = snapAngle(
          startPointRef.current,
          finalPoint,
          floorPlan.project.settings.angleConstraint
        );
      }

      const previewWall = {
        id: 'preview',
        type: wallTypeRef.current,
        start: startPointRef.current,
        end: finalPoint,
        thickness: floorPlan.project.settings.wallThickness,
        height: floorPlan.project.settings.wallHeight,
        materialId: 'mat-wall-white',
      };

      setDrawingPreview(previewWall);
    },
    [
      isDrawing,
      getWorldPoint,
      snapManager,
      zoom,
      floorPlan.project.settings,
      setDrawingPreview,
    ]
  );

  const handleMouseUp = useCallback(
    (e: React.MouseEvent) => {
      if (!isDrawing || !startPointRef.current) return;

      const worldPoint = getWorldPoint(e.clientX, e.clientY);
      const snapTarget = snapManager.findSnapTarget(worldPoint, zoom);

      let finalPoint = snapTarget ? snapTarget.point : worldPoint;

      if (floorPlan.project.settings.angleConstraint > 0) {
        finalPoint = snapAngle(
          startPointRef.current,
          finalPoint,
          floorPlan.project.settings.angleConstraint
        );
      }

      const wallLength = distance(startPointRef.current, finalPoint);

      if (wallLength > 0.1 && !pointsEqual(startPointRef.current, finalPoint)) {
        addWall({
          type: wallTypeRef.current,
          start: startPointRef.current,
          end: finalPoint,
          thickness: floorPlan.project.settings.wallThickness,
          height: floorPlan.project.settings.wallHeight,
          materialId: 'mat-wall-white',
        });

        const lastWall = floorPlan.walls[floorPlan.walls.length - 1];
        if (lastWall && pointsEqual(finalPoint, lastWall.start)) {
          // Auto connect
        }
      }

      startPointRef.current = null;
      setIsDrawing(false);
      setDrawingPreview(null);
    },
    [
      isDrawing,
      getWorldPoint,
      snapManager,
      zoom,
      floorPlan,
      addWall,
      setIsDrawing,
      setDrawingPreview,
    ]
  );

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isDrawing) {
        startPointRef.current = null;
        setIsDrawing(false);
        setDrawingPreview(null);
      }
    },
    [isDrawing, setIsDrawing, setDrawingPreview]
  );

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  return {
    handleMouseDown,
    handleMouseMove,
    handleMouseUp,
    startPoint: startPointRef.current,
  };
};
