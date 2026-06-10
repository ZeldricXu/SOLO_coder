import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import type { ArrowHeadStyle } from '../../types';

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

const inputStyle: React.CSSProperties = {
  width: 50,
  height: 32,
  border: '1px solid #d1d5db',
  borderRadius: 6,
  padding: '0 8px',
  fontSize: 12,
  outline: 'none',
};

const selectStyle: React.CSSProperties = {
  ...inputStyle,
  width: 70,
  cursor: 'pointer',
};

const arrowHeadStyles: { value: ArrowHeadStyle; label: string; icon: string }[] = [
  { value: 'none', label: '无', icon: '·' },
  { value: 'triangle', label: '三角', icon: '►' },
  { value: 'diamond', label: '菱形', icon: '◆' },
];

const ArrowTool: React.FC = () => {
  const { tool, setArrowConfig, setShapeStyle } = useBoardStore();
  const { arrowConfig, shapeStyle } = tool;

  return (
    <div style={containerStyle}>
      <span style={labelStyle}>箭头</span>

      <span style={labelStyle}>起点</span>
      <select
        value={arrowConfig.tailStyle}
        onChange={(e) => setArrowConfig({ tailStyle: e.target.value as ArrowHeadStyle })}
        style={selectStyle}
      >
        {arrowHeadStyles.map((s) => (
          <option key={s.value} value={s.value}>
            {s.icon} {s.label}
          </option>
        ))}
      </select>

      <span style={labelStyle}>终点</span>
      <select
        value={arrowConfig.headStyle}
        onChange={(e) => setArrowConfig({ headStyle: e.target.value as ArrowHeadStyle })}
        style={selectStyle}
      >
        {arrowHeadStyles.map((s) => (
          <option key={s.value} value={s.value}>
            {s.icon} {s.label}
          </option>
        ))}
      </select>

      <span style={labelStyle}>起点大小</span>
      <input
        type="range"
        min="4"
        max="40"
        value={arrowConfig.tailSize}
        onChange={(e) => setArrowConfig({ tailSize: Number(e.target.value) })}
        style={{ width: 60 }}
      />
      <input
        type="number"
        min="4"
        max="40"
        value={arrowConfig.tailSize}
        onChange={(e) => setArrowConfig({ tailSize: Math.max(4, Math.min(40, Number(e.target.value))) })}
        style={inputStyle}
      />

      <span style={labelStyle}>终点大小</span>
      <input
        type="range"
        min="4"
        max="40"
        value={arrowConfig.headSize}
        onChange={(e) => setArrowConfig({ headSize: Number(e.target.value) })}
        style={{ width: 60 }}
      />
      <input
        type="number"
        min="4"
        max="40"
        value={arrowConfig.headSize}
        onChange={(e) => setArrowConfig({ headSize: Math.max(4, Math.min(40, Number(e.target.value))) })}
        style={inputStyle}
      />

      <span style={labelStyle}>颜色</span>
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

export default ArrowTool;
