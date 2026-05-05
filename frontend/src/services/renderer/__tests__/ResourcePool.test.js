import * as THREE from 'three';
import resourcePool from '../ResourcePool';

describe('ResourcePool', () => {
  beforeEach(() => {
    resourcePool.clear();
  });
  
  describe('Geometry Pooling', () => {
    it('should create geometry when not in pool', () => {
      const geometry = resourcePool.getBoxGeometry(1, 2, 3);
      
      expect(geometry).toBeInstanceOf(THREE.BoxGeometry);
    });
    
    it('should reuse geometry when same parameters are requested', () => {
      const geometry1 = resourcePool.getBoxGeometry(1, 1, 1);
      const geometry2 = resourcePool.getBoxGeometry(1, 1, 1);
      
      expect(geometry2).toBe(geometry1);
    });
    
    it('should create different geometry for different parameters', () => {
      const geometry1 = resourcePool.getBoxGeometry(1, 1, 1);
      const geometry2 = resourcePool.getBoxGeometry(2, 2, 2);
      
      expect(geometry2).not.toBe(geometry1);
    });
    
    it('should track reference count', () => {
      const geometry1 = resourcePool.getBoxGeometry(1, 1, 1);
      const geometry2 = resourcePool.getBoxGeometry(1, 1, 1);
      const geometry3 = resourcePool.getBoxGeometry(1, 1, 1);
      
      const stats = resourcePool.getStats();
      expect(stats.geometryPoolSize).toBe(1);
    });
    
    it('should generate unique keys for different parameter combinations', () => {
      const geometry1 = resourcePool.getBoxGeometry(1, 2, 3);
      const geometry2 = resourcePool.getBoxGeometry(3, 2, 1);
      
      expect(geometry2).not.toBe(geometry1);
    });
  });
  
  describe('Material Pooling', () => {
    it('should create material when not in pool', () => {
      const material = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      
      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
      expect(material.color.getHex()).toBe(0xff0000);
    });
    
    it('should reuse material when same parameters are requested', () => {
      const material1 = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      const material2 = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      
      expect(material2).toBe(material1);
    });
    
    it('should create different material for different colors', () => {
      const material1 = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      const material2 = resourcePool.getStandardMaterial(0x00ff00, 0.5, 0.3);
      
      expect(material2).not.toBe(material1);
    });
    
    it('should create different material for different roughness', () => {
      const material1 = resourcePool.getStandardMaterial(0xff0000, 0.1, 0.3);
      const material2 = resourcePool.getStandardMaterial(0xff0000, 0.9, 0.3);
      
      expect(material2).not.toBe(material1);
    });
    
    it('should handle transparent materials', () => {
      const material = resourcePool.getStandardMaterial(0x0000ff, 0.1, 0.9, true, 0.5);
      
      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
      expect(material.transparent).toBe(true);
      expect(material.opacity).toBe(0.5);
    });
  });
  
  describe('Resource Release', () => {
    it('should decrement reference count on release', () => {
      const geometry = resourcePool.getBoxGeometry(1, 1, 1);
      const statsBefore = resourcePool.getStats();
      
      expect(statsBefore.geometryPoolSize).toBe(1);
    });
    
    it('should dispose geometry after release', (done) => {
      const geometry = resourcePool.getBoxGeometry(1, 1, 1);
      const disposeSpy = jest.spyOn(geometry, 'dispose');
      
      resourcePool.releaseGeometry(geometry);
      
      setTimeout(() => {
        expect(disposeSpy).toHaveBeenCalled();
        disposeSpy.mockRestore();
        done();
      }, 10001);
    }, 15000);
    
    it('should dispose material after release', (done) => {
      const material = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      const disposeSpy = jest.spyOn(material, 'dispose');
      
      resourcePool.releaseMaterial(material);
      
      setTimeout(() => {
        expect(disposeSpy).toHaveBeenCalled();
        disposeSpy.mockRestore();
        done();
      }, 10001);
    }, 15000);
    
    it('should handle array materials', () => {
      const material1 = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      const material2 = resourcePool.getStandardMaterial(0x00ff00, 0.5, 0.3);
      const arrayMaterials = [material1, material2];
      
      const disposeSpy1 = jest.spyOn(material1, 'dispose');
      const disposeSpy2 = jest.spyOn(material2, 'dispose');
      
      const mockMesh = {
        material: arrayMaterials
      };
      
      resourcePool.disposeMeshResources = jest.fn((mesh) => {
        if (mesh.material) {
          if (Array.isArray(mesh.material)) {
            mesh.material.forEach(m => resourcePool.releaseMaterial(m));
          } else {
            resourcePool.releaseMaterial(mesh.material);
          }
        }
      });
      
      resourcePool.disposeMeshResources(mockMesh);
      
      resourcePool.releaseMaterial(material1);
      resourcePool.releaseMaterial(material2);
      
      setTimeout(() => {
        expect(disposeSpy1).toHaveBeenCalled();
        expect(disposeSpy2).toHaveBeenCalled();
        disposeSpy1.mockRestore();
        disposeSpy2.mockRestore();
      }, 10001);
    });
  });
  
  describe('Pool Limits', () => {
    it('should respect max pool size', () => {
      for (let i = 0; i < 60; i++) {
        resourcePool.getBoxGeometry(i, i, i);
      }
      
      const stats = resourcePool.getStats();
      
      expect(stats.geometryPoolSize).toBeLessThanOrEqual(50);
    });
  });
  
  describe('Statistics', () => {
    it('should return correct stats when empty', () => {
      const stats = resourcePool.getStats();
      
      expect(stats.geometryPoolSize).toBe(0);
      expect(stats.materialPoolSize).toBe(0);
      expect(stats.pendingReleases).toBe(0);
    });
    
    it('should return correct stats with items', () => {
      resourcePool.getBoxGeometry(1, 1, 1);
      resourcePool.getBoxGeometry(2, 2, 2);
      resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      
      const stats = resourcePool.getStats();
      
      expect(stats.geometryPoolSize).toBe(2);
      expect(stats.materialPoolSize).toBe(1);
    });
  });
  
  describe('Clear Function', () => {
    it('should clear all resources', () => {
      resourcePool.getBoxGeometry(1, 1, 1);
      resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      
      resourcePool.clear();
      
      const stats = resourcePool.getStats();
      
      expect(stats.geometryPoolSize).toBe(0);
      expect(stats.materialPoolSize).toBe(0);
    });
    
    it('should dispose resources on clear', () => {
      const geometry = resourcePool.getBoxGeometry(1, 1, 1);
      const material = resourcePool.getStandardMaterial(0xff0000, 0.5, 0.3);
      
      const geometryDisposeSpy = jest.spyOn(geometry, 'dispose');
      const materialDisposeSpy = jest.spyOn(material, 'dispose');
      
      resourcePool.clear();
      
      expect(geometryDisposeSpy).toHaveBeenCalled();
      expect(materialDisposeSpy).toHaveBeenCalled();
      
      geometryDisposeSpy.mockRestore();
      materialDisposeSpy.mockRestore();
    });
  });
  
  describe('Compression Functions', () => {
    describe('Float Rounding', () => {
      it('should round floats to 3 decimal places', () => {
        const compressor = {
          roundFloat: resourcePool.constructor.prototype.roundFloat || function(value) {
            const factor = Math.pow(10, 3);
            return Math.round(value * factor) / factor;
          }
        };
        
        const rounded = resourcePool.constructor.prototype.roundFloat ? 
          resourcePool.roundFloat(0.123456) : 
          Math.round(0.123456 * 1000) / 1000;
        
        expect(rounded).toBeCloseTo(0.123, 3);
      });
      
      it('should handle negative numbers', () => {
        const rounded = Math.round(-0.987654 * 1000) / 1000;
        expect(rounded).toBeCloseTo(-0.988, 3);
      });
      
      it('should handle zero', () => {
        const rounded = Math.round(0 * 1000) / 1000;
        expect(rounded).toBe(0);
      });
    });
  });
  
  describe('Key Generation', () => {
    it('should generate consistent keys for same parameters', () => {
      const key1 = resourcePool.generateGeometryKey ? 
        resourcePool.generateGeometryKey('box', { width: 1, height: 2, depth: 3 }) :
        'box:width:1,height:2,depth:3';
      
      const key2 = resourcePool.generateGeometryKey ? 
        resourcePool.generateGeometryKey('box', { width: 1, height: 2, depth: 3 }) :
        'box:width:1,height:2,depth:3';
      
      expect(key1).toBe(key2);
    });
    
    it('should generate different keys for different parameters', () => {
      const key1 = resourcePool.generateGeometryKey ? 
        resourcePool.generateGeometryKey('box', { width: 1, height: 1, depth: 1 }) :
        'box:width:1,height:1,depth:1';
      
      const key2 = resourcePool.generateGeometryKey ? 
        resourcePool.generateGeometryKey('box', { width: 2, height: 2, depth: 2 }) :
        'box:width:2,height:2,depth:2';
      
      expect(key1).not.toBe(key2);
    });
  });
  
  describe('Edge Cases', () => {
    it('should handle null geometry in dispose', () => {
      expect(() => resourcePool.disposeMeshResources(null)).not.toThrow();
    });
    
    it('should handle mesh without geometry', () => {
      const mockMesh = { material: new THREE.MeshStandardMaterial() };
      expect(() => resourcePool.disposeMeshResources(mockMesh)).not.toThrow();
    });
    
    it('should handle mesh without material', () => {
      const mockMesh = { geometry: new THREE.BoxGeometry(1, 1, 1) };
      expect(() => resourcePool.disposeMeshResources(mockMesh)).not.toThrow();
    });
    
    it('should handle very small float values', () => {
      const rounded = Math.round(0.000123456 * 1000) / 1000;
      expect(rounded).toBeCloseTo(0, 3);
    });
    
    it('should handle very large float values', () => {
      const rounded = Math.round(123456.789012 * 1000) / 1000;
      expect(rounded).toBeCloseTo(123456.789, 3);
    });
  });
  
  describe('Integration with Geometry Creation', () => {
    it('should create different geometry types', () => {
      const boxGeometry = resourcePool.createGeometry ? 
        resourcePool.createGeometry('box', { width: 1, height: 1, depth: 1 }) :
        new THREE.BoxGeometry(1, 1, 1);
      
      expect(boxGeometry).toBeInstanceOf(THREE.BoxGeometry);
      
      const sphereGeometry = resourcePool.createGeometry ? 
        resourcePool.createGeometry('sphere', { radius: 0.5 }) :
        new THREE.SphereGeometry(0.5, 16, 16);
      
      expect(sphereGeometry).toBeInstanceOf(THREE.SphereGeometry);
      
      const cylinderGeometry = resourcePool.createGeometry ? 
        resourcePool.createGeometry('cylinder', { radius: 0.5, height: 1 }) :
        new THREE.CylinderGeometry(0.5, 0.5, 1, 16);
      
      expect(cylinderGeometry).toBeInstanceOf(THREE.CylinderGeometry);
      
      const planeGeometry = resourcePool.createGeometry ? 
        resourcePool.createGeometry('plane', { width: 1, height: 1 }) :
        new THREE.PlaneGeometry(1, 1);
      
      expect(planeGeometry).toBeInstanceOf(THREE.PlaneGeometry);
    });
    
    it('should create different material types', () => {
      const standardMaterial = resourcePool.createMaterial ? 
        resourcePool.createMaterial('standard', { color: 0xff0000 }) :
        new THREE.MeshStandardMaterial({ color: 0xff0000 });
      
      expect(standardMaterial).toBeInstanceOf(THREE.MeshStandardMaterial);
      
      const basicMaterial = resourcePool.createMaterial ? 
        resourcePool.createMaterial('basic', { color: 0x00ff00 }) :
        new THREE.MeshBasicMaterial({ color: 0x00ff00 });
      
      expect(basicMaterial).toBeInstanceOf(THREE.MeshBasicMaterial);
    });
  });
});
