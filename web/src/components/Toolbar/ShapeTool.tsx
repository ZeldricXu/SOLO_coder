import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import type { ShapeType } from '../../types';

const containerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  paddingLeft: 8,
  paddingRight: 4,
  borderLeft: '1px solid #e5e7eb',
};

const labelStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#6b7280',
};

const shapeButtonStyle = (active: boolean): React.CSSProperties => ({
  width: 32,
  height: 32,
  border: active ? '2px solid #3b82f6' : '1px solid #d1d5db',
  background: active ? '#eff6ff' : '#ffffff',
  borderRadius: 6,
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 16,
});

const inputStyle: React.CSSProperties = {
  width: 50,
  height: 32,
  border: '1px solid #d1d5db',
  borderRadius: 6,
  padding: '0 8px',
  fontSize: 12,
  outline: 'none',
};

const shapes: { type: ShapeType; icon: string; label: string }[] = [
  { type: 'rectangle', icon: '▭', label: '矩形' },
  { type: 'ellipse', icon: '○', label: '椭圆' },
  { type: 'line', icon: '╱', label: '直线' },
  { type: 'arrow', icon: '→', label: '箭头' },
  { type: 'triangle', icon: '△', label: '三角形' },
  { type: 'polygon', icon: '⬡', label: '多边形' },
];

const ShapeTool: React.FC = () => {
  const { tool, setShapeType, setShapeStyle } = useBoardStore();
  const { shapeType, shapeStyle } = tool;

  return (
    <div style={containerStyle}>
      <span style={labelStyle}>图形</span>
      <div style={{ display: 'flex', gap: 4 }}>
        {shapes.map((s) => (
          <button
            key={s.type}
            style={shapeButtonStyle(shapeType === s.type)}
            onClick={() => setShapeType(s.type)}
            title={s.label}
          >
            {s.icon}
          </button>
        ))}
      </div>

      <span style={labelStyle}>描边</span>
      <label style={{ position: 'relative' }}>
        <input
          type="color"
          value={shapeStyle.stroke || '#000000'}
          onChange={(e) => setShapeStyle({ stroke: e.target.value })}
          style={{
            position: 'absolute',
            opacity: 0,
            cursor: 'pointer',
            width: 32,
            height: 32,
          }}
        />
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: 6,
            border: '1px solid #d1d5db',
            backgroundColor: shapeStyle.stroke || '#000000',
            cursor: 'pointer',
          }}
        />
      </label>

      <span style={labelStyle}>填充</span>
      <label style={{ position: 'relative' }}>
        <input
          type="color"
          value={shapeStyle.fill && shapeStyle.fill !== 'transparent' ? shapeStyle.fill : '#ffffff'}
          onChange={(e) => setShapeStyle({ fill: e.target.value })}
          style={{
            position: 'absolute',
            opacity: 0,
            cursor: 'pointer',
            width: 32,
            height: 32,
          }}
        />
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: 6,
            border: '1px solid #d1d5db',
            backgroundColor: shapeStyle.fill && shapeStyle.fill !== 'transparent' ? shapeStyle.fill : 'transparent',
            backgroundImage:
              !shapeStyle.fill || shapeStyle.fill === 'transparent'
                ? 'linear-gradient(45deg, #ccc 25%, transparent 25%), linear-gradient(-45deg, #ccc 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #ccc 75%), linear-gradient(-45deg, transparent 75%, #ccc 75%)'
                : 'none',
            backgroundSize: '8px 8px',
            backgroundPosition: '0 0, 0 4px, 4px -4px, -4px 0px',
            cursor: 'pointer',
          }}
        />
      </label>
      <button
        style={{
          height: 32,
          padding: '0 8px',
          border: '1px solid #d1d5db',
          borderRadius: 6,
          background: shapeStyle.fill === 'transparent' ? '#eff6ff' : '#ffffff',
          cursor: 'pointer',
          fontSize: 11,
        }}
        onClick={() =>
          setShapeStyle({
            fill: shapeStyle.fill === 'transparent' ? '#ffffff' : 'transparent',
          })
        }
      >
        无填充
      </button>

      <span style={labelStyle}>线宽</span>
      <input
        type="number"
        min="0"
        max="20"
        value={shapeStyle.strokeWidth ?? 2}
        onChange={(e) => setShapeStyle({ strokeWidth: Number(e.target.value) })}
        style={inputStyle}
      />

      <span style={labelStyle}>透明度</span>
      <input
        type="range"
        min="0.1"
        max="1"
        step="0.1"
        value={shapeStyle.opacity ?? 1}
        onChange={(e) => setShapeStyle({ opacity: Number(e.target.value) })}
        style={{ width: 60 }}
      />
    </div>
  );
};

export default ShapeTool;
