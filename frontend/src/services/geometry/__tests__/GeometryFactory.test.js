import * as THREE from 'three';
import GeometryFactory from '../GeometryFactory';
import WallFactory from '../WallFactory';
import DoorFactory from '../DoorFactory';
import FurnitureFactory from '../FurnitureFactory';
import factoryRegistry from '../FactoryRegistry';

describe('GeometryFactory Base Class', () => {
  let factory;
  
  beforeEach(() => {
    factory = new GeometryFactory();
  });
  
  describe('validateParams', () => {
    it('should return valid for any parameters by default', () => {
      const result = factory.validateParams({});
      expect(result.valid).toBe(true);
      expect(result.errors).toEqual([]);
    });
  });
  
  describe('createGeometry', () => {
    it('should create a default BoxGeometry', () => {
      const geometry = factory.createGeometry({});
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
  });
  
  describe('createMaterial', () => {
    it('should create a default MeshStandardMaterial', () => {
      const material = factory.createMaterial({});
      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
    });
  });
  
  describe('getDefaultTransform', () => {
    it('should return default transform with zeros and ones', () => {
      const transform = factory.getDefaultTransform();
      expect(transform.position).toEqual({ x: 0, y: 0, z: 0 });
      expect(transform.rotation).toEqual({ x: 0, y: 0, z: 0 });
      expect(transform.scale).toEqual({ x: 1, y: 1, z: 1 });
    });
  });
  
  describe('buildMesh', () => {
    it('should build a mesh with valid parameters', () => {
      const mesh = factory.buildMesh({
        transform: {
          position: { x: 0, y: 0, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 1, y: 1, z: 1 }
        }
      });
      expect(mesh).toBeInstanceOf(THREE.Mesh);
      expect(mesh.castShadow).toBe(true);
      expect(mesh.receiveShadow).toBe(true);
    });
  });
});

describe('WallFactory', () => {
  let factory;
  
  beforeEach(() => {
    factory = new WallFactory();
  });
  
  describe('Object Type', () => {
    it('should have correct object type', () => {
      expect(factory.type).toBe('wall');
    });
    
    it('should return correct type via getObjectType', () => {
      expect(factory.getObjectType()).toBe('wall');
    });
  });
  
  describe('Parameter Validation', () => {
    it('should validate positive dimensions', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 5, y: 3, z: 0.2 }
        }
      });
      expect(result.valid).toBe(true);
    });
    
    it('should reject negative width', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: -5, y: 3, z: 0.2 }
        }
      });
      expect(result.valid).toBe(false);
      expect(result.errors).toContain('Wall width must be greater than 0');
    });
    
    it('should reject zero height', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 5, y: 0, z: 0.2 }
        }
      });
      expect(result.valid).toBe(false);
      expect(result.errors).toContain('Wall height must be greater than 0');
    });
    
    it('should reject negative thickness', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 5, y: 3, z: -0.2 }
        }
      });
      expect(result.valid).toBe(false);
      expect(result.errors).toContain('Wall thickness must be greater than 0');
    });
    
    it('should return valid when no transform provided', () => {
      const result = factory.validateParams({});
      expect(result.valid).toBe(true);
    });
  });
  
  describe('Geometry Creation', () => {
    it('should create BoxGeometry with correct dimensions', () => {
      const geometry = factory.createGeometry({
        transform: {
          scale: { x: 10, y: 4, z: 0.3 }
        }
      });
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
    
    it('should use default scale when not provided', () => {
      const geometry = factory.createGeometry({});
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
  });
  
  describe('Material Creation', () => {
    it('should create default wall material', () => {
      const material = factory.createMaterial({});
      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
      expect(material.color.getHex()).toBe(0x8b7355);
    });
    
    it('should handle different material IDs', () => {
      const material = factory.createMaterial({ material_id: 'mat_concrete_01' });
      expect(material.color.getHex()).toBe(0x808080);
    });
    
    it('should use default for unknown material IDs', () => {
      const material = factory.createMaterial({ material_id: 'mat_unknown_99' });
      expect(material.color.getHex()).toBe(0x8b7355);
    });
  });
  
  describe('Default Transform', () => {
    it('should have correct default transform for walls', () => {
      const transform = factory.getDefaultTransform();
      expect(transform.position.y).toBe(1.5);
      expect(transform.scale).toEqual({ x: 5, y: 3, z: 0.2 });
    });
  });
  
  describe('Build Mesh Integration', () => {
    it('should build a valid wall mesh', () => {
      const mesh = factory.buildMesh({
        transform: {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        }
      });
      expect(mesh).toBeInstanceOf(THREE.Mesh);
      expect(mesh.geometry).toBeInstanceOf(THREE.BoxGeometry);
      expect(mesh.material).toBeInstanceOf(THREE.MeshStandardMaterial);
    });
    
    it('should return null for invalid parameters', () => {
      const mesh = factory.buildMesh({
        transform: {
          scale: { x: -5, y: 3, z: 0.2 }
        }
      });
      expect(mesh).toBeNull();
    });
  });
});

describe('DoorFactory', () => {
  let factory;
  
  beforeEach(() => {
    factory = new DoorFactory();
  });
  
  describe('Object Type', () => {
    it('should have correct object type', () => {
      expect(factory.type).toBe('door');
    });
  });
  
  describe('Parameter Validation', () => {
    it('should validate positive dimensions', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 0.9, y: 2.1, z: 0.1 }
        }
      });
      expect(result.valid).toBe(true);
    });
    
    it('should reject negative width', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: -0.9, y: 2.1, z: 0.1 }
        }
      });
      expect(result.valid).toBe(false);
      expect(result.errors).toContain('Door width must be greater than 0');
    });
    
    it('should reject zero height', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 0.9, y: 0, z: 0.1 }
        }
      });
      expect(result.valid).toBe(false);
      expect(result.errors).toContain('Door height must be greater than 0');
    });
  });
  
  describe('Material Creation', () => {
    it('should create default door material', () => {
      const material = factory.createMaterial({});
      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
      expect(material.color.getHex()).toBe(0x8b4513);
    });
    
    it('should handle wood material', () => {
      const material = factory.createMaterial({ material_id: 'mat_wood_01' });
      expect(material.color.getHex()).toBe(0x654321);
    });
  });
  
  describe('Default Transform', () => {
    it('should have correct default transform for doors', () => {
      const transform = factory.getDefaultTransform();
      expect(transform.position.y).toBe(1.05);
      expect(transform.scale).toEqual({ x: 0.9, y: 2.1, z: 0.1 });
    });
  });
});

describe('FurnitureFactory', () => {
  let factory;
  
  beforeEach(() => {
    factory = new FurnitureFactory();
  });
  
  describe('Object Type', () => {
    it('should have correct object type', () => {
      expect(factory.type).toBe('furniture');
    });
  });
  
  describe('Parameter Validation', () => {
    it('should validate positive dimensions', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 1, y: 1, z: 1 }
        }
      });
      expect(result.valid).toBe(true);
    });
    
    it('should reject any negative scale', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 1, y: -1, z: 1 }
        }
      });
      expect(result.valid).toBe(false);
      expect(result.errors).toContain('All scale dimensions must be greater than 0');
    });
  });
  
  describe('Material Creation', () => {
    it('should create default furniture material', () => {
      const material = factory.createMaterial({});
      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
    });
    
    it('should use asset-specific colors', () => {
      const material = factory.createMaterial({ asset_id: 'furniture_chair_01' });
      expect(material.color.getHex()).toBe(0x2c3e50);
    });
    
    it('should handle different material types', () => {
      const material = factory.createMaterial({ material_id: 'mat_metal_01' });
      expect(material.metalness).toBe(0.8);
    });
  });
  
  describe('Asset-Specific Geometry', () => {
    it('should create chair geometry for chair asset', () => {
      const geometry = factory.createGeometry({
        asset_id: 'furniture_chair_01',
        transform: { scale: { x: 1, y: 1, z: 1 } }
      });
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
    
    it('should create table geometry for table asset', () => {
      const geometry = factory.createGeometry({
        asset_id: 'furniture_table_01',
        transform: { scale: { x: 1.5, y: 0.75, z: 0.8 } }
      });
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
  });
  
  describe('Default Transform', () => {
    it('should have correct default transform for furniture', () => {
      const transform = factory.getDefaultTransform();
      expect(transform.position.y).toBe(0.5);
      expect(transform.scale).toEqual({ x: 1, y: 1, z: 1 });
    });
  });
  
  describe('Scale Application', () => {
    it('should return true for shouldApplyScale', () => {
      expect(factory.shouldApplyScale()).toBe(true);
    });
  });
});

describe('FactoryRegistry', () => {
  it('should register and retrieve WallFactory', () => {
    const wallFactory = new WallFactory();
    factoryRegistry.registerFactory('wall', wallFactory);
    const retrieved = factoryRegistry.getFactory('wall');
    expect(retrieved).toBeInstanceOf(WallFactory);
  });
  
  it('should register and retrieve DoorFactory', () => {
    const doorFactory = new DoorFactory();
    factoryRegistry.registerFactory('door', doorFactory);
    const retrieved = factoryRegistry.getFactory('door');
    expect(retrieved).toBeInstanceOf(DoorFactory);
  });
  
  it('should register and retrieve FurnitureFactory', () => {
    const furnitureFactory = new FurnitureFactory();
    factoryRegistry.registerFactory('furniture', furnitureFactory);
    const retrieved = factoryRegistry.getFactory('furniture');
    expect(retrieved).toBeInstanceOf(FurnitureFactory);
  });
  
  describe('Factory Presence Check', () => {
    it('should return true for registered factory', () => {
      expect(factoryRegistry.hasFactory('wall')).toBe(true);
    });
    
    it('should return false for unregistered factory', () => {
      expect(factoryRegistry.hasFactory('unknown_type')).toBe(false);
    });
  });
  
  describe('Get Registered Types', () => {
    it('should return array of registered types', () => {
      const types = factoryRegistry.getRegisteredTypes();
      expect(Array.isArray(types)).toBe(true);
      expect(types).toContain('wall');
      expect(types).toContain('door');
      expect(types).toContain('furniture');
    });
  });
  
  describe('Unregister Factory', () => {
    it('should unregister a factory', () => {
      const result = factoryRegistry.unregisterFactory('furniture');
      expect(result).toBe(true);
      expect(factoryRegistry.hasFactory('furniture')).toBe(false);
    });
    
    it('should return false when unregistering non-existent factory', () => {
      const result = factoryRegistry.unregisterFactory('non_existent');
      expect(result).toBe(false);
    });
  });
  
  describe('Build Mesh Integration', () => {
    it('should build mesh using wall factory', () => {
      const mesh = factoryRegistry.buildMesh('wall', {
        transform: {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        }
      });
      expect(mesh).toBeInstanceOf(THREE.Mesh);
    });
    
    it('should build mesh using door factory', () => {
      const mesh = factoryRegistry.buildMesh('door', {
        transform: {
          position: { x: 0, y: 1.05, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 0.9, y: 2.1, z: 0.1 }
        }
      });
      expect(mesh).toBeInstanceOf(THREE.Mesh);
    });
  });
  
  describe('Create From Object Data', () => {
    it('should create mesh from object data with wall type', () => {
      const mesh = factoryRegistry.createFromObjectData({
        object_id: 'test_wall_001',
        object_type: 'wall',
        transform: {
          position: { x: 0, y: 1.5, z: 0 },
          rotation: { x: 0, y: 0, z: 0 },
          scale: { x: 5, y: 3, z: 0.2 }
        },
        material_id: 'mat_default_01'
      });
      expect(mesh).toBeInstanceOf(THREE.Mesh);
      expect(mesh.userData.objectId).toBe('test_wall_001');
      expect(mesh.userData.objectType).toBe('wall');
    });
  });
  
  describe('Get Factory Info', () => {
    it('should return correct info for registered factory', () => {
      const info = factoryRegistry.getFactoryInfo('wall');
      expect(info.type).toBe('wall');
      expect(info.hasFactory).toBe(true);
      expect(info.isDefault).toBe(false);
    });
    
    it('should return default info for unregistered type', () => {
      const info = factoryRegistry.getFactoryInfo('unknown');
      expect(info.hasFactory).toBe(false);
      expect(info.isDefault).toBe(true);
    });
  });
});

describe('Boundary and Edge Case Testing', () => {
  describe('WallFactory Edge Cases', () => {
    let factory;
    
    beforeEach(() => {
      factory = new WallFactory();
    });
    
    it('should handle very small but positive values', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 0.001, y: 0.001, z: 0.001 }
        }
      });
      expect(result.valid).toBe(true);
    });
    
    it('should handle very large values', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: 1000000, y: 1000000, z: 1000000 }
        }
      });
      expect(result.valid).toBe(true);
    });
    
    it('should reject NaN values', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: NaN, y: 3, z: 0.2 }
        }
      });
      expect(result.valid).toBe(false);
    });
    
    it('should reject Infinity values', () => {
      const result = factory.validateParams({
        transform: {
          scale: { x: Infinity, y: 3, z: 0.2 }
        }
      });
      expect(result.valid).toBe(false);
    });
  });
  
  describe('DoorFactory Edge Cases', () => {
    let factory;
    
    beforeEach(() => {
      factory = new DoorFactory();
    });
    
    it('should handle missing transform gracefully', () => {
      const mesh = factory.buildMesh({});
      expect(mesh).toBeInstanceOf(THREE.Mesh);
    });
    
    it('should handle partial transform data', () => {
      const mesh = factory.buildMesh({
        transform: {
          position: { x: 5, y: 0, z: 0 }
        }
      });
      expect(mesh).toBeInstanceOf(THREE.Mesh);
    });
  });
  
  describe('FurnitureFactory Asset Handling', () => {
    let factory;
    
    beforeEach(() => {
      factory = new FurnitureFactory();
    });
    
    it('should handle unknown asset IDs gracefully', () => {
      const geometry = factory.createGeometry({
        asset_id: 'unknown_asset_999',
        transform: { scale: { x: 1, y: 1, z: 1 } }
      });
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
    
    it('should handle missing asset ID', () => {
      const geometry = factory.createGeometry({
        transform: { scale: { x: 1, y: 1, z: 1 } }
      });
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
    
    it('should create material with default gray color', () => {
      const material = factory.createMaterial({});
      expect(material.color.getHex()).toBe(0x666666);
    });
  });
});
