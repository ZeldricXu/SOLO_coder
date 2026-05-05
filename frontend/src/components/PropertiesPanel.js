import React, { useEffect, useState } from 'react';
import useStore from '../store';
import geometryEditor from '../services/geometryEditor';

const styles = {
  panel: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    overflow: 'hidden'
  },
  header: {
    padding: '12px 16px',
    borderBottom: '1px solid #0f3460',
    backgroundColor: '#0f3460'
  },
  title: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#e0e0e0',
    margin: 0
  },
  noSelection: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    flex: 1,
    color: '#666',
    fontSize: '13px',
    padding: '20px'
  },
  section: {
    padding: '16px',
    borderBottom: '1px solid #0f3460'
  },
  sectionTitle: {
    fontSize: '12px',
    fontWeight: 600,
    color: '#8892b0',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '12px'
  },
  propertyRow: {
    display: 'flex',
    alignItems: 'center',
    marginBottom: '10px'
  },
  propertyLabel: {
    width: '60px',
    fontSize: '12px',
    color: '#8892b0'
  },
  propertyValue: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    flex: 1
  },
  axisLabel: {
    width: '20px',
    textAlign: 'center',
    fontSize: '11px',
    color: '#e94560',
    fontWeight: 600
  },
  numberInput: {
    flex: 1,
    padding: '6px 8px',
    backgroundColor: '#0d1b2a',
    border: '1px solid #0f3460',
    borderRadius: '4px',
    color: '#e0e0e0',
    fontSize: '12px',
    textAlign: 'right',
    outline: 'none'
  },
  objectInfo: {
    display: 'flex',
    alignItems: 'center',
    marginBottom: '12px'
  },
  objectIcon: {
    width: '40px',
    height: '40px',
    backgroundColor: '#16213e',
    borderRadius: '8px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '20px',
    marginRight: '12px'
  },
  objectDetails: {
    flex: 1
  },
  objectName: {
    fontSize: '14px',
    color: '#e0e0e0',
    fontWeight: 500,
    marginBottom: '2px'
  },
  objectId: {
    fontSize: '11px',
    color: '#666',
    fontFamily: 'monospace'
  },
  toggleGroup: {
    display: 'flex',
    gap: '4px'
  },
  toggleButton: {
    flex: 1,
    padding: '8px 12px',
    fontSize: '12px',
    border: '1px solid #0f3460',
    backgroundColor: '#0d1b2a',
    color: '#8892b0',
    cursor: 'pointer',
    borderRadius: '4px',
    transition: 'all 0.2s'
  },
  toggleButtonActive: {
    backgroundColor: '#e94560',
    color: '#fff',
    borderColor: '#e94560'
  },
  materialSelect: {
    width: '100%',
    padding: '8px 12px',
    backgroundColor: '#0d1b2a',
    border: '1px solid #0f3460',
    borderRadius: '4px',
    color: '#e0e0e0',
    fontSize: '12px',
    outline: 'none',
    cursor: 'pointer'
  },
  actions: {
    display: 'flex',
    gap: '8px',
    padding: '16px'
  },
  actionButton: {
    flex: 1,
    padding: '10px 16px',
    borderRadius: '4px',
    border: '1px solid #0f3460',
    backgroundColor: '#0f3460',
    color: '#e0e0e0',
    cursor: 'pointer',
    fontSize: '12px',
    transition: 'all 0.2s'
  },
  deleteButton: {
    backgroundColor: '#dc3545',
    borderColor: '#dc3545'
  }
};

const materials = [
  { id: 'mat_default_01', name: '默认材质', color: '#666666' },
  { id: 'mat_concrete_01', name: '混凝土', color: '#808080' },
  { id: 'mat_wood_01', name: '木材', color: '#8B4513' },
  { id: 'mat_glass_01', name: '玻璃', color: '#87CEEB' },
  { id: 'mat_metal_01', name: '金属', color: '#C0C0C0' },
  { id: 'mat_brick_01', name: '砖块', color: '#B22222' }
];

function PropertiesPanel() {
  const { selectedObjectId, getObject } = useStore();
  const [selectedObject, setSelectedObject] = useState(null);
  const [localTransform, setLocalTransform] = useState(null);

  useEffect(() => {
    if (selectedObjectId) {
      const obj = getObject(selectedObjectId);
      setSelectedObject(obj);
      setLocalTransform(obj?.transform);
    } else {
      setSelectedObject(null);
      setLocalTransform(null);
    }
  }, [selectedObjectId, getObject]);

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

  const handleTransformChange = (type, axis, value) => {
    if (!selectedObject) return;
    
    const numValue = parseFloat(value) || 0;
    const updatedTransform = {
      ...localTransform,
      [type]: {
        ...localTransform[type],
        [axis]: numValue
      }
    };
    
    setLocalTransform(updatedTransform);
  };

  const handleTransformBlur = (type, axis) => {
    if (!selectedObject) return;
    geometryEditor.updateTransform(selectedObject.object_id, {
      [type]: localTransform[type]
    });
  };

  const handleMaterialChange = (materialId) => {
    if (!selectedObject) return;
    // TODO: Implement material update
  };

  const handleDelete = () => {
    if (!selectedObject) return;
    geometryEditor.deleteObject(selectedObject.object_id);
  };

  const handleDuplicate = () => {
    if (!selectedObject) return;
    geometryEditor.duplicateObject(selectedObject.object_id);
  };

  if (!selectedObject) {
    return (
      <div style={styles.panel}>
        <div style={styles.header}>
          <h3 style={styles.title}>属性</h3>
        </div>
        <div style={styles.noSelection}>
          选择一个对象以查看其属性
        </div>
      </div>
    );
  }

  return (
    <div style={styles.panel}>
      <div style={styles.header}>
        <h3 style={styles.title}>属性</h3>
      </div>
      
      <div style={styles.section}>
        <div style={styles.objectInfo}>
          <div style={styles.objectIcon}>
            {getObjectIcon(selectedObject.object_type)}
          </div>
          <div style={styles.objectDetails}>
            <div style={styles.objectName}>
              {getObjectTypeName(selectedObject.object_type)}
            </div>
            <div style={styles.objectId}>
              {selectedObject.object_id}
            </div>
          </div>
        </div>
        
        <div style={styles.propertyRow}>
          <span style={styles.propertyLabel}>版本</span>
          <span style={{ color: '#e0e0e0', fontSize: '12px' }}>
            v{selectedObject.version}
          </span>
        </div>
      </div>
      
      <div style={styles.section}>
        <div style={styles.sectionTitle}>变换</div>
        
        <div style={styles.sectionTitle} style={{ ...styles.sectionTitle, marginTop: '8px' }}>
          位置
        </div>
        {['x', 'y', 'z'].map((axis) => (
          <div key={axis} style={styles.propertyRow}>
            <span style={styles.axisLabel}>{axis.toUpperCase()}</span>
            <input
              type="number"
              step="0.1"
              style={styles.numberInput}
              value={localTransform?.position?.[axis] || 0}
              onChange={(e) => handleTransformChange('position', axis, e.target.value)}
              onBlur={() => handleTransformBlur('position', axis)}
            />
          </div>
        ))}
        
        <div style={styles.sectionTitle} style={{ ...styles.sectionTitle, marginTop: '16px' }}>
          旋转
        </div>
        {['x', 'y', 'z'].map((axis) => (
          <div key={axis} style={styles.propertyRow}>
            <span style={styles.axisLabel}>{axis.toUpperCase()}</span>
            <input
              type="number"
              step="0.1"
              style={styles.numberInput}
              value={localTransform?.rotation?.[axis] || 0}
              onChange={(e) => handleTransformChange('rotation', axis, e.target.value)}
              onBlur={() => handleTransformBlur('rotation', axis)}
            />
          </div>
        ))}
        
        <div style={styles.sectionTitle} style={{ ...styles.sectionTitle, marginTop: '16px' }}>
          缩放
        </div>
        {['x', 'y', 'z'].map((axis) => (
          <div key={axis} style={styles.propertyRow}>
            <span style={styles.axisLabel}>{axis.toUpperCase()}</span>
            <input
              type="number"
              step="0.1"
              style={styles.numberInput}
              value={localTransform?.scale?.[axis] || 1}
              onChange={(e) => handleTransformChange('scale', axis, e.target.value)}
              onBlur={() => handleTransformBlur('scale', axis)}
            />
          </div>
        ))}
      </div>
      
      <div style={styles.section}>
        <div style={styles.sectionTitle}>材质</div>
        <select
          style={styles.materialSelect}
          value={selectedObject.material_id || 'mat_default_01'}
          onChange={(e) => handleMaterialChange(e.target.value)}
        >
          {materials.map((mat) => (
            <option key={mat.id} value={mat.id}>
              {mat.name}
            </option>
          ))}
        </select>
      </div>
      
      <div style={styles.actions}>
        <button
          style={styles.actionButton}
          onClick={handleDuplicate}
        >
          📋 复制
        </button>
        <button
          style={{ ...styles.actionButton, ...styles.deleteButton }}
          onClick={handleDelete}
        >
          🗑️ 删除
        </button>
      </div>
    </div>
  );
}

export default PropertiesPanel;
