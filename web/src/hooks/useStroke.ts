import { useState, useCallback, useRef } from 'react';
import { useBoardStore } from '../stores/useBoardStore';
import { useUserStore } from '../stores/useUserStore';
import { wasm } from '../wasm';
import type { Point, Stroke, Shape, StrokeStyle, ShapeStyle, ShapeType } from '../types';

interface DrawingState {
  isDrawing: boolean;
  currentPoints: Point[];
  shapeStart: Point | null;
  shapeEnd: Point | null;
}

interface UseStrokeResult {
  drawingState: DrawingState;
  startDrawing: (point: Point) => void;
  continueDrawing: (point: Point) => void;
  endDrawing: () => void;
  cancelDrawing: () => void;
}

export function useStroke(): UseStrokeResult {
  const [drawingState, setDrawingState] = useState<DrawingState>({
    isDrawing: false,
    currentPoints: [],
    shapeStart: null,
    shapeEnd: null,
  });

  const {
    tool,
    addStroke,
    addShape,
  } = useBoardStore();

  const currentUser = useUserStore((state) => state.currentUser);
  const strokeIdRef = useRef<string | null>(null);

  const createStroke = useCallback(
    (points: Point[], style: StrokeStyle): Stroke => {
      const simplified = wasm.simplifyStroke(points, 0.5);
      return {
        id: crypto.randomUUID(),
        points: simplified,
        style,
        layerId: 'default',
        userId: currentUser?.id ?? 'anonymous',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        bounds: wasm.computeBounds(simplified),
      };
    },
    [currentUser]
  );

  const createShape = useCallback(
    (start: Point, end: Point, type: ShapeType, style: ShapeStyle): Shape => {
      const x = Math.min(start.x, end.x);
      const y = Math.min(start.y, end.y);
      const width = Math.abs(end.x - start.x);
      const height = Math.abs(end.y - start.y);

      return {
        id: crypto.randomUUID(),
        type,
        x,
        y,
        width,
        height,
        style,
        layerId: 'default',
        userId: currentUser?.id ?? 'anonymous',
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
    },
    [currentUser]
  );

  const startDrawing = useCallback(
    (point: Point) => {
      if (tool.activeTool === 'pen') {
        strokeIdRef.current = crypto.randomUUID();
        setDrawingState({
          isDrawing: true,
          currentPoints: [point],
          shapeStart: null,
          shapeEnd: null,
        });
      } else if (tool.activeTool === 'shape') {
        setDrawingState({
          isDrawing: true,
          currentPoints: [],
          shapeStart: point,
          shapeEnd: point,
        });
      }
    },
    [tool.activeTool]
  );

  const continueDrawing = useCallback(
    (point: Point) => {
      if (!drawingState.isDrawing) return;

      if (tool.activeTool === 'pen') {
        setDrawingState((prev) => ({
          ...prev,
          currentPoints: [...prev.currentPoints, point],
        }));
      } else if (tool.activeTool === 'shape' && drawingState.shapeStart) {
        setDrawingState((prev) => ({
          ...prev,
          shapeEnd: point,
        }));
      }
    },
    [drawingState.isDrawing, drawingState.shapeStart, tool.activeTool]
  );

  const endDrawing = useCallback(() => {
    if (!drawingState.isDrawing) return;

    if (tool.activeTool === 'pen' && drawingState.currentPoints.length >= 2) {
      const stroke = createStroke(drawingState.currentPoints, tool.strokeStyle);
      addStroke(stroke);
    } else if (tool.activeTool === 'shape' && drawingState.shapeStart && drawingState.shapeEnd) {
      const dist = Math.sqrt(
        Math.pow(drawingState.shapeEnd.x - drawingState.shapeStart.x, 2) +
        Math.pow(drawingState.shapeEnd.y - drawingState.shapeStart.y, 2)
      );
      if (dist > 5) {
        const shape = createShape(
          drawingState.shapeStart,
          drawingState.shapeEnd,
          tool.shapeType,
          tool.shapeStyle
        );
        addShape(shape);
      }
    }

    strokeIdRef.current = null;
    setDrawingState({
      isDrawing: false,
      currentPoints: [],
      shapeStart: null,
      shapeEnd: null,
    });
  }, [drawingState, tool, createStroke, createShape, addStroke, addShape]);

  const cancelDrawing = useCallback(() => {
    strokeIdRef.current = null;
    setDrawingState({
      isDrawing: false,
      currentPoints: [],
      shapeStart: null,
      shapeEnd: null,
    });
  }, []);

  return {
    drawingState,
    startDrawing,
    continueDrawing,
    endDrawing,
    cancelDrawing,
  };
}
