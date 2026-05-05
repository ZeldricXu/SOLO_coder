import React from 'react';
import useStore from '../store';
import geometryEditor from '../services/geometryEditor';
import sceneManager from '../services/renderer/SceneManager';

const styles = {
  toolbar: {
    display: 'flex',
    alignItems: 'center',
    height: '48px',
    backgroundColor: '#0f3460',
    borderBottom: '1px solid #0d1b2a',
    padding: '0 16px',
    gap: '8px'
  },
  logo: {
    fontSize: '18px',
    fontWeight: 700,
    color: '#e94560',
    marginRight: '16px'
  },
  separator: {
    width: '1px',
    height: '28px',
    backgroundColor: '#16213e',
    margin: '0 8px'
  },
  button: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '36px',
    height: '32px',
    borderRadius: '4px',
    border: 'none',
    backgroundColor: 'transparent',
    color: '#8892b0',
    cursor: 'pointer',
    fontSize: '14px',
    transition: 'all 0.2s'
  },
  buttonActive: {
    backgroundColor: '#e94560',
    color: '#fff'
  },
  buttonHover: {
    backgroundColor: '#16213e',
    color: '#e0e0e0'
  },
  toolGroup: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px'
  },
  transformGroup: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
    marginLeft: 'auto'
  },
  disconnectButton: {
    padding: '6px 16px',
    borderRadius: '4px',
    border: 'none',
    backgroundColor: '#dc3545',
    color: '#fff',
    cursor: 'pointer',
    fontSize: '13px',
    marginLeft: '16px'
  },
  spacer: {
    flex: 1
  },
  label: {
    fontSize: '12px',
    color: '#8892b0',
    marginRight: '8px'
  }
};

const tools = [
  { id: 'select', icon: '🖱️', tooltip: '选择工具 (V)' },
  { id: 'translate', icon: '↔️', tooltip: '移动工具 (W)' },
  { id: 'rotate', icon: '🔄', tooltip: '旋转工具 (E)' },
  { id: 'scale', icon: '⤢', tooltip: '缩放工具 (R)' }
];

const creationTools = [
  { id: 'wall', icon: '🧱', tooltip: '创建墙体', label: '墙体' },
  { id: 'door', icon: '🚪', tooltip: '创建门', label: '门' },
  { id: 'window', icon: '🪟', tooltip: '创建窗户', label: '窗户' },
  { id: 'furniture', icon: '🪑', tooltip: '创建家具', label: '家具' }
];

function Toolbar({ onDisconnect }) {
  const { activeTool, selectedObjectId, setActiveTool } = useStore();
  const [hoveredButton, setHoveredButton] = useState(null);

  const handleToolClick = (toolId) => {
    geometryEditor.setActiveTool(toolId);
  };

  const handleDelete = () => {
    if (selectedObjectId) {
      geometryEditor.deleteObject(selectedObjectId);
    }
  };

  const handleDuplicate = () => {
    if (selectedObjectId) {
      geometryEditor.duplicateObject(selectedObjectId);
    }
  };

  const handleFocus = () => {
    if (selectedObjectId) {
      sceneManager.focusOnObject(selectedObjectId);
    }
  };

  const handleGridToggle = () => {
    sceneManager.setGridVisible(!sceneManager.gridHelper?.visible);
  };

  const getButtonStyle = (toolId, isHovered) => {
    let style = { ...styles.button };
    if (activeTool === toolId) {
      style = { ...style, ...styles.buttonActive };
    } else if (isHovered) {
      style = { ...style, ...styles.buttonHover };
    }
    return style;
  };

  return (
    <div style={styles.toolbar}>
      <span style={styles.logo}>SceneForge</span>
      
      <div style={styles.separator} />
      
      <div style={styles.toolGroup}>
        {tools.map((tool) => (
          <button
            key={tool.id}
            style={getButtonStyle(tool.id, hoveredButton === tool.id)}
            onClick={() => handleToolClick(tool.id)}
            onMouseEnter={() => setHoveredButton(tool.id)}
            onMouseLeave={() => setHoveredButton(null)}
            title={tool.tooltip}
          >
            {tool.icon}
          </button>
        ))}
      </div>
      
      <div style={styles.separator} />
      
      <div style={styles.toolGroup}>
        <span style={styles.label}>创建:</span>
        {creationTools.map((tool) => (
          <button
            key={tool.id}
            style={getButtonStyle(tool.id, hoveredButton === `create_${tool.id}`)}
            onClick={() => handleToolClick(tool.id)}
            onMouseEnter={() => setHoveredButton(`create_${tool.id}`)}
            onMouseLeave={() => setHoveredButton(null)}
            title={tool.tooltip}
          >
            <span style={{ marginRight: '4px' }}>{tool.icon}</span>
            <span style={{ fontSize: '12px' }}>{tool.label}</span>
          </button>
        ))}
      </div>
      
      <div style={styles.separator} />
      
      <div style={styles.toolGroup}>
        <button
          style={styles.button}
          onClick={handleDelete}
          disabled={!selectedObjectId}
          title="删除选中对象 (Delete)"
        >
          🗑️
        </button>
        <button
          style={styles.button}
          onClick={handleDuplicate}
          disabled={!selectedObjectId}
          title="复制选中对象 (Ctrl+D)"
        >
          📋
        </button>
        <button
          style={styles.button}
          onClick={handleFocus}
          disabled={!selectedObjectId}
          title="聚焦到选中对象 (F)"
        >
          🔍
        </button>
        <button
          style={styles.button}
          onClick={handleGridToggle}
          title="显示/隐藏网格"
        >
          📐
        </button>
      </div>
      
      <div style={styles.spacer} />
      
      <button
        style={styles.disconnectButton}
        onClick={onDisconnect}
      >
        断开连接
      </button>
    </div>
  );
}

function useState(initialValue) {
  return React.useState(initialValue);
}

export default Toolbar;
