import React, { useState } from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import { exportAsPNG, exportAsSVG, exportAsPDF } from '../../utils/export';
import type { ExportFormat, ExportOptions } from '../../types';

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 80,
  right: 80,
  width: 320,
  backgroundColor: '#ffffff',
  borderRadius: 12,
  boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
  border: '1px solid #e5e7eb',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  zIndex: 100,
};

const headerStyle: React.CSSProperties = {
  padding: '12px 16px',
  borderBottom: '1px solid #e5e7eb',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
};

const contentStyle: React.CSSProperties = {
  padding: 16,
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
};

const formatButtonStyle = (active: boolean): React.CSSProperties => ({
  flex: 1,
  padding: '16px 12px',
  border: active ? '2px solid #3b82f6' : '1px solid #d1d5db',
  borderRadius: 8,
  backgroundColor: active ? '#eff6ff' : '#ffffff',
  cursor: 'pointer',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 6,
  transition: 'all 0.15s',
});

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: '1px solid #d1d5db',
  borderRadius: 6,
  fontSize: 13,
  outline: 'none',
};

const labelStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 500,
  color: '#374151',
  marginBottom: 6,
};

const ExportPanel: React.FC = () => {
  const { toggleExportPanel, strokes, shapes } = useBoardStore();
  const [format, setFormat] = useState<ExportFormat>('png');
  const [scale, setScale] = useState(2);
  const [includeBackground, setIncludeBackground] = useState(true);
  const [background, setBackground] = useState('#ffffff');
  const [onlySelected, setOnlySelected] = useState(false);
  const [quality, setQuality] = useState(0.9);
  const [exporting, setExporting] = useState(false);

  const handleExport = async () => {
    setExporting(true);
    try {
      const options: ExportOptions = {
        format,
        scale,
        quality,
        includeBackground,
        background,
        onlySelected,
      };

      switch (format) {
        case 'png':
          await exportAsPNG(strokes, shapes, options);
          break;
        case 'svg':
          await exportAsSVG(strokes, shapes, options);
          break;
        case 'pdf':
          await exportAsPDF(strokes, shapes, options);
          break;
      }
    } finally {
      setExporting(false);
    }
  };

  return (
    <div style={containerStyle}>
      <div style={headerStyle}>
        <div style={{ fontWeight: 600, fontSize: 14, color: '#111827' }}>
          ⬇ 导出
        </div>
        <button
          onClick={toggleExportPanel}
          style={{
            border: 'none',
            background: 'transparent',
            cursor: 'pointer',
            fontSize: 16,
            color: '#6b7280',
            padding: 4,
          }}
          title="关闭"
        >
          ✕
        </button>
      </div>

      <div style={contentStyle}>
        <div>
          <div style={labelStyle}>导出格式</div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              style={formatButtonStyle(format === 'png')}
              onClick={() => setFormat('png')}
            >
              <span style={{ fontSize: 20 }}>🖼️</span>
              <span style={{ fontSize: 12, fontWeight: 500, color: format === 'png' ? '#3b82f6' : '#374151' }}>
                PNG
              </span>
            </button>
            <button
              style={formatButtonStyle(format === 'svg')}
              onClick={() => setFormat('svg')}
            >
              <span style={{ fontSize: 20 }}>📐</span>
              <span style={{ fontSize: 12, fontWeight: 500, color: format === 'svg' ? '#3b82f6' : '#374151' }}>
                SVG
              </span>
            </button>
            <button
              style={formatButtonStyle(format === 'pdf')}
              onClick={() => setFormat('pdf')}
            >
              <span style={{ fontSize: 20 }}>📄</span>
              <span style={{ fontSize: 12, fontWeight: 500, color: format === 'pdf' ? '#3b82f6' : '#374151' }}>
                PDF
              </span>
            </button>
          </div>
        </div>

        <div>
          <div style={labelStyle}>缩放比例: {scale}x</div>
          <input
            type="range"
            min="1"
            max="4"
            step="0.5"
            value={scale}
            onChange={(e) => setScale(Number(e.target.value))}
            style={{ width: '100%' }}
          />
        </div>

        {format === 'png' && (
          <div>
            <div style={labelStyle}>图片质量: {Math.round(quality * 100)}%</div>
            <input
              type="range"
              min="0.1"
              max="1"
              step="0.1"
              value={quality}
              onChange={(e) => setQuality(Number(e.target.value))}
              style={{ width: '100%' }}
            />
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            type="checkbox"
            id="include-bg"
            checked={includeBackground}
            onChange={(e) => setIncludeBackground(e.target.checked)}
          />
          <label htmlFor="include-bg" style={{ fontSize: 13, color: '#374151' }}>
            包含背景
          </label>
        </div>

        {includeBackground && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={labelStyle}>背景颜色:</span>
            <input
              type="color"
              value={background}
              onChange={(e) => setBackground(e.target.value)}
              style={{
                width: 32,
                height: 32,
                border: '1px solid #d1d5db',
                borderRadius: 4,
                cursor: 'pointer',
                padding: 0,
              }}
            />
            <input
              type="text"
              value={background}
              onChange={(e) => setBackground(e.target.value)}
              style={inputStyle}
            />
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            type="checkbox"
            id="only-selected"
            checked={onlySelected}
            onChange={(e) => setOnlySelected(e.target.checked)}
          />
          <label htmlFor="only-selected" style={{ fontSize: 13, color: '#374151' }}>
            仅导出选中内容
          </label>
        </div>

        <button
          onClick={handleExport}
          disabled={exporting}
          style={{
            padding: '10px 16px',
            border: 'none',
            borderRadius: 8,
            backgroundColor: exporting ? '#93c5fd' : '#3b82f6',
            color: '#ffffff',
            fontSize: 14,
            fontWeight: 500,
            cursor: exporting ? 'not-allowed' : 'pointer',
            marginTop: 8,
          }}
        >
          {exporting ? '导出中...' : '开始导出'}
        </button>
      </div>
    </div>
  );
};

export default ExportPanel;
