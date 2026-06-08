import React from 'react';
import { Pencil, ArrowRight, Type, Trash2, Circle, Minus } from 'lucide-react';
import type { DrawingPrimitiveType } from '@/types/drawing';
import { DEFAULT_DRAWING_COLORS, DEFAULT_DRAWING_LINE_WIDTHS } from '@/types/drawing';
import type { DrawingSession } from '@/types/drawing';

interface DrawingToolbarProps {
  session: DrawingSession;
  onChange: (session: Partial<DrawingSession>) => void;
  onClear: () => void;
  enabled: boolean;
}

export const DrawingToolbar: React.FC<DrawingToolbarProps> = ({
  session,
  onChange,
  onClear,
  enabled,
}) => {
  const tools: { type: DrawingPrimitiveType; icon: React.ReactNode; label: string }[] = [
    { type: 'freehand', icon: <Pencil size={16} />, label: '画笔' },
    { type: 'line', icon: <Minus size={16} />, label: '直线' },
    { type: 'arrow', icon: <ArrowRight size={16} />, label: '箭头' },
  ];

  return (
    <div
      className={`absolute bottom-4 left-1/2 -translate-x-1/2 bg-neutral-800 border border-neutral-700 rounded-lg shadow-xl p-2 flex items-center gap-1 transition-opacity ${
        enabled ? 'opacity-100' : 'opacity-50 pointer-events-none'
      }`}
    >
      <div className="flex items-center gap-0.5 pr-2 border-r border-neutral-700 mr-1">
        {tools.map((tool) => (
          <button
            key={tool.type}
            onClick={() => onChange({ tool: tool.type })}
            title={tool.label}
            className={`p-2 rounded transition-colors ${
              session.tool === tool.type
                ? 'bg-accent-primary text-white'
                : 'text-neutral-400 hover:bg-neutral-700 hover:text-white'
            }`}
          >
            {tool.icon}
          </button>
        ))}
      </div>

      <div className="flex items-center gap-1 pr-2 border-r border-neutral-700 mr-1">
        {DEFAULT_DRAWING_COLORS.map((color) => (
          <button
            key={color}
            onClick={() => onChange({ color })}
            title={color}
            className={`w-6 h-6 rounded-full border-2 transition-transform hover:scale-110 ${
              session.color === color ? 'border-white scale-110' : 'border-transparent'
            }`}
            style={{ backgroundColor: color }}
          />
        ))}
      </div>

      <div className="flex items-center gap-1 pr-2 border-r border-neutral-700 mr-1">
        {DEFAULT_DRAWING_LINE_WIDTHS.map((width) => (
          <button
            key={width}
            onClick={() => onChange({ lineWidth: width })}
            title={`${width}px`}
            className={`w-8 h-6 rounded flex items-center justify-center transition-colors ${
              session.lineWidth === width
                ? 'bg-accent-primary text-white'
                : 'text-neutral-400 hover:bg-neutral-700 hover:text-white'
            }`}
          >
            <div
              className="rounded-full bg-current"
              style={{ width: 4 + width * 2, height: 4 + width * 2 }}
            />
          </button>
        ))}
      </div>

      <button
        onClick={onClear}
        title="清除所有绘图"
        className="p-2 rounded text-neutral-400 hover:bg-neutral-700 hover:text-red-400 transition-colors"
      >
        <Trash2 size={16} />
      </button>
    </div>
  );
};
