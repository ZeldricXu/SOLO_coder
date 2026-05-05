import useStore from '../store';
import syncClientV2 from './sync/SyncClientV2';
import sceneManager from './renderer/SceneManager';
import factoryRegistry from './geometry/FactoryRegistry';

class GeometryEditor {
  constructor() {
    this.store = useStore;
    this.originalTransforms = new Map();
  }
  
  createObject(objectType, position = { x: 0, y: 0, z: 0 }, options = {}) {
    const store = this.store.getState();
    
    const defaultTransform = factoryRegistry.getDefaultTransform(objectType);
    
    const objectData = {
      object_type: objectType,
      transform: {
        position: position,
        rotation: defaultTransform.rotation,
        scale: defaultTransform.scale
      },
      material_id: options.material_id || 'mat_default_01',
      asset_id: options.asset_id,
      creator_id: store.userId
    };
    
    store.addObject(objectData);
    
    syncClientV2.sendCreateOperation(objectData);
    
    return objectData;
  }
  
  startTransformEdit(objectId) {
    const store = this.store.getState();
    const object = store.getObject(objectId);
    
    if (object) {
      this.originalTransforms.set(objectId, {
        ...object.transform
      });
    }
  }
  
  commitTransformEdit(objectId) {
    const original = this.originalTransforms.get(objectId);
    if (!original) return;
    
    const store = this.store.getState();
    const current = store.getObject(objectId);
    
    if (!current) {
      this.originalTransforms.delete(objectId);
      return;
    }
    
    const operation = syncClientV2.sendTransformOperation(
      objectId,
      original,
      current.transform
    );
    
    this.originalTransforms.delete(objectId);
    
    return operation;
  }
  
  updateTransform(objectId, transform, sendToServer = true) {
    const store = this.store.getState();
    const existing = store.getObject(objectId);
    
    if (!existing) {
      console.warn(`Object not found: ${objectId}`);
      return;
    }
    
    const updatedTransform = {
      ...existing.transform,
      ...transform
    };
    
    store.updateObject(objectId, { transform: updatedTransform });
    
    if (sendToServer) {
      syncClientV2.sendTransformOperation(
        objectId,
        existing.transform,
        updatedTransform
      );
    }
  }
  
  updatePosition(objectId, position, sendToServer = true) {
    this.updateTransform(objectId, { position }, sendToServer);
  }
  
  updateRotation(objectId, rotation, sendToServer = true) {
    this.updateTransform(objectId, { rotation }, sendToServer);
  }
  
  updateScale(objectId, scale, sendToServer = true) {
    this.updateTransform(objectId, { scale }, sendToServer);
  }
  
  deleteObject(objectId, sendToServer = true) {
    const store = this.store.getState();
    
    store.deleteObject(objectId);
    
    if (sendToServer) {
      syncClientV2.sendDeleteOperation(objectId);
    }
  }
  
  duplicateObject(objectId) {
    const store = this.store.getState();
    const existing = store.getObject(objectId);
    
    if (!existing) {
      console.warn(`Object not found: ${objectId}`);
      return null;
    }
    
    const newPosition = {
      x: existing.transform.position.x + 1,
      y: existing.transform.position.y,
      z: existing.transform.position.z + 1
    };
    
    return this.createObject(
      existing.object_type,
      newPosition,
      {
        material_id: existing.material_id,
        asset_id: existing.asset_id
      }
    );
  }
  
  selectObject(objectId) {
    const store = this.store.getState();
    store.selectObject(objectId);
    
    const mesh = sceneManager.meshesMap.get(objectId);
    if (mesh) {
      sceneManager.selectMesh(mesh);
    }
    
    this.startTransformEdit(objectId);
  }
  
  deselectAll() {
    const store = this.store.getState();
    
    if (store.selectedObjectId) {
      this.commitTransformEdit(store.selectedObjectId);
    }
    
    store.clearSelection();
    sceneManager.deselectMesh();
  }
  
  setActiveTool(tool) {
    const store = this.store.getState();
    
    if (store.selectedObjectId && 
        (tool === 'select' || !['translate', 'rotate', 'scale'].includes(tool))) {
      this.commitTransformEdit(store.selectedObjectId);
    }
    
    store.setActiveTool(tool);
    
    if (tool === 'translate') {
      sceneManager.setTransformMode('translate');
    } else if (tool === 'rotate') {
      sceneManager.setTransformMode('rotate');
    } else if (tool === 'scale') {
      sceneManager.setTransformMode('scale');
    }
  }
  
  getSelectedObject() {
    const store = this.store.getState();
    if (store.selectedObjectId) {
      return store.getObject(store.selectedObjectId);
    }
    return null;
  }
  
  getAllObjects() {
    const store = this.store.getState();
    return store.getAllObjects();
  }
  
  createWall(start, end, height = 3, thickness = 0.2) {
    const dx = end.x - start.x;
    const dz = end.z - start.z;
    const length = Math.sqrt(dx * dx + dz * dz);
    
    if (length < 0.1) {
      console.warn('Wall too short');
      return null;
    }
    
    const midX = (start.x + end.x) / 2;
    const midZ = (start.z + end.z) / 2;
    
    const angle = Math.atan2(dx, dz);
    
    return this.createObject('wall', { x: midX, y: height / 2, z: midZ }, {
      transform: {
        position: { x: midX, y: height / 2, z: midZ },
        rotation: { x: 0, y: angle, z: 0 },
        scale: { x: length, y: height, z: thickness }
      }
    });
  }
  
  alignObjects(objectIds, axis = 'x', mode = 'center') {
    if (objectIds.length < 2) return;
    
    const store = this.store.getState();
    const objects = objectIds.map(id => store.getObject(id)).filter(Boolean);
    
    if (objects.length < 2) return;
    
    let targetValue;
    
    if (mode === 'min') {
      targetValue = Math.min(...objects.map(o => o.transform.position[axis]));
    } else if (mode === 'max') {
      targetValue = Math.max(...objects.map(o => o.transform.position[axis]));
    } else {
      const sum = objects.reduce((acc, o) => acc + o.transform.position[axis], 0);
      targetValue = sum / objects.length;
    }
    
    objects.forEach(obj => {
      const newPosition = { ...obj.transform.position };
      newPosition[axis] = targetValue;
      this.updatePosition(obj.object_id, newPosition);
    });
  }
  
  distributeObjects(objectIds, axis = 'x') {
    if (objectIds.length < 3) return;
    
    const store = this.store.getState();
    const objects = objectIds.map(id => store.getObject(id)).filter(Boolean);
    
    if (objects.length < 3) return;
    
    const sorted = [...objects].sort((a, b) => 
      a.transform.position[axis] - b.transform.position[axis]
    );
    
    const min = sorted[0].transform.position[axis];
    const max = sorted[sorted.length - 1].transform.position[axis];
    const step = (max - min) / (sorted.length - 1);
    
    sorted.forEach((obj, index) => {
      const newPosition = { ...obj.transform.position };
      newPosition[axis] = min + step * index;
      this.updatePosition(obj.object_id, newPosition);
    });
  }
  
  getObjectTypeInfo(objectType) {
    return {
      type: objectType,
      hasFactory: factoryRegistry.hasFactory(objectType),
      defaultTransform: factoryRegistry.getDefaultTransform(objectType)
    };
  }
  
  getRegisteredObjectTypes() {
    return factoryRegistry.getRegisteredTypes();
  }
  
  createFromAsset(asset, position = { x: 0, y: 0, z: 0 }) {
    const objectType = asset.category === 'door' ? 'door' :
                       asset.category === 'window' ? 'window' :
                       asset.category === 'wall' ? 'wall' : 'furniture';
    
    return this.createObject(objectType, position, {
      material_id: asset.default_material_id,
      asset_id: asset.asset_id
    });
  }
}

const geometryEditor = new GeometryEditor();
export default geometryEditor;
