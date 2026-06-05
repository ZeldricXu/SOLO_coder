import React from 'react';
import {
  MousePointer2,
  Minus,
  Spline,
  DoorOpen,
  Square,
  Ruler,
  MessageSquarePlus,
  Sofa,
  Plus,
} from 'lucide-react';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import type { ToolType } from '@/types/floorplan';

const tools: { id: ToolType; icon: React.ReactNode; label: string }[] = [
  { id: 'select', icon: <MousePointer2 size={20} />, label: '选择' },
  { id: 'wall-straight', icon: <Minus size={20} />, label: '直线墙' },
  { id: 'wall-arc', icon: <Spline size={20} />, label: '弧形墙' },
  { id: 'door', icon: <DoorOpen size={20} />, label: '门' },
  { id: 'window', icon: <Square size={20} />, label: '窗' },
  { id: 'measure', icon: <Ruler size={20} />, label: '测量' },
  { id: 'annotation', icon: <MessageSquarePlus size={20} />, label: '批注' },
  { id: 'furniture', icon: <Sofa size={20} />, label: '家具' },
];

export const ToolPanel: React.FC = () => {
  const { currentTool, setCurrentTool, addWall, floorPlan } = useFloorPlanStore();
  const { togglePanel } = useUIStore();

  const handleToolClick = (toolId: ToolType) => {
    if (toolId === 'furniture') {
      togglePanel('furnitureLibrary');
    } else if (toolId === 'door') {
      const walls = floorPlan.walls;
      if (walls.length > 0) {
        const wall = walls[0];
        addWall({
          type: 'straight',
          start: { x: 3, y: 0 },
          end: { x: 6, y: 0 },
          thickness: floorPlan.project.settings.wallThickness,
          height: floorPlan.project.settings.wallHeight,
          materialId: 'mat-wall-white',
        });
      }
      setCurrentTool('select');
    } else {
      setCurrentTool(toolId);
    }
  };

  return (
    <div className="w-16 bg-neutral-800 border-r border-neutral-700 flex flex-col items-center py-3 gap-1 select-none">
      {tools.map(({ id, icon, label }) => (
        <button
          key={id}
          onClick={() => handleToolClick(id)}
          className={`relative w-12 h-12 flex items-center justify-center rounded transition-all group ${
            currentTool === id
              ? 'bg-accent-primary/20 text-accent-primary'
              : 'text-neutral-400 hover:text-white hover:bg-neutral-700'
          }`}
          title={label}
        >
          {currentTool === id && (
            <div className="absolute left-0 top-2 bottom-2 w-0.5 bg-accent-primary rounded-r" />
          )}
          {icon}
          <span className="absolute left-full ml-2 px-2 py-1 bg-neutral-900 text-white text-xs rounded opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap z-50">
            {label}
          </span>
        </button>
      ))}

      <div className="flex-1" />

      <button
        onClick={() => togglePanel('projectList')}
        className="w-12 h-12 flex items-center justify-center rounded text-neutral-400 hover:text-white hover:bg-neutral-700 transition-all group"
        title="项目列表"
      >
        <Plus size={20} />
      </button>
    </div>
  );
};
