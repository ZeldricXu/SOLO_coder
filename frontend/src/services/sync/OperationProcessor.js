import useStore from '../../store';

class OperationProcessor {
  constructor() {
    this.operationHandlers = new Map();
    this.registerDefaultHandlers();
  }

  registerDefaultHandlers() {
    this.operationHandlers.set('object_create', this.handleObjectCreate.bind(this));
    this.operationHandlers.set('transform_update', this.handleTransformUpdate.bind(this));
    this.operationHandlers.set('object_delete', this.handleObjectDelete.bind(this));
    this.operationHandlers.set('material_update', this.handleMaterialUpdate.bind(this));
  }

  registerHandler(operationType, handler) {
    if (typeof handler !== 'function') {
      throw new Error('Handler must be a function');
    }
    this.operationHandlers.set(operationType, handler);
  }

  process(operation) {
    const { operation_type, object_id, parameters, version } = operation;
    
    const handler = this.operationHandlers.get(operation_type);
    
    if (!handler) {
      console.warn(`No handler found for operation type: ${operation_type}`);
      return false;
    }
    
    try {
      return handler({
        object_id,
        parameters,
        version,
        operation_type
      });
    } catch (error) {
      console.error(`Error processing operation ${operation_type}:`, error);
      return false;
    }
  }

  handleObjectCreate({ object_id, parameters, version }) {
    const store = useStore.getState();
    const { object_type, transform, material_id, asset_id } = parameters;
    
    const objectData = {
      object_id,
      object_type: object_type || 'furniture',
      transform: transform || {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      },
      material_id: material_id || 'mat_default_01',
      asset_id,
      version
    };
    
    store.addObject(objectData);
    
    console.log(`Object created: ${object_id}`);
    return true;
  }

  handleTransformUpdate({ object_id, parameters, version }) {
    const store = useStore.getState();
    const existing = store.getObject(object_id);
    
    if (!existing) {
      console.warn(`Object not found for transform update: ${object_id}`);
      return false;
    }
    
    const { transform } = parameters;
    
    if (!transform) {
      console.warn('No transform data in operation');
      return false;
    }
    
    const updatedTransform = {
      ...existing.transform
    };
    
    if (transform.position) {
      updatedTransform.position = {
        ...existing.transform.position,
        ...transform.position
      };
    }
    
    if (transform.rotation) {
      updatedTransform.rotation = {
        ...existing.transform.rotation,
        ...transform.rotation
      };
    }
    
    if (transform.scale) {
      updatedTransform.scale = {
        ...existing.transform.scale,
        ...transform.scale
      };
    }
    
    store.updateObject(object_id, {
      transform: updatedTransform,
      version
    });
    
    return true;
  }

  handleObjectDelete({ object_id, version }) {
    const store = useStore.getState();
    const existing = store.getObject(object_id);
    
    if (!existing) {
      console.warn(`Object not found for deletion: ${object_id}`);
      return false;
    }
    
    store.deleteObject(object_id);
    
    console.log(`Object deleted: ${object_id}`);
    return true;
  }

  handleMaterialUpdate({ object_id, parameters, version }) {
    const store = useStore.getState();
    const existing = store.getObject(object_id);
    
    if (!existing) {
      console.warn(`Object not found for material update: ${object_id}`);
      return false;
    }
    
    const { material_id } = parameters;
    
    if (!material_id) {
      console.warn('No material_id in operation');
      return false;
    }
    
    store.updateObject(object_id, {
      material_id,
      version
    });
    
    console.log(`Material updated for object: ${object_id} -> ${material_id}`);
    return true;
  }

  batchProcess(operations) {
    if (!Array.isArray(operations)) {
      return false;
    }
    
    let allSuccess = true;
    
    for (const operation of operations) {
      const success = this.process(operation);
      if (!success) {
        allSuccess = false;
        console.warn(`Operation failed: ${JSON.stringify(operation)}`);
      }
    }
    
    return allSuccess;
  }

  createOperationCreate(objectData) {
    return {
      operation_type: 'object_create',
      object_type: objectData.object_type,
      parameters: {
        transform: objectData.transform,
        material_id: objectData.material_id,
        asset_id: objectData.asset_id
      }
    };
  }

  createOperationTransform(objectId, originalTransform, newTransform) {
    const delta = this.calculateTransformDelta(originalTransform, newTransform);
    
    if (Object.keys(delta).length === 0) {
      return null;
    }
    
    return {
      operation_type: 'transform_update',
      target_object: objectId,
      parameters: {
        transform: delta
      }
    };
  }

  createOperationDelete(objectId) {
    return {
      operation_type: 'object_delete',
      target_object: objectId,
      parameters: {}
    };
  }

  createOperationMaterial(objectId, materialId) {
    return {
      operation_type: 'material_update',
      target_object: objectId,
      parameters: {
        material_id: materialId
      }
    };
  }

  calculateTransformDelta(original, updated) {
    const delta = {};
    
    if (!original || !updated) {
      return delta;
    }
    
    if (original.position && updated.position) {
      if (!this.areVectorsEqual(original.position, updated.position)) {
        delta.position = updated.position;
      }
    }
    
    if (original.rotation && updated.rotation) {
      if (!this.areVectorsEqual(original.rotation, updated.rotation)) {
        delta.rotation = updated.rotation;
      }
    }
    
    if (original.scale && updated.scale) {
      if (!this.areVectorsEqual(original.scale, updated.scale)) {
        delta.scale = updated.scale;
      }
    }
    
    return delta;
  }

  areVectorsEqual(vec1, vec2, tolerance = 0.001) {
    if (!vec1 || !vec2) return false;
    
    return Math.abs(vec1.x - vec2.x) < tolerance &&
           Math.abs(vec1.y - vec2.y) < tolerance &&
           Math.abs(vec1.z - vec2.z) < tolerance;
  }

  getRegisteredTypes() {
    return Array.from(this.operationHandlers.keys());
  }

  hasHandler(operationType) {
    return this.operationHandlers.has(operationType);
  }
}

const operationProcessor = new OperationProcessor();
export default operationProcessor;
