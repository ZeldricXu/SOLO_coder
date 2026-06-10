import React, { useState } from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import StrokeTool from './StrokeTool';
import ShapeTool from './ShapeTool';
import type { ToolType } from '../../types';

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  top: 16,
  left: '50%',
  transform: 'translateX(-50%)',
  display: 'flex',
  gap: 4,
  backgroundColor: '#ffffff',
  padding: 6,
  borderRadius: 12,
  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.1)',
  border: '1px solid #e5e7eb',
};

const toolButtonStyle = (active: boolean): React.CSSProperties => ({
  width: 40,
  height: 40,
  border: 'none',
  background: active ? '#3b82f6' : 'transparent',
  borderRadius: 8,
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 18,
  color: active ? '#ffffff' : '#374151',
  transition: 'all 0.15s',
});

const dividerStyle: React.CSSProperties = {
  width: 1,
  backgroundColor: '#e5e7eb',
  margin: '4px 4px',
};

const actionButtonStyle: React.CSSProperties = {
  padding: '0 12px',
  height: 40,
  border: 'none',
  background: 'transparent',
  borderRadius: 8,
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 13,
  color: '#374151',
  transition: 'all 0.15s',
};

const tools: { type: ToolType; icon: string; label: string }[] = [
  { type: 'select', icon: '▢', label: '选择' },
  { type: 'pen', icon: '✎', label: '画笔' },
  { type: 'eraser', icon: '⌫', label: '橡皮擦' },
  { type: 'shape', icon: '○', label: '图形' },
  { type: 'text', icon: 'T', label: '文字' },
  { type: 'comment', icon: '💬', label: '评论' },
  { type: 'pan', icon: '✋', label: '平移' },
];

const Toolbar: React.FC = () => {
  const { tool, setActiveTool, undo, redo, toggleExportPanel, toggleVersionTree, toggleComments, deleteSelected, selectedIds } = useBoardStore();
  const [showStrokeConfig, setShowStrokeConfig] = useState(false);
  const [showShapeConfig, setShowShapeConfig] = useState(false);

  const handleToolClick = (toolType: ToolType) => {
    setActiveTool(toolType);
    setShowStrokeConfig(toolType === 'pen');
    setShowShapeConfig(toolType === 'shape');
  };

  return (
    <div style={containerStyle}>
      {tools.map((t) => (
        <button
          key={t.type}
          style={toolButtonStyle(tool.activeTool === t.type)}
          onClick={() => handleToolClick(t.type)}
          title={t.label}
          onMouseOver={(e) => {
            if (tool.activeTool !== t.type) {
              e.currentTarget.style.background = '#f3f4f6';
            }
          }}
          onMouseOut={(e) => {
            if (tool.activeTool !== t.type) {
              e.currentTarget.style.background = 'transparent';
            }
          }}
        >
          {t.icon}
        </button>
      ))}

      {showStrokeConfig && <StrokeTool />}
      {showShapeConfig && <ShapeTool />}

      <div style={dividerStyle} />

      <button
        style={actionButtonStyle}
        onClick={undo}
        title="撤销 (Ctrl+Z)"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = 'transparent';
        }}
      >
        ↶
      </button>

      <button
        style={actionButtonStyle}
        onClick={redo}
        title="重做 (Ctrl+Y)"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = 'transparent';
        }}
      >
        ↷
      </button>

      {selectedIds.length > 0 && (
        <button
          style={{ ...actionButtonStyle, color: '#ef4444' }}
          onClick={deleteSelected}
          title="删除选中 (Delete)"
          onMouseOver={(e) => {
            e.currentTarget.style.background = '#fee2e2';
          }}
          onMouseOut={(e) => {
            e.currentTarget.style.background = 'transparent';
          }}
        >
          🗑
        </button>
      )}

      <div style={dividerStyle} />

      <button
        style={actionButtonStyle}
        onClick={toggleComments}
        title="评论"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = 'transparent';
        }}
      >
        💬 评论
      </button>

      <button
        style={actionButtonStyle}
        onClick={toggleVersionTree}
        title="历史版本"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = 'transparent';
        }}
      >
        🕒 历史
      </button>

      <button
        style={actionButtonStyle}
        onClick={toggleExportPanel}
        title="导出"
        onMouseOver={(e) => {
          e.currentTarget.style.background = '#f3f4f6';
        }}
        onMouseOut={(e) => {
          e.currentTarget.style.background = 'transparent';
        }}
      >
        ⬇ 导出
      </button>
    </div>
  );
};

export default Toolbar;
