import React, { useState, useMemo } from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import { useToolRegistry } from '../../stores/useToolRegistry';
import StrokeTool from './StrokeTool';
import ShapeTool from './ShapeTool';
import StarTool from './StarTool';
import ArrowTool from './ArrowTool';
import TextTool from './TextTool';
import type { ToolType } from '../../types';

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  top: 16,
  left: '50%',
  transform: 'translateX(-50%)',
  display: 'flex',
  alignItems: 'flex-start',
  flexWrap: 'wrap',
  maxWidth: 'calc(100% - 32px)',
  gap: 4,
  backgroundColor: '#ffffff',
  padding: 6,
  borderRadius: 12,
  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.1)',
  border: '1px solid #e5e7eb',
};

const mainBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
};

const configBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  width: '100%',
  flexWrap: 'wrap',
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

const categoryDividerStyle: React.CSSProperties = {
  width: 1,
  height: 28,
  backgroundColor: '#e5e7eb',
  margin: '0 4px',
  alignSelf: 'center',
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

const categoryOrder: Array<'interaction' | 'drawing' | 'shape' | 'text' | 'utility'> = [
  'interaction',
  'drawing',
  'shape',
  'text',
  'utility',
];

const categoryLabels: Record<string, string> = {
  interaction: '交互',
  drawing: '绘图',
  shape: '图形',
  text: '文字',
  utility: '工具',
};

const Toolbar: React.FC = () => {
  const { tool, setActiveTool, undo, redo, toggleExportPanel, toggleVersionTree, toggleComments, deleteSelected, selectedIds, setShapeType } = useBoardStore();
  const { getAllTools, getComponentForTool } = useToolRegistry();
  const [activeConfigPanel, setActiveConfigPanel] = useState<ToolType | null>(null);

  const toolsByCategory = useMemo(() => {
    const grouped: Record<string, ReturnType<typeof getAllTools>> = {};
    const allTools = getAllTools();
    for (const t of allTools) {
      if (!grouped[t.category]) {
        grouped[t.category] = [];
      }
      grouped[t.category].push(t);
    }
    return grouped;
  }, [getAllTools]);

  const handleToolClick = (toolType: ToolType) => {
    setActiveTool(toolType);

    if (toolType === 'star' || toolType === 'arrow' || toolType === 'rich-text') {
      setShapeType(toolType);
    }

    switch (toolType) {
      case 'pen':
        setActiveConfigPanel(activeConfigPanel === 'pen' ? null : 'pen');
        break;
      case 'shape':
        setActiveConfigPanel(activeConfigPanel === 'shape' ? null : 'shape');
        break;
      case 'star':
        setActiveConfigPanel(activeConfigPanel === 'star' ? null : 'star');
        break;
      case 'arrow':
        setActiveConfigPanel(activeConfigPanel === 'arrow' ? null : 'arrow');
        break;
      case 'text':
      case 'rich-text':
        setActiveConfigPanel((p) => (p === 'text' || p === 'rich-text' ? null : 'rich-text'));
        break;
      default:
        setActiveConfigPanel(null);
        break;
    }
  };

  const renderConfigPanel = () => {
    const Component = activeConfigPanel ? getComponentForTool(activeConfigPanel) : null;
    if (!Component) {
      switch (activeConfigPanel) {
        case 'pen':
          return <StrokeTool key="pen-config" />;
        case 'shape':
          return <ShapeTool key="shape-config" />;
        case 'star':
          return <StarTool key="star-config" />;
        case 'arrow':
          return <ArrowTool key="arrow-config" />;
        case 'text':
        case 'rich-text':
          return <TextTool key="text-config" />;
        default:
          return null;
      }
    }
    return <Component key={`${activeConfigPanel}-config`} />;
  };

  return (
    <div style={containerStyle}>
      <div style={mainBarStyle}>
        {categoryOrder.map((category, catIdx) => {
          const categoryTools = toolsByCategory[category];
          if (!categoryTools || categoryTools.length === 0) return null;

          return (
            <React.Fragment key={category}>
              {catIdx > 0 && <div style={categoryDividerStyle} />}
              {categoryTools.map((t) => {
                const toolTypeId = t.id as ToolType;
                const isActive = tool.activeTool === toolTypeId;
                return (
                  <button
                    key={t.id}
                    style={toolButtonStyle(isActive)}
                    onClick={() => handleToolClick(toolTypeId)}
                    title={`${t.name}${categoryLabels[category] ? ` [${categoryLabels[category]}]` : ''}`}
                    onMouseOver={(e) => {
                      if (!isActive) {
                        e.currentTarget.style.background = '#f3f4f6';
                      }
                    }}
                    onMouseOut={(e) => {
                      if (!isActive) {
                        e.currentTarget.style.background = 'transparent';
                      }
                    }}
                  >
                    {t.icon}
                  </button>
                );
              })}
            </React.Fragment>
          );
        })}

        <div style={categoryDividerStyle} />

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

        <div style={categoryDividerStyle} />

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

      {activeConfigPanel && (
        <div style={configBarStyle}>
          {renderConfigPanel()}
        </div>
      )}
    </div>
  );
};

export default Toolbar;
