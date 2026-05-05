import useStore from '../../../store';
import operationProcessor from '../OperationProcessor';

jest.mock('../../../store', () => {
  const mockObjects = new Map();
  let mockSelectedObjectId = null;
  let mockCurrentVersion = 0;
  
  return {
    __esModule: true,
    default: {
      getState: jest.fn(() => ({
        objects: mockObjects,
        selectedObjectId: mockSelectedObjectId,
        currentVersion: mockCurrentVersion,
        getObject: jest.fn((id) => mockObjects.get(id)),
        addObject: jest.fn((obj) => {
          mockObjects.set(obj.object_id, obj);
        }),
        updateObject: jest.fn((id, updates) => {
          const existing = mockObjects.get(id);
          if (existing) {
            mockObjects.set(id, { ...existing, ...updates });
          }
        }),
        deleteObject: jest.fn((id) => {
          mockObjects.delete(id);
        }),
        setCurrentVersion: jest.fn((v) => {
          mockCurrentVersion = v;
        }),
        selectObject: jest.fn((id) => {
          mockSelectedObjectId = id;
        }),
        clearSelection: jest.fn(() => {
          mockSelectedObjectId = null;
        }),
        getAllObjects: jest.fn(() => Array.from(mockObjects.values()))
      })),
      subscribe: jest.fn(),
      _mockObjects: mockObjects,
      _reset: jest.fn(() => {
        mockObjects.clear();
        mockSelectedObjectId = null;
        mockCurrentVersion = 0;
      })
    }
  };
});

describe('OperationProcessor', () => {
  beforeEach(() => {
    useStore._reset();
  });
  
  describe('Operation Type Handlers', () => {
    describe('object_create', () => {
      it('should create an object with valid parameters', () => {
        const operation = {
          operation_type: 'object_create',
          object_id: 'obj_wall_001',
          object_type: 'wall',
          parameters: {
            transform: {
              position: { x: 0, y: 1.5, z: 0 },
              rotation: { x: 0, y: 0, z: 0 },
              scale: { x: 5, y: 3, z: 0.2 }
            },
            material_id: 'mat_concrete_01'
          },
          version: 1
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const createdObject = state.getObject('obj_wall_001');
        
        expect(createdObject).toBeDefined();
        expect(createdObject.object_id).toBe('obj_wall_001');
        expect(createdObject.object_type).toBe('wall');
        expect(createdObject.transform.position).toEqual({ x: 0, y: 1.5, z: 0 });
        expect(createdObject.material_id).toBe('mat_concrete_01');
        expect(createdObject.version).toBe(1);
      });
      
      it('should use default transform when not provided', () => {
        const operation = {
          operation_type: 'object_create',
          object_id: 'obj_furniture_001',
          object_type: 'furniture',
          parameters: {
            material_id: 'mat_default_01'
          },
          version: 1
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const createdObject = state.getObject('obj_furniture_001');
        
        expect(createdObject.transform.position).toEqual({ x: 0, y: 0, z: 0 });
        expect(createdObject.transform.rotation).toEqual({ x: 0, y: 0, z: 0 });
        expect(createdObject.transform.scale).toEqual({ x: 1, y: 1, z: 1 });
      });
      
      it('should create object with asset_id', () => {
        const operation = {
          operation_type: 'object_create',
          object_id: 'obj_furniture_002',
          object_type: 'furniture',
          parameters: {
            transform: {
              position: { x: 5, y: 0, z: 5 },
              rotation: { x: 0, y: Math.PI / 4, z: 0 },
              scale: { x: 1, y: 1, z: 1 }
            },
            asset_id: 'furniture_chair_01'
          },
          version: 2
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const createdObject = state.getObject('obj_furniture_002');
        
        expect(createdObject.asset_id).toBe('furniture_chair_01');
      });
    });
    
    describe('transform_update', () => {
      beforeEach(() => {
        const state = useStore.getState();
        state.addObject({
          object_id: 'obj_wall_001',
          object_type: 'wall',
          transform: {
            position: { x: 0, y: 1.5, z: 0 },
            rotation: { x: 0, y: 0, z: 0 },
            scale: { x: 5, y: 3, z: 0.2 }
          },
          material_id: 'mat_concrete_01',
          version: 1
        });
      });
      
      it('should update position', () => {
        const operation = {
          operation_type: 'transform_update',
          object_id: 'obj_wall_001',
          parameters: {
            transform: {
              position: { x: 10, y: 1.5, z: 0 }
            }
          },
          version: 2
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const updatedObject = state.getObject('obj_wall_001');
        
        expect(updatedObject.transform.position).toEqual({ x: 10, y: 1.5, z: 0 });
        expect(updatedObject.transform.rotation).toEqual({ x: 0, y: 0, z: 0 });
        expect(updatedObject.transform.scale).toEqual({ x: 5, y: 3, z: 0.2 });
        expect(updatedObject.version).toBe(2);
      });
      
      it('should update rotation', () => {
        const operation = {
          operation_type: 'transform_update',
          object_id: 'obj_wall_001',
          parameters: {
            transform: {
              rotation: { x: 0, y: Math.PI / 2, z: 0 }
            }
          },
          version: 3
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const updatedObject = state.getObject('obj_wall_001');
        
        expect(updatedObject.transform.rotation.y).toBe(Math.PI / 2);
      });
      
      it('should update scale', () => {
        const operation = {
          operation_type: 'transform_update',
          object_id: 'obj_wall_001',
          parameters: {
            transform: {
              scale: { x: 10, y: 4, z: 0.3 }
            }
          },
          version: 4
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const updatedObject = state.getObject('obj_wall_001');
        
        expect(updatedObject.transform.scale).toEqual({ x: 10, y: 4, z: 0.3 });
      });
      
      it('should update multiple transform properties at once', () => {
        const operation = {
          operation_type: 'transform_update',
          object_id: 'obj_wall_001',
          parameters: {
            transform: {
              position: { x: 10, y: 2, z: 5 },
              rotation: { x: 0, y: Math.PI / 4, z: 0 },
              scale: { x: 10, y: 4, z: 0.3 }
            }
          },
          version: 5
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const updatedObject = state.getObject('obj_wall_001');
        
        expect(updatedObject.transform.position).toEqual({ x: 10, y: 2, z: 5 });
        expect(updatedObject.transform.rotation.y).toBe(Math.PI / 4);
        expect(updatedObject.transform.scale).toEqual({ x: 10, y: 4, z: 0.3 });
      });
      
      it('should return false when object does not exist', () => {
        const operation = {
          operation_type: 'transform_update',
          object_id: 'obj_nonexistent_001',
          parameters: {
            transform: {
              position: { x: 10, y: 1.5, z: 0 }
            }
          },
          version: 6
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(false);
      });
      
      it('should return false when no transform in parameters', () => {
        const operation = {
          operation_type: 'transform_update',
          object_id: 'obj_wall_001',
          parameters: {},
          version: 7
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(false);
      });
    });
    
    describe('object_delete', () => {
      beforeEach(() => {
        const state = useStore.getState();
        state.addObject({
          object_id: 'obj_wall_001',
          object_type: 'wall',
          transform: {
            position: { x: 0, y: 1.5, z: 0 },
            rotation: { x: 0, y: 0, z: 0 },
            scale: { x: 5, y: 3, z: 0.2 }
          },
          material_id: 'mat_concrete_01',
          version: 1
        });
      });
      
      it('should delete existing object', () => {
        let state = useStore.getState();
        expect(state.getObject('obj_wall_001')).toBeDefined();
        
        const operation = {
          operation_type: 'object_delete',
          object_id: 'obj_wall_001',
          parameters: {},
          version: 2
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        state = useStore.getState();
        expect(state.getObject('obj_wall_001')).toBeUndefined();
      });
      
      it('should return false when deleting non-existent object', () => {
        const operation = {
          operation_type: 'object_delete',
          object_id: 'obj_nonexistent_001',
          parameters: {},
          version: 3
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(false);
      });
    });
    
    describe('material_update', () => {
      beforeEach(() => {
        const state = useStore.getState();
        state.addObject({
          object_id: 'obj_wall_001',
          object_type: 'wall',
          transform: {
            position: { x: 0, y: 1.5, z: 0 },
            rotation: { x: 0, y: 0, z: 0 },
            scale: { x: 5, y: 3, z: 0.2 }
          },
          material_id: 'mat_concrete_01',
          version: 1
        });
      });
      
      it('should update material', () => {
        const operation = {
          operation_type: 'material_update',
          object_id: 'obj_wall_001',
          parameters: {
            material_id: 'mat_brick_01'
          },
          version: 2
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(true);
        
        const state = useStore.getState();
        const updatedObject = state.getObject('obj_wall_001');
        
        expect(updatedObject.material_id).toBe('mat_brick_01');
        expect(updatedObject.version).toBe(2);
      });
      
      it('should return false when object does not exist', () => {
        const operation = {
          operation_type: 'material_update',
          object_id: 'obj_nonexistent_001',
          parameters: {
            material_id: 'mat_brick_01'
          },
          version: 3
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(false);
      });
      
      it('should return false when no material_id in parameters', () => {
        const operation = {
          operation_type: 'material_update',
          object_id: 'obj_wall_001',
          parameters: {},
          version: 4
        };
        
        const result = operationProcessor.process(operation);
        
        expect(result).toBe(false);
      });
    });
  });
  
  describe('Operation Creation', () => {
    describe('createOperationCreate', () => {
      it('should create operation with all parameters', () => {
        const objectData = {
          object_type: 'wall',
          transform: {
            position: { x: 0, y: 1.5, z: 0 },
            rotation: { x: 0, y: 0, z: 0 },
            scale: { x: 5, y: 3, z: 0.2 }
          },
          material_id: 'mat_concrete_01',
          asset_id: null
        };
        
        const operation = operationProcessor.createOperationCreate(objectData);
        
        expect(operation.operation_type).toBe('object_create');
        expect(operation.object_type).toBe('wall');
        expect(operation.parameters.transform).toEqual(objectData.transform);
        expect(operation.parameters.material_id).toBe('mat_concrete_01');
      });
    });
    
    describe('createOperationTransform', () => {
      it('should return null when no changes', () => {
        const original = {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        };
        
        const newTransform = {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        };
        
        const operation = operationProcessor.createOperationTransform(
          'obj_wall_001',
          original,
          newTransform
        );
        
        expect(operation).toBeNull();
      });
      
      it('should create operation with only changed properties', () => {
        const original = {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        };
        
        const newTransform = {
          position: { x: 10, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        };
        
        const operation = operationProcessor.createOperationTransform(
          'obj_wall_001',
          original,
          newTransform
        );
        
        expect(operation).not.toBeNull();
        expect(operation.operation_type).toBe('transform_update');
        expect(operation.target_object).toBe('obj_wall_001');
        expect(operation.parameters.transform.position).toEqual({ x: 10, y: 1.5, z: 0 });
        expect(operation.parameters.transform.rotation).toBeUndefined();
        expect(operation.parameters.transform.scale).toBeUndefined();
      });
    });
    
    describe('createOperationDelete', () => {
      it('should create delete operation', () => {
        const operation = operationProcessor.createOperationDelete('obj_wall_001');
        
        expect(operation.operation_type).toBe('object_delete');
        expect(operation.target_object).toBe('obj_wall_001');
        expect(operation.parameters).toEqual({});
      });
    });
    
    describe('createOperationMaterial', () => {
      it('should create material update operation', () => {
        const operation = operationProcessor.createOperationMaterial(
          'obj_wall_001',
          'mat_brick_01'
        );
        
        expect(operation.operation_type).toBe('material_update');
        expect(operation.target_object).toBe('obj_wall_001');
        expect(operation.parameters.material_id).toBe('mat_brick_01');
      });
    });
  });
  
  describe('Vector Comparison', () => {
    it('should return true for identical vectors', () => {
      const vec1 = { x: 1.0, y: 2.0, z: 3.0 };
      const vec2 = { x: 1.0, y: 2.0, z: 3.0 };
      
      const result = operationProcessor.areVectorsEqual(vec1, vec2);
      
      expect(result).toBe(true);
    });
    
    it('should return false for different vectors', () => {
      const vec1 = { x: 1.0, y: 2.0, z: 3.0 };
      const vec2 = { x: 1.0, y: 2.0, z: 4.0 };
      
      const result = operationProcessor.areVectorsEqual(vec1, vec2);
      
      expect(result).toBe(false);
    });
    
    it('should handle floating point tolerance', () => {
      const vec1 = { x: 1.0, y: 2.0, z: 3.0 };
      const vec2 = { x: 1.0001, y: 2.0001, z: 3.0001 };
      
      const result = operationProcessor.areVectorsEqual(vec1, vec2);
      
      expect(result).toBe(true);
    });
    
    it('should return false for null vectors', () => {
      const result = operationProcessor.areVectorsEqual(null, { x: 0, y: 0, z: 0 });
      expect(result).toBe(false);
    });
    
    it('should return false for undefined vectors', () => {
      const result = operationProcessor.areVectorsEqual(undefined, { x: 0, y: 0, z: 0 });
      expect(result).toBe(false);
    });
  });
  
  describe('Delta Calculation', () => {
    it('should calculate delta when position changes', () => {
      const original = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const updated = {
        position: { x: 10, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const delta = operationProcessor.calculateTransformDelta(original, updated);
      
      expect(delta.position).toEqual({ x: 10, y: 0, z: 0 });
      expect(delta.rotation).toBeUndefined();
      expect(delta.scale).toBeUndefined();
    });
    
    it('should calculate delta when rotation changes', () => {
      const original = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const updated = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: Math.PI / 2, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const delta = operationProcessor.calculateTransformDelta(original, updated);
      
      expect(delta.rotation).toEqual({ x: 0, y: Math.PI / 2, z: 0 });
      expect(delta.position).toBeUndefined();
      expect(delta.scale).toBeUndefined();
    });
    
    it('should return empty delta when no changes', () => {
      const original = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const updated = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const delta = operationProcessor.calculateTransformDelta(original, updated);
      
      expect(Object.keys(delta).length).toBe(0);
    });
  });
  
  describe('Handler Registration', () => {
    it('should register custom handler', () => {
      const customHandler = jest.fn(() => true);
      operationProcessor.registerHandler('custom_operation', customHandler);
      
      const operation = {
        operation_type: 'custom_operation',
        parameters: { test: true }
      };
      
      operationProcessor.process(operation);
      
      expect(customHandler).toHaveBeenCalled();
    });
    
    it('should throw error when handler is not a function', () => {
      expect(() => {
        operationProcessor.registerHandler('invalid', 'not a function');
      }).toThrow();
    });
    
    it('should check if handler exists', () => {
      expect(operationProcessor.hasHandler('object_create')).toBe(true);
      expect(operationProcessor.hasHandler('nonexistent')).toBe(false);
    });
    
    it('should get registered types', () => {
      const types = operationProcessor.getRegisteredTypes();
      
      expect(Array.isArray(types)).toBe(true);
      expect(types).toContain('object_create');
      expect(types).toContain('transform_update');
      expect(types).toContain('object_delete');
      expect(types).toContain('material_update');
    });
  });
  
  describe('Batch Processing', () => {
    beforeEach(() => {
      const state = useStore.getState();
      state.addObject({
        object_id: 'obj_wall_001',
        object_type: 'wall',
        transform: {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        },
        material_id: 'mat_concrete_01',
        version: 1
      });
    });
    
    it('should process multiple operations', () => {
      const operations = [
        {
          operation_type: 'object_create',
          object_id: 'obj_door_001',
          object_type: 'door',
          parameters: {
            transform: {
              position: { x: 5, y: 1.05, z: 0 },
              rotation: { x: 0, y: 0, z: 0 },
              scale: { x: 0.9, y: 2.1, z: 0.1 }
            }
          },
          version: 2
        },
        {
          operation_type: 'transform_update',
          object_id: 'obj_wall_001',
          parameters: {
            transform: {
              position: { x: 10, y: 1.5, z: 0 }
            }
          },
          version: 3
        }
      ];
      
      const result = operationProcessor.batchProcess(operations);
      
      expect(result).toBe(true);
      
      const state = useStore.getState();
      expect(state.getObject('obj_door_001')).toBeDefined();
      expect(state.getObject('obj_wall_001').transform.position.x).toBe(10);
    });
    
    it('should return false when any operation fails', () => {
      const operations = [
        {
          operation_type: 'object_create',
          object_id: 'obj_door_002',
          object_type: 'door',
          parameters: {
            transform: {
              position: { x: 5, y: 1.05, z: 0 },
              rotation: { x: 0, y: 0, z: 0 },
              scale: { x: 0.9, y: 2.1, z: 0.1 }
            }
          },
          version: 2
        },
        {
          operation_type: 'transform_update',
          object_id: 'obj_nonexistent_999',
          parameters: {
            transform: {
              position: { x: 10, y: 1.5, z: 0 }
            }
          },
          version: 3
        }
      ];
      
      const result = operationProcessor.batchProcess(operations);
      
      expect(result).toBe(false);
    });
  });
  
  describe('Unknown Operation Handling', () => {
    it('should return false for unknown operation type', () => {
      const operation = {
        operation_type: 'unknown_operation_type',
        parameters: { test: true }
      };
      
      const result = operationProcessor.process(operation);
      
      expect(result).toBe(false);
    });
  });
  
  describe('Error Handling', () => {
    it('should catch and return false on handler errors', () => {
      const errorHandler = jest.fn(() => {
        throw new Error('Test error');
      });
      operationProcessor.registerHandler('error_operation', errorHandler);
      
      const operation = {
        operation_type: 'error_operation',
        parameters: {}
      };
      
      const result = operationProcessor.process(operation);
      
      expect(result).toBe(false);
    });
  });
});
