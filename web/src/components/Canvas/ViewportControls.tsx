import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';

const buttonStyle: React.CSSProperties = {
  width: 36,
  height: 36,
  border: '1px solid #d1d5db',
  background: '#ffffff',
  borderRadius: 6,
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 18,
  fontWeight: 500,
  color: '#374151',
  transition: 'all 0.15s',
};

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 24,
  right: 24,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  backgroundColor: '#ffffff',
  padding: 8,
  borderRadius: 10,
  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.1)',
  border: '1px solid #e5e7eb',
};

const zoomDisplayStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#6b7280',
  textAlign: 'center',
  padding: '4px 0',
  minWidth: 50,
  fontFamily: 'monospace',
};

const ViewportControls: React.FC = () => {
  const { viewport, zoomIn, zoomOut, resetViewport, toggleGrid, showGrid } = useBoardStore();

  const zoomPercent = Math.round(viewport.zoom * 100);

  return (
    <div style={containerStyle}>
      <button
        style={buttonStyle}
        onClick={zoomIn}
        title="放大 (Ctrl+)"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = '#ffffff';
        }}
      >
        +
      </button>

      <div style={zoomDisplayStyle}>{zoomPercent}%</div>

      <button
        style={buttonStyle}
        onClick={zoomOut}
        title="缩小 (Ctrl-)"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = '#ffffff';
        }}
      >
        −
      </button>

      <button
        style={buttonStyle}
        onClick={resetViewport}
        title="重置视图"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = '#ffffff';
        }}
      >
        ⌂
      </button>

      <div
        style={{
          height: 1,
          backgroundColor: '#e5e7eb',
          margin: '4px 0',
        }}
      />

      <button
        style={{
          ...buttonStyle,
          background: showGrid ? '#3b82f6' : '#ffffff',
          color: showGrid ? '#ffffff' : '#374151',
          borderColor: showGrid ? '#3b82f6' : '#d1d5db',
        }}
        onClick={toggleGrid}
        title="显示/隐藏网格"
        onMouseOver={(e) => {
          if (!showGrid) e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          if (!showGrid) e.currentTarget.style.background = '#ffffff';
        }}
      >
        #
      </button>
    </div>
  );
};

export default ViewportControls;
