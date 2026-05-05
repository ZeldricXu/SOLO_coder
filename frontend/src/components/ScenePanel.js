import React, { useEffect, useState } from 'react';
import useStore from '../store';
import geometryEditor from '../services/geometryEditor';

const styles = {
  panel: {
    display: 'flex',
    flexDirection: 'column',
    height: '40%',
    minHeight: '200px',
    borderBottom: '1px solid #0f3460',
    overflow: 'hidden'
  },
  header: {
    padding: '12px 16px',
    borderBottom: '1px solid #0f3460',
    backgroundColor: '#0f3460',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  title: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#e0e0e0',
    margin: 0
  },
  count: {
    fontSize: '11px',
    color: '#8892b0',
    backgroundColor: '#16213e',
    padding: '2px 8px',
    borderRadius: '10px'
  },
  objectList: {
    flex: 1,
    overflowY: 'auto',
    padding: '4px'
  },
  objectItem: {
    display: 'flex',
    alignItems: 'center',
    padding: '8px 12px',
    borderRadius: '4px',
    cursor: 'pointer',
    marginBottom: '2px',
    transition: 'all 0.2s',
    backgroundColor: 'transparent'
  },
  objectItemHover: {
    backgroundColor: '#0f3460'
  },
  objectItemSelected: {
    backgroundColor: '#e9456020',
    border: '1px solid #e94560'
  },
  objectIcon: {
    width: '28px',
    height: '28px',
    backgroundColor: '#16213e',
    borderRadius: '6px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '14px',
    marginRight: '10px'
  },
  objectName: {
    flex: 1,
    fontSize: '12px',
    color: '#e0e0e0',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap'
  },
  objectType: {
    fontSize: '10px',
    color: '#8892b0',
    backgroundColor: '#0d1b2a',
    padding: '2px 6px',
    borderRadius: '4px'
  },
  empty: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    flex: 1,
    color: '#666',
    fontSize: '12px',
    padding: '20px'
  },
  toolbar: {
    display: 'flex',
    padding: '8px 12px',
    borderTop: '1px solid #0f3460',
    gap: '8px'
  },
  toolbarButton: {
    padding: '6px 12px',
    borderRadius: '4px',
    border: '1px solid #0f3460',
    backgroundColor: '#0f3460',
    color: '#e0e0e0',
    cursor: 'pointer',
    fontSize: '11px',
    transition: 'all 0.2s'
  },
  toolbarButtonDisabled: {
    opacity: 0.5,
    cursor: 'not-allowed'
  }
};

function ScenePanel() {
  const { objects, selectedObjectId, selectObject } = useStore();
  const [hoveredObject, setHoveredObject] = useState(null);
  const [objectArray, setObjectArray] = useState([]);

  useEffect(() => {
    const arr = Array.from(objects.values());
    setObjectArray(arr);
  }, [objects]);

  const getObjectIcon = (objectType) => {
    const icons = {
      wall: '🧱',
      door: '🚪',
      window: '🪟',
      furniture: '🪑'
    };
    return icons[objectType] || '📦';
  };

  const getObjectTypeName = (objectType) => {
    const names = {
      wall: '墙体',
      door: '门',
      window: '窗户',
      furniture: '家具'
    };
    return names[objectType] || '对象';
  };

  const getObjectDisplayName = (obj) => {
    if (obj.asset_id) {
      return obj.name || getObjectTypeName(obj.object_type);
    }
    const prefix = {
      wall: '墙体',
      door: '门',
      window: '窗户',
      furniture: '家具'
    }[obj.object_type] || '对象';
    
    const index = objectArray.findIndex(o => o.object_id === obj.object_id) + 1;
    return `${prefix} ${index}`;
  };

  const handleObjectClick = (objectId) => {
    if (selectedObjectId === objectId) {
      // Clear selection if clicking the same object
      return;
    }
    geometryEditor.selectObject(objectId);
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

  const handleClearAll = () => {
    if (window.confirm('确定要清空场景中的所有对象吗？')) {
      objectArray.forEach(obj => {
        geometryEditor.deleteObject(obj.object_id, false);
      });
    }
  };

  return (
    <div style={styles.panel}>
      <div style={styles.header}>
        <h3 style={styles.title}>场景对象</h3>
        <span style={styles.count}>{objectArray.length} 个对象</span>
      </div>
      
      <div style={styles.objectList}>
        {objectArray.length === 0 ? (
          <div style={styles.empty}>
            场景为空<br />使用创建工具添加对象
          </div>
        ) : (
          objectArray.map((obj) => (
            <div
              key={obj.object_id}
              style={{
                ...styles.objectItem,
                ...(hoveredObject === obj.object_id ? styles.objectItemHover : {}),
                ...(selectedObjectId === obj.object_id ? styles.objectItemSelected : {})
              }}
              onClick={() => handleObjectClick(obj.object_id)}
              onMouseEnter={() => setHoveredObject(obj.object_id)}
              onMouseLeave={() => setHoveredObject(null)}
            >
              <div style={styles.objectIcon}>
                {getObjectIcon(obj.object_type)}
              </div>
              <span style={styles.objectName}>
                {getObjectDisplayName(obj)}
              </span>
              <span style={styles.objectType}>
                {getObjectTypeName(obj.object_type)}
              </span>
            </div>
          ))
        )}
      </div>
      
      <div style={styles.toolbar}>
        <button
          style={{
            ...styles.toolbarButton,
            ...(!selectedObjectId ? styles.toolbarButtonDisabled : {})
          }}
          onClick={handleDuplicate}
          disabled={!selectedObjectId}
        >
          📋 复制
        </button>
        <button
          style={{
            ...styles.toolbarButton,
            ...(!selectedObjectId ? styles.toolbarButtonDisabled : {})
          }}
          onClick={handleDelete}
          disabled={!selectedObjectId}
        >
          🗑️ 删除
        </button>
        <button
          style={{
            ...styles.toolbarButton,
            ...(objectArray.length === 0 ? styles.toolbarButtonDisabled : {})
          }}
          onClick={handleClearAll}
          disabled={objectArray.length === 0}
        >
          清空场景
        </button>
      </div>
    </div>
  );
}

export default ScenePanel;
