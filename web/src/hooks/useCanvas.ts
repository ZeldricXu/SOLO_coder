import { useRef, useEffect, useCallback } from 'react';
import { useBoardStore } from '../stores/useBoardStore';
import type { Point, Stroke, Shape, StrokeStyle, ShapeStyle, ShapeType } from '../types';

interface UseCanvasResult {
  canvasRef: React.RefObject<HTMLCanvasElement>;
  ctx: CanvasRenderingContext2D | null;
  screenToWorld: (screenX: number, screenY: number) => Point;
  worldToScreen: (worldX: number, worldY: number) => Point;
  clear: () => void;
  render: () => void;
  drawStroke: (stroke: Stroke) => void;
  drawShape: (shape: Shape) => void;
  drawGrid: () => void;
  drawTemporaryStroke: (points: Point[], style: StrokeStyle) => void;
  drawTemporaryShape: (start: Point, end: Point, type: ShapeType, style: ShapeStyle) => void;
}

export function useCanvas(): UseCanvasResult {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const ctxRef = useRef<CanvasRenderingContext2D | null>(null);
  const { viewport, strokes, shapes, showGrid, selectedIds } = useBoardStore();

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    ctxRef.current = ctx;

    const resizeCanvas = () => {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      canvas.width = rect.width * dpr;
      canvas.height = rect.height * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      render();
    };

    resizeCanvas();
    window.addEventListener('resize', resizeCanvas);

    return () => {
      window.removeEventListener('resize', resizeCanvas);
    };
  }, []);

  const screenToWorld = useCallback((screenX: number, screenY: number): Point => {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };

    const rect = canvas.getBoundingClientRect();
    const x = (screenX - rect.left - viewport.x) / viewport.zoom;
    const y = (screenY - rect.top - viewport.y) / viewport.zoom;

    return { x, y };
  }, [viewport]);

  const worldToScreen = useCallback((worldX: number, worldY: number): Point => {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };

    const rect = canvas.getBoundingClientRect();
    const x = worldX * viewport.zoom + viewport.x + rect.left;
    const y = worldY * viewport.zoom + viewport.y + rect.top;

    return { x, y };
  }, [viewport]);

  const clear = useCallback(() => {
    const canvas = canvasRef.current;
    const ctx = ctxRef.current;
    if (!canvas || !ctx) return;

    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.restore();
  }, []);

  const drawGrid = useCallback(() => {
    const canvas = canvasRef.current;
    const ctx = ctxRef.current;
    if (!canvas || !ctx || !showGrid) return;

    const rect = canvas.getBoundingClientRect();
    const gridSize = 50 * viewport.zoom;
    const offsetX = viewport.x % gridSize;
    const offsetY = viewport.y % gridSize;

    ctx.save();
    ctx.strokeStyle = '#e5e7eb';
    ctx.lineWidth = 1;

    for (let x = offsetX; x < rect.width; x += gridSize) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, rect.height);
      ctx.stroke();
    }

    for (let y = offsetY; y < rect.height; y += gridSize) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(rect.width, y);
      ctx.stroke();
    }

    ctx.restore();
  }, [viewport, showGrid]);

  const applyTransform = useCallback(() => {
    const ctx = ctxRef.current;
    if (!ctx) return;
    ctx.translate(viewport.x, viewport.y);
    ctx.scale(viewport.zoom, viewport.zoom);
  }, [viewport]);

  const drawStroke = useCallback((stroke: Stroke) => {
    const ctx = ctxRef.current;
    if (!ctx || stroke.points.length < 2) return;

    ctx.save();
    applyTransform();

    ctx.strokeStyle = stroke.style.color;
    ctx.lineWidth = stroke.style.width;
    ctx.globalAlpha = stroke.style.opacity;
    ctx.lineCap = stroke.style.cap || 'round';
    ctx.lineJoin = stroke.style.join || 'round';

    if (stroke.style.dashPattern) {
      ctx.setLineDash(stroke.style.dashPattern);
    }

    ctx.beginPath();
    ctx.moveTo(stroke.points[0].x, stroke.points[0].y);

    for (let i = 1; i < stroke.points.length; i++) {
      ctx.lineTo(stroke.points[i].x, stroke.points[i].y);
    }

    ctx.stroke();

    if (selectedIds.includes(stroke.id)) {
      ctx.globalAlpha = 1;
      ctx.strokeStyle = '#3b82f6';
      ctx.lineWidth = 2;
      ctx.setLineDash([5, 5]);
      if (stroke.bounds) {
        ctx.strokeRect(
          stroke.bounds.minX - 5,
          stroke.bounds.minY - 5,
          stroke.bounds.maxX - stroke.bounds.minX + 10,
          stroke.bounds.maxY - stroke.bounds.minY + 10
        );
      }
    }

    ctx.restore();
  }, [applyTransform, selectedIds]);

  const drawShape = useCallback((shape: Shape) => {
    const ctx = ctxRef.current;
    if (!ctx) return;

    ctx.save();
    applyTransform();
    ctx.globalAlpha = shape.style.opacity ?? 1;

    if (shape.rotation) {
      const centerX = shape.x + shape.width / 2;
      const centerY = shape.y + shape.height / 2;
      ctx.translate(centerX, centerY);
      ctx.rotate((shape.rotation * Math.PI) / 180);
      ctx.translate(-centerX, -centerY);
    }

    if (shape.style.fill && shape.style.fill !== 'transparent') {
      ctx.fillStyle = shape.style.fill;
    }
    if (shape.style.stroke) {
      ctx.strokeStyle = shape.style.stroke;
      ctx.lineWidth = shape.style.strokeWidth ?? 2;
    }

    ctx.beginPath();

    switch (shape.type) {
      case 'rectangle':
        ctx.rect(shape.x, shape.y, shape.width, shape.height);
        break;
      case 'ellipse':
        ctx.ellipse(
          shape.x + shape.width / 2,
          shape.y + shape.height / 2,
          Math.abs(shape.width) / 2,
          Math.abs(shape.height) / 2,
          0,
          0,
          Math.PI * 2
        );
        break;
      case 'line':
      case 'arrow':
        ctx.moveTo(shape.x, shape.y);
        ctx.lineTo(shape.x + shape.width, shape.y + shape.height);
        break;
      case 'triangle':
        ctx.moveTo(shape.x + shape.width / 2, shape.y);
        ctx.lineTo(shape.x + shape.width, shape.y + shape.height);
        ctx.lineTo(shape.x, shape.y + shape.height);
        ctx.closePath();
        break;
      case 'polygon':
        if (shape.points && shape.points.length > 0) {
          ctx.moveTo(shape.points[0].x, shape.points[0].y);
          for (let i = 1; i < shape.points.length; i++) {
            ctx.lineTo(shape.points[i].x, shape.points[i].y);
          }
          ctx.closePath();
        }
        break;
    }

    if (shape.style.fill && shape.style.fill !== 'transparent') {
      ctx.fill();
    }
    if (shape.style.stroke) {
      ctx.stroke();
    }

    if (selectedIds.includes(shape.id)) {
      ctx.globalAlpha = 1;
      ctx.strokeStyle = '#3b82f6';
      ctx.lineWidth = 2;
      ctx.setLineDash([5, 5]);
      ctx.strokeRect(shape.x - 5, shape.y - 5, shape.width + 10, shape.height + 10);
    }

    ctx.restore();
  }, [applyTransform, selectedIds]);

  const drawTemporaryStroke = useCallback((points: Point[], style: StrokeStyle) => {
    const ctx = ctxRef.current;
    if (!ctx || points.length < 2) return;

    ctx.save();
    applyTransform();

    ctx.strokeStyle = style.color;
    ctx.lineWidth = style.width;
    ctx.globalAlpha = style.opacity;
    ctx.lineCap = style.cap || 'round';
    ctx.lineJoin = style.join || 'round';

    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);

    for (let i = 1; i < points.length; i++) {
      ctx.lineTo(points[i].x, points[i].y);
    }

    ctx.stroke();
    ctx.restore();
  }, [applyTransform]);

  const drawTemporaryShape = useCallback(
    (start: Point, end: Point, type: ShapeType, style: ShapeStyle) => {
      const ctx = ctxRef.current;
      if (!ctx) return;

      const x = Math.min(start.x, end.x);
      const y = Math.min(start.y, end.y);
      const width = Math.abs(end.x - start.x);
      const height = Math.abs(end.y - start.y);

      ctx.save();
      applyTransform();
      ctx.globalAlpha = (style.opacity ?? 1) * 0.5;

      if (style.fill && style.fill !== 'transparent') {
        ctx.fillStyle = style.fill;
      }
      if (style.stroke) {
        ctx.strokeStyle = style.stroke;
        ctx.lineWidth = style.strokeWidth ?? 2;
      }

      ctx.beginPath();

      switch (type) {
        case 'rectangle':
          ctx.rect(x, y, width, height);
          break;
        case 'ellipse':
          ctx.ellipse(x + width / 2, y + height / 2, width / 2, height / 2, 0, 0, Math.PI * 2);
          break;
        case 'line':
        case 'arrow':
          ctx.moveTo(start.x, start.y);
          ctx.lineTo(end.x, end.y);
          break;
        case 'triangle':
          ctx.moveTo(x + width / 2, y);
          ctx.lineTo(x + width, y + height);
          ctx.lineTo(x, y + height);
          ctx.closePath();
          break;
      }

      if (style.fill && style.fill !== 'transparent') {
        ctx.fill();
      }
      if (style.stroke) {
        ctx.stroke();
      }

      ctx.restore();
    },
    [applyTransform]
  );

  const render = useCallback(() => {
    clear();
    drawGrid();
    strokes.forEach((stroke) => drawStroke(stroke));
    shapes.forEach((shape) => drawShape(shape));
  }, [clear, drawGrid, strokes, shapes, drawStroke, drawShape]);

  useEffect(() => {
    render();
  }, [render]);

  return {
    canvasRef,
    ctx: ctxRef.current,
    screenToWorld,
    worldToScreen,
    clear,
    render,
    drawStroke,
    drawShape,
    drawGrid,
    drawTemporaryStroke,
    drawTemporaryShape,
  };
}
