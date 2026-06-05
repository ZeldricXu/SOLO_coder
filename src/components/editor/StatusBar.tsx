import React from 'react';
import { Ruler, Grid3X3, MousePointer2, Users, Save, Cloud } from 'lucide-react';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { formatLength, formatArea } from '@/utils/math';
import { polygonArea } from '@/utils/geometry';

export const StatusBar: React.FC = () => {
  const { floorPlan, currentTool } = useFloorPlanStore();
  const { mouseWorldPos, showGrid, showHelpers, measuring, measurementPoints } = useUIStore();

  const totalFloorArea = floorPlan.rooms.reduce((sum, room) => {
    return sum + polygonArea(room.boundary);
  }, 0);

  const totalWallLength = floorPlan.walls.reduce((sum, wall) => {
    const dx = wall.end.x - wall.start.x;
    const dy = wall.end.y - wall.start.y;
    return sum + Math.sqrt(dx * dx + dy * dy);
  }, 0);

  const getToolLabel = () => {
    switch (currentTool) {
      case 'select': return '选择工具';
      case 'wall-straight': return '直线墙';
      case 'wall-arc': return '弧形墙';
      case 'door': return '插入门';
      case 'window': return '插入窗';
      case 'measure': return '测量工具';
      case 'annotation': return '批注工具';
      case 'furniture': return '家具工具';
      default: return currentTool;
    }
  };

  return (
    <div className="h-8 bg-neutral-900 border-t border-neutral-800 flex items-center justify-between px-4 text-xs">
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-2 text-neutral-400">
          <MousePointer2 size={12} />
          <span className="font-mono">
            X: {mouseWorldPos.x.toFixed(2)}m, Y: {mouseWorldPos.y.toFixed(2)}m
          </span>
        </div>

        <div className="flex items-center gap-2 text-neutral-400">
          <Grid3X3 size={12} />
          <span>网格: {showGrid ? '开' : '关'}</span>
        </div>

        <div className="flex items-center gap-2 text-neutral-400">
          <span>辅助: {showHelpers ? '开' : '关'}</span>
        </div>
      </div>

      <div className="flex items-center gap-4">
        {measuring && measurementPoints.length > 0 && (
          <div className="flex items-center gap-2 px-3 py-1 bg-accent-primary/20 rounded text-accent-primary">
            <Ruler size={12} />
            <span>
              {measurementPoints.length === 1
                ? '点击第二个点完成测量'
                : `距离: ${formatLength(
                    Math.hypot(
                      measurementPoints[1].x - measurementPoints[0].x,
                      measurementPoints[1].y - measurementPoints[0].y
                    )
                  )}`}
            </span>
          </div>
        )}

        <div className="w-px h-4 bg-neutral-700" />

        <div className="flex items-center gap-4 text-neutral-400">
          <div>
            <span className="text-neutral-500">总面积: </span>
            <span className="text-white font-mono">{formatArea(totalFloorArea)}</span>
          </div>
          <div>
            <span className="text-neutral-500">墙总长: </span>
            <span className="text-white font-mono">{formatLength(totalWallLength)}</span>
          </div>
        </div>

        <div className="w-px h-4 bg-neutral-700" />

        <div className="flex items-center gap-2 px-2 py-0.5 bg-neutral-800 rounded text-accent-secondary">
          <span>{getToolLabel()}</span>
        </div>

        <div className="flex items-center gap-2 text-neutral-400">
          <Save size={12} />
          <span>已保存</span>
        </div>

        <div className="flex items-center gap-2 text-neutral-400">
          <Users size={12} />
          <span>1 人在线</span>
        </div>

        <div className="flex items-center gap-1 text-green-400">
          <Cloud size={12} />
          <span>已同步</span>
        </div>
      </div>
    </div>
  );
};
