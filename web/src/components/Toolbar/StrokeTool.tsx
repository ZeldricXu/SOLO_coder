import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import type { StrokeCap, StrokeJoin } from '../../types';

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

const colorPickerStyle: React.CSSProperties = {
  width: 32,
  height: 32,
  border: '1px solid #d1d5db',
  borderRadius: 6,
  cursor: 'pointer',
  padding: 0,
  background: 'none',
};

const inputStyle: React.CSSProperties = {
  width: 50,
  height: 32,
  border: '1px solid #d1d5db',
  borderRadius: 6,
  padding: '0 8px',
  fontSize: 12,
  outline: 'none',
};

const colors = [
  '#000000',
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#3b82f6',
  '#8b5cf6',
  '#ec4899',
];

const StrokeTool: React.FC = () => {
  const { tool, setStrokeStyle } = useBoardStore();
  const { strokeStyle } = tool;

  return (
    <div style={containerStyle}>
      <span style={labelStyle}>颜色</span>
      <div style={{ display: 'flex', gap: 4 }}>
        {colors.map((color) => (
          <button
            key={color}
            style={{
              width: 24,
              height: 24,
              borderRadius: '50%',
              border: strokeStyle.color === color ? '2px solid #3b82f6' : '2px solid transparent',
              backgroundColor: color,
              cursor: 'pointer',
              padding: 0,
            }}
            onClick={() => setStrokeStyle({ color })}
          />
        ))}
        <label style={{ position: 'relative' }}>
          <input
            type="color"
            value={strokeStyle.color}
            onChange={(e) => setStrokeStyle({ color: e.target.value })}
            style={{
              ...colorPickerStyle,
              position: 'absolute',
              opacity: 0,
              cursor: 'pointer',
            }}
          />
          <div
            style={{
              width: 32,
              height: 32,
              borderRadius: 6,
              border: '1px solid #d1d5db',
              background: `conic-gradient(red, yellow, lime, aqua, blue, magenta, red)`,
              cursor: 'pointer',
            }}
          />
        </label>
      </div>

      <span style={labelStyle}>粗细</span>
      <input
        type="range"
        min="1"
        max="50"
        value={strokeStyle.width}
        onChange={(e) => setStrokeStyle({ width: Number(e.target.value) })}
        style={{ width: 80 }}
      />
      <input
        type="number"
        min="1"
        max="50"
        value={strokeStyle.width}
        onChange={(e) => setStrokeStyle({ width: Number(e.target.value) })}
        style={inputStyle}
      />

      <span style={labelStyle}>透明度</span>
      <input
        type="range"
        min="0.1"
        max="1"
        step="0.1"
        value={strokeStyle.opacity}
        onChange={(e) => setStrokeStyle({ opacity: Number(e.target.value) })}
        style={{ width: 60 }}
      />

      <span style={labelStyle}>端点</span>
      <select
        value={strokeStyle.cap || 'round'}
        onChange={(e) => setStrokeStyle({ cap: e.target.value as StrokeCap })}
        style={{ ...inputStyle, width: 60 }}
      >
        <option value="round">圆</option>
        <option value="square">方</option>
        <option value="butt">平</option>
      </select>

      <span style={labelStyle}>连接</span>
      <select
        value={strokeStyle.join || 'round'}
        onChange={(e) => setStrokeStyle({ join: e.target.value as StrokeJoin })}
        style={{ ...inputStyle, width: 60 }}
      >
        <option value="round">圆</option>
        <option value="bevel">斜</option>
        <option value="miter">尖</option>
      </select>
    </div>
  );
};

export default StrokeTool;
