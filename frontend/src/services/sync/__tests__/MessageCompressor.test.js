import messageCompressor from '../MessageCompressor';

describe('MessageCompressor', () => {
  describe('Field Alias Compression', () => {
    it('should compress object_id to oid', () => {
      const original = { object_id: 'obj_wall_001' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.oid).toBe('obj_wall_001');
      expect(compressed.object_id).toBeUndefined();
    });
    
    it('should compress transform to t', () => {
      const original = {
        transform: {
          position: { x: 0, y: 0, z: 0 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t).toBeDefined();
      expect(compressed.transform).toBeUndefined();
    });
    
    it('should compress nested position to pos', () => {
      const original = {
        transform: {
          position: { x: 1, y: 2, z: 3 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t.pos).toEqual({ x: 1, y: 2, z: 3 });
      expect(compressed.t.position).toBeUndefined();
    });
    
    it('should compress nested rotation to rot', () => {
      const original = {
        transform: {
          rotation: { x: 0, y: Math.PI / 2, z: 0 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t.rot).toEqual({ x: 0, y: Math.PI / 2, z: 0 });
      expect(compressed.t.rotation).toBeUndefined();
    });
    
    it('should compress nested scale to s', () => {
      const original = {
        transform: {
          scale: { x: 5, y: 3, z: 0.2 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t.s).toEqual({ x: 5, y: 3, z: 0.2 });
      expect(compressed.t.scale).toBeUndefined();
    });
    
    it('should compress material_id to mid', () => {
      const original = { material_id: 'mat_concrete_01' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.mid).toBe('mat_concrete_01');
      expect(compressed.material_id).toBeUndefined();
    });
    
    it('should compress asset_id to aid', () => {
      const original = { asset_id: 'furniture_chair_01' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.aid).toBe('furniture_chair_01');
      expect(compressed.asset_id).toBeUndefined();
    });
    
    it('should compress object_type to objt', () => {
      const original = { object_type: 'wall' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.objt).toBe('wall');
      expect(compressed.object_type).toBeUndefined();
    });
    
    it('should compress version to v', () => {
      const original = { version: 42 };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.v).toBe(42);
      expect(compressed.version).toBeUndefined();
    });
    
    it('should compress timestamp to ts', () => {
      const now = Date.now();
      const original = { timestamp: now };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.ts).toBe(now);
      expect(compressed.timestamp).toBeUndefined();
    });
    
    it('should compress user_id to uid', () => {
      const original = { user_id: 'user_001' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.uid).toBe('user_001');
      expect(compressed.user_id).toBeUndefined();
    });
  });
  
  describe('Operation Type Alias Compression', () => {
    it('should compress object_create to c', () => {
      const original = { operation_type: 'object_create' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.ot).toBe('c');
    });
    
    it('should compress transform_update to tu', () => {
      const original = { operation_type: 'transform_update' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.ot).toBe('tu');
    });
    
    it('should compress object_delete to d', () => {
      const original = { operation_type: 'object_delete' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.ot).toBe('d');
    });
    
    it('should compress material_update to mu', () => {
      const original = { operation_type: 'material_update' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.ot).toBe('mu');
    });
  });
  
  describe('Object Type Alias Compression', () => {
    it('should compress wall to w', () => {
      const original = { object_type: 'wall' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.objt).toBe('w');
    });
    
    it('should compress door to d', () => {
      const original = { object_type: 'door' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.objt).toBe('d');
    });
    
    it('should compress window to wn', () => {
      const original = { object_type: 'window' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.objt).toBe('wn');
    });
    
    it('should compress furniture to f', () => {
      const original = { object_type: 'furniture' };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.objt).toBe('f');
    });
  });
  
  describe('Float Precision Compression', () => {
    it('should round floats to 3 decimal places', () => {
      const original = {
        transform: {
          position: { x: 1.123456, y: 2.987654, z: 3.141592 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t.pos.x).toBeCloseTo(1.123, 3);
      expect(compressed.t.pos.y).toBeCloseTo(2.988, 3);
      expect(compressed.t.pos.z).toBeCloseTo(3.142, 3);
    });
    
    it('should handle negative floats', () => {
      const original = {
        transform: {
          position: { x: -1.123456, y: -2.987654, z: -3.141592 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t.pos.x).toBeCloseTo(-1.123, 3);
      expect(compressed.t.pos.y).toBeCloseTo(-2.988, 3);
      expect(compressed.t.pos.z).toBeCloseTo(-3.142, 3);
    });
    
    it('should handle zero', () => {
      const original = {
        transform: {
          position: { x: 0, y: 0, z: 0 }
        }
      };
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.t.pos.x).toBe(0);
      expect(compressed.t.pos.y).toBe(0);
      expect(compressed.t.pos.z).toBe(0);
    });
  });
  
  describe('Decompression', () => {
    it('should decompress aliased fields back to original', () => {
      const original = {
        object_id: 'obj_wall_001',
        object_type: 'wall',
        transform: {
          position: { x: 1, y: 2, z: 3 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        },
        material_id: 'mat_concrete_01',
        version: 42
      };
      
      const compressed = messageCompressor.compress(original);
      const decompressed = messageCompressor.decompress(compressed);
      
      expect(decompressed.object_id).toBe('obj_wall_001');
      expect(decompressed.object_type).toBe('wall');
      expect(decompressed.transform.position).toEqual({ x: 1, y: 2, z: 3 });
      expect(decompressed.transform.rotation).toEqual({ x: 0, y: 0, z: 0 });
      expect(decompressed.transform.scale).toEqual({ x: 5, y: 3, z: 0.2 });
      expect(decompressed.material_id).toBe('mat_concrete_01');
      expect(decompressed.version).toBe(42);
    });
    
    it('should decompress operation types back to original', () => {
      const operations = [
        { operation_type: 'object_create' },
        { operation_type: 'transform_update' },
        { operation_type: 'object_delete' },
        { operation_type: 'material_update' }
      ];
      
      operations.forEach(op => {
        const compressed = messageCompressor.compress(op);
        const decompressed = messageCompressor.decompress(compressed);
        expect(decompressed.operation_type).toBe(op.operation_type);
      });
    });
    
    it('should decompress object types back to original', () => {
      const objects = [
        { object_type: 'wall' },
        { object_type: 'door' },
        { object_type: 'window' },
        { object_type: 'furniture' }
      ];
      
      objects.forEach(obj => {
        const compressed = messageCompressor.compress(obj);
        const decompressed = messageCompressor.decompress(compressed);
        expect(decompressed.object_type).toBe(obj.object_type);
      });
    });
    
    it('should handle arrays during compression/decompression', () => {
      const original = {
        objects: [
          { object_id: 'obj_001', object_type: 'wall' },
          { object_id: 'obj_002', object_type: 'door' }
        ]
      };
      
      const compressed = messageCompressor.compress(original);
      const decompressed = messageCompressor.decompress(compressed);
      
      expect(decompressed.objects[0].object_id).toBe('obj_001');
      expect(decompressed.objects[0].object_type).toBe('wall');
      expect(decompressed.objects[1].object_id).toBe('obj_002');
      expect(decompressed.objects[1].object_type).toBe('door');
    });
  });
  
  describe('Transform Compression', () => {
    it('should compress transform with only relevant fields', () => {
      const transform = {
        position: { x: 1, y: 2, z: 3 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 5, y: 3, z: 0.2 }
      };
      
      const compressed = messageCompressor.compressTransform(transform);
      
      expect(compressed.pos).toEqual({ x: 1, y: 2, z: 3 });
      expect(compressed.rot).toEqual({ x: 0, y: 0, z: 0 });
      expect(compressed.s).toEqual({ x: 5, y: 3, z: 0.2 });
    });
    
    it('should handle null transform', () => {
      const compressed = messageCompressor.compressTransform(null);
      expect(compressed).toBeNull();
    });
    
    it('should decompress compressed transform', () => {
      const original = {
        position: { x: 1, y: 2, z: 3 },
        rotation: { x: 0, y: Math.PI / 2, z: 0 },
        scale: { x: 5, y: 3, z: 0.2 }
      };
      
      const compressed = messageCompressor.compressTransform(original);
      const decompressed = messageCompressor.decompressTransform(compressed);
      
      expect(decompressed.position).toEqual(original.position);
      expect(decompressed.rotation.y).toBe(original.rotation.y);
      expect(decompressed.scale).toEqual(original.scale);
    });
    
    it('should handle null decompress transform', () => {
      const decompressed = messageCompressor.decompressTransform(null);
      expect(decompressed).toBeNull();
    });
  });
  
  describe('Delta Transform Calculation', () => {
    it('should calculate delta for position change', () => {
      const original = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const modified = {
        position: { x: 10, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const delta = messageCompressor.createDeltaMessage(original, modified);
      
      expect(delta.transform).toBeDefined();
      expect(delta.transform.position).toEqual({ x: 10, y: 0, z: 0 });
      expect(delta.transform.rotation).toBeUndefined();
      expect(delta.transform.scale).toBeUndefined();
    });
    
    it('should calculate delta for rotation change', () => {
      const original = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const modified = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: Math.PI / 2, z: 0 },
        scale: { x: 1, y: 1, z: 1 }
      };
      
      const delta = messageCompressor.createDeltaMessage(original, modified);
      
      expect(delta.transform.rotation).toEqual({ x: 0, y: Math.PI / 2, z: 0 });
      expect(delta.transform.position).toBeUndefined();
    });
    
    it('should calculate delta for material change', () => {
      const original = {
        material_id: 'mat_concrete_01'
      };
      
      const modified = {
        material_id: 'mat_brick_01'
      };
      
      const delta = messageCompressor.createDeltaMessage(original, modified);
      
      expect(delta.material_id).toBe('mat_brick_01');
    });
    
    it('should return empty delta when no changes', () => {
      const original = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 },
        material_id: 'mat_concrete_01'
      };
      
      const modified = {
        position: { x: 0, y: 0, z: 0 },
        rotation: { x: 0, y: 0, z: 0 },
        scale: { x: 1, y: 1, z: 1 },
        material_id: 'mat_concrete_01'
      };
      
      const delta = messageCompressor.createDeltaMessage(original, modified);
      
      expect(Object.keys(delta).length).toBe(0);
    });
  });
  
  describe('Size Estimation', () => {
    it('should estimate message size', () => {
      const message = {
        operation_type: 'transform_update',
        target_object: 'obj_wall_001',
        parameters: {
          transform: {
            position: { x: 10, y: 1.5, z: 0 }
          }
        }
      };
      
      const size = messageCompressor.estimateSize(message);
      
      expect(typeof size).toBe('number');
      expect(size).toBeGreaterThan(0);
    });
  });
  
  describe('Compression Ratio', () => {
    it('should calculate compression ratio', () => {
      const original = {
        object_id: 'obj_wall_001',
        operation_type: 'transform_update',
        parameters: {
          transform: {
            position: { x: 10.123456, y: 1.5, z: 0.0 },
            rotation: { x: 0.0, y: 1.570796, z: 0.0 },
            scale: { x: 5.0, y: 3.0, z: 0.2 }
          }
        },
        user_id: 'user_abcdefgh',
        timestamp: Date.now(),
        version: 42
      };
      
      const compressed = messageCompressor.compress(original);
      const ratio = messageCompressor.getCompressionRatio(original, compressed);
      
      expect(typeof ratio.originalSize).toBe('number');
      expect(typeof ratio.compressedSize).toBe('number');
      expect(typeof ratio.ratio).toBe('string');
      expect(parseFloat(ratio.ratio)).toBeGreaterThan(0);
    });
    
    it('should show compression benefit for large messages', () => {
      const original = {
        operation_type: 'object_create',
        object_type: 'furniture',
        object_id: 'obj_furniture_very_long_id_1234567890',
        parameters: {
          transform: {
            position: { x: 1.123456, y: 2.987654, z: 3.141592 },
            rotation: { x: 0.123456, y: 0.987654, z: 0.567890 },
            scale: { x: 1.5, y: 2.0, z: 1.2 }
          },
          material_id: 'mat_furniture_leather_brown_01',
          asset_id: 'furniture_sofa_modern_01'
        },
        user_id: 'user_abcdef1234567890abcdef',
        version: 100,
        timestamp: Date.now()
      };
      
      const compressed = messageCompressor.compress(original);
      const ratio = messageCompressor.getCompressionRatio(original, compressed);
      
      expect(ratio.originalSize).toBeGreaterThan(ratio.compressedSize);
      expect(parseFloat(ratio.ratio)).toBeGreaterThan(0);
    });
  });
  
  describe('Position Equality Check', () => {
    it('should return true for equal positions', () => {
      const pos1 = { x: 1.0, y: 2.0, z: 3.0 };
      const pos2 = { x: 1.0, y: 2.0, z: 3.0 };
      
      const result = messageCompressor.arePositionsEqual(pos1, pos2);
      
      expect(result).toBe(true);
    });
    
    it('should return false for different positions', () => {
      const pos1 = { x: 1.0, y: 2.0, z: 3.0 };
      const pos2 = { x: 1.0, y: 2.0, z: 4.0 };
      
      const result = messageCompressor.arePositionsEqual(pos1, pos2);
      
      expect(result).toBe(false);
    });
    
    it('should handle floating point precision', () => {
      const pos1 = { x: 1.0, y: 2.0, z: 3.0 };
      const pos2 = { x: 1.0001, y: 2.0001, z: 3.0001 };
      
      const result = messageCompressor.arePositionsEqual(pos1, pos2);
      
      expect(result).toBe(true);
    });
  });
  
  describe('Rotation Equality Check', () => {
    it('should return true for equal rotations', () => {
      const rot1 = { x: 0, y: Math.PI / 2, z: 0 };
      const rot2 = { x: 0, y: Math.PI / 2, z: 0 };
      
      const result = messageCompressor.areRotationsEqual(rot1, rot2);
      
      expect(result).toBe(true);
    });
    
    it('should return false for different rotations', () => {
      const rot1 = { x: 0, y: 0, z: 0 };
      const rot2 = { x: 0, y: Math.PI / 2, z: 0 };
      
      const result = messageCompressor.areRotationsEqual(rot1, rot2);
      
      expect(result).toBe(false);
    });
  });
  
  describe('Scale Equality Check', () => {
    it('should return true for equal scales', () => {
      const s1 = { x: 5, y: 3, z: 0.2 };
      const s2 = { x: 5, y: 3, z: 0.2 };
      
      const result = messageCompressor.areScalesEqual(s1, s2);
      
      expect(result).toBe(true);
    });
    
    it('should return false for different scales', () => {
      const s1 = { x: 1, y: 1, z: 1 };
      const s2 = { x: 2, y: 1, z: 1 };
      
      const result = messageCompressor.areScalesEqual(s1, s2);
      
      expect(result).toBe(false);
    });
  });
  
  describe('Real-World Integration Test', () => {
    it('should compress and decompress full operation message', () => {
      const originalMessage = {
        operation_type: 'transform_update',
        object_id: 'obj_wall_001',
        parameters: {
          transform: {
            position: { x: 10.5, y: 1.5, z: 0.0 },
            rotation: { x: 0.0, y: 0.7854, z: 0.0 },
            scale: { x: 5.0, y: 3.0, z: 0.2 }
          }
        },
        user_id: 'user_abc123',
        timestamp: Date.now(),
        version: 42
      };
      
      const originalSize = JSON.stringify(originalMessage).length;
      
      const compressed = messageCompressor.compress(originalMessage);
      const compressedSize = JSON.stringify(compressed).length;
      
      const decompressed = messageCompressor.decompress(compressed);
      
      expect(decompressed.operation_type).toBe(originalMessage.operation_type);
      expect(decompressed.object_id).toBe(originalMessage.object_id);
      expect(decompressed.parameters.transform.position).toEqual(originalMessage.parameters.transform.position);
      expect(decompressed.parameters.transform.rotation.y).toBeCloseTo(originalMessage.parameters.transform.rotation.y, 3);
      expect(decompressed.parameters.transform.scale).toEqual(originalMessage.parameters.transform.scale);
      expect(decompressed.user_id).toBe(originalMessage.user_id);
      expect(decompressed.version).toBe(originalMessage.version);
      
      expect(compressedSize).toBeLessThan(originalSize);
    });
  });
  
  describe('Edge Cases', () => {
    it('should handle null values during compression', () => {
      const original = {
        object_id: null,
        transform: null
      };
      
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.oid).toBeNull();
      expect(compressed.t).toBeNull();
    });
    
    it('should handle undefined values during compression', () => {
      const original = {
        object_id: undefined,
        transform: undefined
      };
      
      const compressed = messageCompressor.compress(original);
      
      expect(compressed.oid).toBeUndefined();
      expect(compressed.t).toBeUndefined();
    });
    
    it('should handle empty objects', () => {
      const original = {};
      const compressed = messageCompressor.compress(original);
      const decompressed = messageCompressor.decompress(compressed);
      
      expect(decompressed).toEqual({});
    });
    
    it('should handle arrays with mixed types', () => {
      const original = [
        { object_id: 'obj_001', object_type: 'wall' },
        'string value',
        123,
        null,
        true
      ];
      
      const compressed = messageCompressor.compress(original);
      const decompressed = messageCompressor.decompress(compressed);
      
      expect(decompressed[0].object_id).toBe('obj_001');
      expect(decompressed[1]).toBe('string value');
      expect(decompressed[2]).toBe(123);
      expect(decompressed[3]).toBeNull();
      expect(decompressed[4]).toBe(true);
    });
  });
});
