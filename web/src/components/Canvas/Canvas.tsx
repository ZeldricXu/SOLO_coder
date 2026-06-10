import React, { useCallback, useEffect, useRef } from 'react';
import { useCanvas } from '../../hooks/useCanvas';
import { useStroke } from '../../hooks/useStroke';
import { useBoardStore } from '../../stores/useBoardStore';
import { useUserStore } from '../../stores/useUserStore';
import { useCollaboration } from '../../hooks/useCollaboration';

const Canvas: React.FC = () => {
  const {
    canvasRef,
    screenToWorld,
    render,
    drawTemporaryStroke,
    drawTemporaryShape,
  } = useCanvas();

  const { drawingState, startDrawing, continueDrawing, endDrawing, cancelDrawing } = useStroke();
  const { viewport, setViewport, tool, clearSelection } = useBoardStore();
  const updateUserCursor = useUserStore((state) => state.updateUserCursor);
  const currentUser = useUserStore((state) => state.currentUser);
  const { sendCursor } = useCollaboration();

  const isPanning = useRef(false);
  const lastPanPos = useRef<{ x: number; y: number } | null>(null);

  const handleMouseDown = useCallback(
    (e: React.MouseEvent<HTMLCanvasElement>) => {
      if (e.button === 1 || (e.button === 0 && tool.activeTool === 'pan')) {
        isPanning.current = true;
        lastPanPos.current = { x: e.clientX, y: e.clientY };
        return;
      }

      if (e.button !== 0) return;

      if (tool.activeTool === 'select') {
        clearSelection();
        return;
      }

      const worldPoint = screenToWorld(e.clientX, e.clientY);
      startDrawing(worldPoint);
    },
    [tool.activeTool, screenToWorld, startDrawing, clearSelection]
  );

  const handleMouseMove = useCallback(
    (e: React.MouseEvent<HTMLCanvasElement>) => {
      const worldPoint = screenToWorld(e.clientX, e.clientY);

      if (currentUser) {
        updateUserCursor(currentUser.id, worldPoint.x, worldPoint.y);
        sendCursor(worldPoint);
      }

      if (isPanning.current && lastPanPos.current) {
        const dx = e.clientX - lastPanPos.current.x;
        const dy = e.clientY - lastPanPos.current.y;
        setViewport({
          x: viewport.x + dx,
          y: viewport.y + dy,
        });
        lastPanPos.current = { x: e.clientX, y: e.clientY };
        return;
      }

      if (!drawingState.isDrawing) return;

      continueDrawing(worldPoint);

      if (tool.activeTool === 'pen' && drawingState.currentPoints.length > 0) {
        render();
        drawTemporaryStroke(drawingState.currentPoints, tool.strokeStyle);
      } else if (
        tool.activeTool === 'shape' &&
        drawingState.shapeStart &&
        drawingState.shapeEnd
      ) {
        render();
        drawTemporaryShape(
          drawingState.shapeStart,
          drawingState.shapeEnd,
          tool.shapeType,
          tool.shapeStyle
        );
      }
    },
    [
      screenToWorld,
      currentUser,
      updateUserCursor,
      sendCursor,
      viewport,
      setViewport,
      drawingState,
      continueDrawing,
      tool,
      render,
      drawTemporaryStroke,
      drawTemporaryShape,
    ]
  );

  const handleMouseUp = useCallback(() => {
    if (isPanning.current) {
      isPanning.current = false;
      lastPanPos.current = null;
      return;
    }
    endDrawing();
    render();
  }, [endDrawing, render]);

  const handleMouseLeave = useCallback(() => {
    if (isPanning.current) {
      isPanning.current = false;
      lastPanPos.current = null;
    }
    if (drawingState.isDrawing) {
      cancelDrawing();
      render();
    }
  }, [drawingState.isDrawing, cancelDrawing, render]);

  const handleWheel = useCallback(
    (e: React.WheelEvent<HTMLCanvasElement>) => {
      e.preventDefault();
      const delta = -e.deltaY * 0.001;
      const newZoom = Math.max(0.1, Math.min(5, viewport.zoom * (1 + delta)));

      const rect = canvasRef.current?.getBoundingClientRect();
      if (!rect) return;

      const mouseX = e.clientX - rect.left;
      const mouseY = e.clientY - rect.top;

      const worldX = (mouseX - viewport.x) / viewport.zoom;
      const worldY = (mouseY - viewport.y) / viewport.zoom;

      setViewport({
        zoom: newZoom,
        x: mouseX - worldX * newZoom,
        y: mouseY - worldY * newZoom,
      });
    },
    [viewport, setViewport, canvasRef]
  );

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        cancelDrawing();
        clearSelection();
        render();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [cancelDrawing, clearSelection, render]);

  const getCursor = () => {
    switch (tool.activeTool) {
      case 'pen':
        return 'crosshair';
      case 'eraser':
        return 'cell';
      case 'shape':
        return 'crosshair';
      case 'pan':
        return 'grab';
      case 'select':
        return 'default';
      case 'text':
        return 'text';
      case 'comment':
        return 'pointer';
      default:
        return 'default';
    }
  };

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        width: '100%',
        height: '100%',
        cursor: getCursor(),
        touchAction: 'none',
      }}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseLeave}
      onWheel={handleWheel}
    />
  );
};

export default Canvas;
