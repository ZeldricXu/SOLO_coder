import React, { useState } from 'react';
import { useDrag } from 'react-dnd';
import { PhysicsObject, vec3, generateId, MATERIALS } from '@physics-sim/shared';
import { useLegacySimulationStore, getLegacyState } from '../store';

const OBJECT_TEMPLATES = [
  { type: 'box', name: '方块', icon: '⬜', defaultSize: { x: 1, y: 1, z: 1 } },
  { type: 'sphere', name: '球体', icon: '⚪', defaultRadius: 0.5 },
  { type: 'cylinder', name: '圆柱体', icon: '🔘', defaultRadius: 0.5, defaultHeight: 1 },
  { type: 'plane', name: '平面', icon: '⬛', defaultSize: { x: 10, y: 10, z: 0 } },
  { type: 'incline', name: '斜面', icon: '📐', defaultSize: { x: 5, y: 0.2, z: 3 }, defaultAngle: 0.3 },
  { type: 'charge', name: '电荷', icon: '⚡', defaultRadius: 0.3, defaultCharge: 1e-9 },
  { type: 'magnet', name: '磁铁', icon: '🧲', defaultSize: { x: 1, y: 0.3, z: 0.5 } },
  { type: 'spring', name: '弹簧', icon: '🔗', defaultRadius: 0.1, defaultRestLength: 1 },
];

interface DraggableObjectProps {
  template: typeof OBJECT_TEMPLATES[0];
}

const DraggableObject: React.FC<DraggableObjectProps> = ({ template }) => {
  const [{ isDragging }, drag] = useDrag(() => ({
    type: 'physicsObject',
    item: { template },
    collect: (monitor) => ({
      isDragging: monitor.isDragging(),
    }),
  }));

  return (
    <div
      ref={drag}
      style={{
        padding: '12px 15px',
        background: isDragging ? '#4ecdc4' : '#3a3a3a',
        borderRadius: 8,
        cursor: 'grab',
        opacity: isDragging ? 0.5 : 1,
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        transition: 'all 0.2s',
      }}
      onMouseEnter={(e) => {
        if (!isDragging) {
          e.currentTarget.style.background = '#4a4a4a';
        }
      }}
      onMouseLeave={(e) => {
        if (!isDragging) {
          e.currentTarget.style.background = '#3a3a3a';
        }
      }}
    >
      <span style={{ fontSize: 20 }}>{template.icon}</span>
      <span style={{ color: 'white', fontSize: 14 }}>{template.name}</span>
    </div>
  );
};

const SceneEditor: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'objects' | 'sensors' | 'settings'>('objects');
  const {
    engine,
    selectedObjectId,
    showGrid,
    showAxes,
    showForces,
    showTrajectories,
    addPhysicsObject,
    removePhysicsObject,
    toggleGrid,
    toggleAxes,
    toggleForces,
    toggleTrajectories,
  } = useLegacySimulationStore();

  const state = engine?.getState();
  const objects = state?.objects || new Map();
  const selectedObject = selectedObjectId ? objects.get(selectedObjectId) : null;

  const handleAddObject = (template: typeof OBJECT_TEMPLATES[0]) => {
    const baseObj: any = {
      id: generateId(),
      objectType: template.type,
      position: vec3(0, 2, 0),
      rotation: vec3(0, 0, 0),
      velocity: vec3(0, 0, 0),
      angularVelocity: vec3(0, 0, 0),
      materialId: 'aluminum',
      isStatic: template.type === 'plane',
      sensorType: undefined,
    };

    if ('defaultSize' in template) {
      baseObj.size = template.defaultSize;
    }
    if ('defaultRadius' in template) {
      baseObj.radius = template.defaultRadius;
    }
    if ('defaultHeight' in template) {
      baseObj.height = template.defaultHeight;
    }
    if ('defaultAngle' in template) {
      baseObj.angle = template.defaultAngle;
    }
    if ('defaultCharge' in template) {
      baseObj.charge = template.defaultCharge;
    }
    if ('defaultRestLength' in template) {
      baseObj.restLength = template.defaultRestLength;
      baseObj.stiffness = 100;
      baseObj.damping = 0.5;
    }

    addPhysicsObject(baseObj as PhysicsObject);
  };

  return (
    <div style={{
      width: 280,
      height: '100%',
      background: '#1e1e1e',
      borderRight: '1px solid #333',
      display: 'flex',
      flexDirection: 'column',
      overflow: 'hidden',
    }}>
      <div style={{
        padding: '15px 20px',
        background: '#2a2a2a',
        borderBottom: '1px solid #333',
      }}>
        <h2 style={{ color: 'white', fontSize: 18, margin: 0 }}>场景编辑器</h2>
      </div>
      
      <div style={{
        display: 'flex',
        borderBottom: '1px solid #333',
      }}>
        {(['objects', 'sensors', 'settings'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            style={{
              flex: 1,
              padding: '10px',
              border: 'none',
              background: activeTab === tab ? '#3a3a3a' : 'transparent',
              color: activeTab === tab ? '#4ecdc4' : '#aaa',
              cursor: 'pointer',
              fontSize: 13,
              transition: 'all 0.2s',
            }}
          >
            {tab === 'objects' ? '物体' : tab === 'sensors' ? '传感器' : '设置'}
          </button>
        ))}
      </div>
      
      <div style={{ flex: 1, overflowY: 'auto', padding: 15 }}>
        {activeTab === 'objects' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <h3 style={{ color: '#aaa', fontSize: 12, textTransform: 'uppercase', margin: '0 0 10px 0' }}>
              添加物体
            </h3>
            {OBJECT_TEMPLATES.map((template) => (
              <div key={template.type} onClick={() => handleAddObject(template)}>
                <DraggableObject template={template} />
              </div>
            ))}
            
            <h3 style={{ color: '#aaa', fontSize: 12, textTransform: 'uppercase', margin: '20px 0 10px 0' }}>
              场景物体 ({objects.size})
            </h3>
            {Array.from(objects.entries()).map(([id, obj]) => (
              <div
                key={id}
                onClick={() => getLegacyState().selectObject(id)}
                style={{
                  padding: '10px 12px',
                  background: selectedObjectId === id ? '#4ecdc4' : '#3a3a3a',
                  borderRadius: 6,
                  cursor: 'pointer',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: 5,
                }}
              >
                <span style={{ color: 'white', fontSize: 13 }}>
                  {obj.objectType}
                </span>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    removePhysicsObject(id);
                  }}
                  style={{
                    padding: '2px 8px',
                    border: 'none',
                    borderRadius: 4,
                    background: '#ff6b6b',
                    color: 'white',
                    cursor: 'pointer',
                    fontSize: 11,
                  }}
                >
                  ×
                </button>
              </div>
            ))}
          </div>
        )}
        
        {activeTab === 'sensors' && (
          <div style={{ color: '#aaa', fontSize: 14 }}>
            <p>传感器功能开发中...</p>
          </div>
        )}
        
        {activeTab === 'settings' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 15 }}>
            <h3 style={{ color: '#aaa', fontSize: 12, textTransform: 'uppercase', margin: 0 }}>
              显示设置
            </h3>
            
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'white', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={showGrid}
                onChange={toggleGrid}
                style={{ width: 18, height: 18 }}
              />
              <span>显示网格</span>
            </label>
            
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'white', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={showAxes}
                onChange={toggleAxes}
                style={{ width: 18, height: 18 }}
              />
              <span>显示坐标轴</span>
            </label>
            
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'white', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={showForces}
                onChange={toggleForces}
                style={{ width: 18, height: 18 }}
              />
              <span>显示力矢量</span>
            </label>
            
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'white', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={showTrajectories}
                onChange={toggleTrajectories}
                style={{ width: 18, height: 18 }}
              />
              <span>显示轨迹</span>
            </label>
          </div>
        )}
      </div>
      
      {selectedObject && (
        <div style={{
          padding: 15,
          background: '#2a2a2a',
          borderTop: '1px solid #333',
        }}>
          <h3 style={{ color: '#4ecdc4', fontSize: 14, margin: '0 0 10px 0' }}>
            属性: {selectedObject.objectType}
          </h3>
          <div style={{ color: '#aaa', fontSize: 12, fontFamily: 'monospace' }}>
            <div>位置: ({selectedObject.position.x.toFixed(2)}, {selectedObject.position.y.toFixed(2)}, {selectedObject.position.z.toFixed(2)})</div>
            <div>材料: {MATERIALS[selectedObject.materialId]?.name || selectedObject.materialId}</div>
            {selectedObject.isStatic && <div style={{ color: '#ff6b6b' }}>静态物体</div>}
          </div>
        </div>
      )}
    </div>
  );
};

export default SceneEditor;
