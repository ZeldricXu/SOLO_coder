import React, { useRef, useEffect, useCallback, useState } from 'react';
import type { Point2D } from '@/types/geometry';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { GridSystem } from '@/engine/drawing/GridSystem';
import { SnapManager, type SnapTarget } from '@/engine/drawing/SnapManager';
import { WallDrawer } from '@/engine/drawing/WallDrawer';
import { useWallDrawing } from '@/hooks/useWallDrawing';
import { distance, angle, midpoint, polygonArea } from '@/utils/geometry';
import { formatLength, formatArea } from '@/utils/math';

export const Canvas2D: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const {
    floorPlan,
    selectedIds,
    hoveredId,
    setHoveredId,
    select,
    currentTool,
    drawingPreview,
  } = useFloorPlanStore();

  const {
    zoom,
    panOffset,
    setZoom,
    setPanOffset,
    showGrid,
    showDimensions,
    showAnnotations,
    setWorldPos,
  } = useUIStore();

  const [gridSystem] = useState(() => new GridSystem());
  const [snapManager] = useState(() => new SnapManager());
  const [wallDrawer] = useState(() => new WallDrawer());
  const [snapTarget, setSnapTarget] = useState<SnapTarget | null>(null);
  const [isPanning, setIsPanning] = useState(false);
  const [panStart, setPanStart] = useState<{ x: number; y: number } | null>(null);

  const { handleMouseDown, handleMouseMove, handleMouseUp } = useWallDrawing(
    canvasRef,
    gridSystem,
    snapManager
  );

  useEffect(() => {
    gridSystem.setConfig({
      size: floorPlan.project.settings.gridSize,
      show: showGrid,
    });
    snapManager.setConfig({
      enabled: floorPlan.project.settings.snapToGrid,
    });
    wallDrawer.setOptions({
      showDimensions,
      wallThickness: floorPlan.project.settings.wallThickness,
    });
  }, [floorPlan.project.settings, showGrid, showDimensions, gridSystem, snapManager, wallDrawer]);

  const render = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const width = canvas.width;
    const height = canvas.height;

    ctx.fillStyle = '#1a1f2e';
    ctx.fillRect(0, 0, width, height);

    if (showGrid) {
      gridSystem.draw(ctx, width, height, panOffset, zoom);
    }

    for (const room of floorPlan.rooms) {
      if (room.boundary.length < 3) continue;

      ctx.save();
      ctx.fillStyle = 'rgba(0, 212, 255, 0.05)';
      ctx.strokeStyle = 'rgba(0, 212, 255, 0.3)';
      ctx.lineWidth = 1;
      ctx.setLineDash([5, 5]);

      ctx.beginPath();
      const first = room.boundary[0];
      ctx.moveTo(first.x * zoom + panOffset.x, first.y * zoom + panOffset.y);
      for (let i = 1; i < room.boundary.length; i++) {
        ctx.lineTo(
          room.boundary[i].x * zoom + panOffset.x,
          room.boundary[i].y * zoom + panOffset.y
        );
      }
      ctx.closePath();
      ctx.fill();
      ctx.stroke();

      const center = midpoint(room.boundary[0], room.boundary[2] || room.boundary[0]);
      ctx.fillStyle = 'rgba(233, 236, 239, 0.6)';
      ctx.font = '12px Inter, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const area = polygonArea(room.boundary);
      ctx.fillText(
        `${room.name} · ${formatArea(area)}`,
        center.x * zoom + panOffset.x,
        center.y * zoom + panOffset.y
      );
      ctx.restore();
    }

    for (const wall of floorPlan.walls) {
      const isSelected = selectedIds.includes(wall.id);
      const isHovered = hoveredId === wall.id;
      wallDrawer.drawWall(ctx, wall, zoom, panOffset, isSelected, isHovered);
    }

    for (const opening of floorPlan.openings) {
      const wall = floorPlan.walls.find((w) => w.id === opening.wallId);
      if (!wall) continue;

      const wallLen = distance(wall.start, wall.end);
      const t = opening.positionX / wallLen;
      const pos = {
        x: wall.start.x + (wall.end.x - wall.start.x) * t,
        y: wall.start.y + (wall.end.y - wall.start.y) * t,
      };

      wallDrawer.drawOpening(
        ctx,
        {
          type: opening.type,
          position: pos,
          width: opening.width,
          wallAngle: angle(wall.start, wall.end),
        },
        zoom,
        panOffset,
        selectedIds.includes(opening.id)
      );
    }

    for (const furniture of floorPlan.furniture) {
      ctx.save();
      const x = furniture.position.x * zoom + panOffset.x;
      const y = furniture.position.z * zoom + panOffset.y;
      const isSelected = selectedIds.includes(furniture.id);

      ctx.translate(x, y);
      ctx.rotate(furniture.rotation);
      ctx.scale(furniture.scale, furniture.scale);

      ctx.fillStyle = isSelected ? '#ff6b35' : 'rgba(108, 117, 125, 0.5)';
      ctx.strokeStyle = isSelected ? '#ff6b35' : '#6c757d';
      ctx.lineWidth = 2;

      ctx.beginPath();
      ctx.rect(-0.4, -0.4, 0.8, 0.8);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = '#fff';
      ctx.font = '10px Inter, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(furniture.name[0], 0, 0);
      ctx.restore();
    }

    if (drawingPreview) {
      wallDrawer.drawPreview(
        ctx,
        drawingPreview.start,
        drawingPreview.end,
        zoom,
        panOffset,
        drawingPreview.type,
        drawingPreview.center
      );
    }

    if (snapTarget) {
      snapManager.drawSnapIndicator(ctx, snapTarget, zoom);
    }

    if (showAnnotations) {
      for (const annotation of floorPlan.annotations) {
        const x = annotation.position.x * zoom + panOffset.x;
        const y = annotation.position.z * zoom + panOffset.y;

        ctx.save();
        ctx.fillStyle = annotation.status === 'resolved' ? '#4ade80' : '#ff6b35';
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 2;

        ctx.beginPath();
        ctx.arc(x, y, 8, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = '#fff';
        ctx.font = 'bold 10px Inter, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('!', x, y);
        ctx.restore();
      }
    }
  }, [
    floorPlan,
    selectedIds,
    hoveredId,
    drawingPreview,
    snapTarget,
    zoom,
    panOffset,
    showGrid,
    showDimensions,
    showAnnotations,
    gridSystem,
    wallDrawer,
    snapManager,
  ]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container) return;

    const resizeCanvas = () => {
      const rect = container.getBoundingClientRect();
      canvas.width = rect.width * window.devicePixelRatio;
      canvas.height = rect.height * window.devicePixelRatio;
      canvas.style.width = `${rect.width}px`;
      canvas.style.height = `${rect.height}px`;
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
      }
    };

    resizeCanvas();
    window.addEventListener('resize', resizeCanvas);

    return () => window.removeEventListener('resize', resizeCanvas);
  }, []);

  useEffect(() => {
    render();
  }, [render]);

  const handleCanvasMouseMove = useCallback(
    (e: React.MouseEvent) => {
      handleMouseMove(e);

      if (!canvasRef.current) return;
      const rect = canvasRef.current.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      const world = gridSystem.screenToWorld({ x, y }, panOffset, zoom);
      setWorldPos({ x: world.x, y: 0, z: world.y });

      if (currentTool === 'select') {
        let found: string | null = null;
        let minDist = Infinity;

        for (const wall of floorPlan.walls) {
          const dist = pointToWallDistance({ x, y }, wall, zoom, panOffset);
          if (dist < 10 && dist < minDist) {
            minDist = dist;
            found = wall.id;
          }
        }

        for (const opening of floorPlan.openings) {
          const wall = floorPlan.walls.find((w) => w.id === opening.wallId);
          if (!wall) continue;
          const wallLen = distance(wall.start, wall.end);
          const t = opening.positionX / wallLen;
          const pos = {
            x: wall.start.x + (wall.end.x - wall.start.x) * t,
            y: wall.start.y + (wall.end.y - wall.start.y) * t,
          };
          const screenPos = {
            x: pos.x * zoom + panOffset.x,
            y: pos.y * zoom + panOffset.y,
          };
          const dist = Math.sqrt((x - screenPos.x) ** 2 + (y - screenPos.y) ** 2);
          if (dist < 15 && dist < minDist) {
            minDist = dist;
            found = opening.id;
          }
        }

        setHoveredId(found);
      }

      const snap = snapManager.findSnapTarget(world, zoom);
      setSnapTarget(snap);

      if (isPanning && panStart) {
        const dx = e.clientX - panStart.x;
        const dy = e.clientY - panStart.y;
        setPanOffset({ x: panOffset.x + dx, y: panOffset.y + dy });
        setPanStart({ x: e.clientX, y: e.clientY });
      }
    },
    [
      handleMouseMove,
      gridSystem,
      panOffset,
      zoom,
      setWorldPos,
      currentTool,
      floorPlan,
      snapManager,
      setHoveredId,
      isPanning,
      panStart,
      setPanOffset,
    ]
  );

  const handleCanvasMouseDown = useCallback(
    (e: React.MouseEvent) => {
      if (e.button === 1 || (e.button === 0 && e.altKey)) {
        setIsPanning(true);
        setPanStart({ x: e.clientX, y: e.clientY });
        return;
      }

      if (currentTool === 'select' && e.button === 0) {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const rect = canvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;

        let found: string | null = null;
        let minDist = Infinity;

        for (const wall of floorPlan.walls) {
          const dist = pointToWallDistance({ x, y }, wall, zoom, panOffset);
          if (dist < 10 && dist < minDist) {
            minDist = dist;
            found = wall.id;
          }
        }

        if (found) {
          select(found);
        } else {
          select(null);
        }
        return;
      }

      handleMouseDown(e);
    },
    [currentTool, floorPlan, zoom, panOffset, select, handleMouseDown]
  );

  const handleCanvasMouseUp = useCallback(
    (e: React.MouseEvent) => {
      if (isPanning) {
        setIsPanning(false);
        setPanStart(null);
        return;
      }
      handleMouseUp(e);
    },
    [isPanning, handleMouseUp]
  );

  const handleWheel = useCallback(
    (e: React.WheelEvent) => {
      e.preventDefault();
      const delta = e.deltaY > 0 ? 0.9 : 1.1;
      setZoom(zoom * delta);
    },
    [zoom, setZoom]
  );

  return (
    <div
      ref={containerRef}
      className="w-full h-full relative bg-canvas-bg overflow-hidden"
      style={{ cursor: isPanning ? 'grabbing' : currentTool.startsWith('wall') ? 'crosshair' : 'default' }}
    >
      <canvas
        ref={canvasRef}
        onMouseDown={handleCanvasMouseDown}
        onMouseMove={handleCanvasMouseMove}
        onMouseUp={handleCanvasMouseUp}
        onMouseLeave={() => {
          setIsPanning(false);
          setPanStart(null);
          setSnapTarget(null);
          setHoveredId(null);
        }}
        onWheel={handleWheel}
        className="absolute inset-0"
      />
    </div>
  );
};

function pointToWallDistance(
  screen: Point2D,
  wall: { start: Point2D; end: Point2D },
  zoom: number,
  offset: Point2D
): number {
  const s1 = {
    x: wall.start.x * zoom + offset.x,
    y: wall.start.y * zoom + offset.y,
  };
  const s2 = {
    x: wall.end.x * zoom + offset.x,
    y: wall.end.y * zoom + offset.y,
  };

  const A = screen.x - s1.x;
  const B = screen.y - s1.y;
  const C = s2.x - s1.x;
  const D = s2.y - s1.y;

  const dot = A * C + B * D;
  const lenSq = C * C + D * D;
  let param = -1;

  if (lenSq !== 0) param = dot / lenSq;

  let xx, yy;
  if (param < 0) {
    xx = s1.x;
    yy = s1.y;
  } else if (param > 1) {
    xx = s2.x;
    yy = s2.y;
  } else {
    xx = s1.x + param * C;
    yy = s1.y + param * D;
  }

  const dx = screen.x - xx;
  const dy = screen.y - yy;
  return Math.sqrt(dx * dx + dy * dy);
}
