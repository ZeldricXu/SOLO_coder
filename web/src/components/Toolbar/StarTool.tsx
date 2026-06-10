import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';

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

const StarTool: React.FC = () => {
  const { tool, setStarConfig, setShapeStyle } = useBoardStore();
  const { starConfig, shapeStyle } = tool;

  const innerRatio = starConfig.innerRadius / starConfig.outerRadius;

  const handleRatioChange = (ratio: number) => {
    setStarConfig({
      innerRadius: starConfig.outerRadius * ratio,
    });
  };

  return (
    <div style={containerStyle}>
      <span style={labelStyle}>星形</span>

      <span style={labelStyle}>角数</span>
      <input
        type="range"
        min="3"
        max="50"
        value={starConfig.numPoints}
        onChange={(e) => setStarConfig({ numPoints: Number(e.target.value) })}
        style={{ width: 80 }}
      />
      <input
        type="number"
        min="3"
        max="50"
        value={starConfig.numPoints}
        onChange={(e) => setStarConfig({ numPoints: Math.max(3, Math.min(50, Number(e.target.value))) })}
        style={inputStyle}
      />

      <span style={labelStyle}>内径比</span>
      <input
        type="range"
        min="0"
        max="1"
        step="0.05"
        value={innerRatio}
        onChange={(e) => handleRatioChange(Number(e.target.value))}
        style={{ width: 80 }}
      />
      <input
        type="number"
        min="0"
        max="1"
        step="0.05"
        value={Number(innerRatio.toFixed(2))}
        onChange={(e) => handleRatioChange(Math.max(0, Math.min(1, Number(e.target.value))))}
        style={{ ...inputStyle, width: 55 }}
      />

      <span style={labelStyle}>外径</span>
      <input
        type="number"
        min="10"
        max="500"
        value={starConfig.outerRadius}
        onChange={(e) => setStarConfig({ outerRadius: Number(e.target.value) })}
        style={inputStyle}
      />

      <span style={labelStyle}>旋转</span>
      <input
        type="range"
        min="0"
        max="360"
        value={starConfig.rotation}
        onChange={(e) => setStarConfig({ rotation: Number(e.target.value) })}
        style={{ width: 60 }}
      />

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
    </div>
  );
};

export default StarTool;
