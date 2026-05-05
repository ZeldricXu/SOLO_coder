import * as THREE from 'three';
import frustumCullingManager from '../FrustumCulling';

describe('FrustumCullingManager', () => {
  let scene;
  let camera;
  let renderer;
  let testObjects;
  
  beforeEach(() => {
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(45, 1, 0.1, 1000);
    renderer = new THREE.WebGLRenderer();
    
    camera.position.set(0, 0, 10);
    camera.lookAt(0, 0, 0);
    camera.updateMatrixWorld();
    
    testObjects = [];
    
    frustumCullingManager.toggleCulling(true);
    frustumCullingManager.setOptimizationLevel('moderate');
  });
  
  afterEach(() => {
    renderer.dispose();
    testObjects.forEach(obj => {
      if (obj.geometry) obj.geometry.dispose();
      if (obj.material) {
        if (Array.isArray(obj.material)) {
          obj.material.forEach(m => m.dispose());
        } else {
          obj.material.dispose();
        }
      }
    });
    testObjects = [];
  });
  
  describe('Basic Frustum Culling', () => {
    it('should identify object in front of camera as visible', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, 0);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.update(camera);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(true);
    });
    
    it('should identify object behind camera as not visible', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, 20);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.update(camera);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(false);
    });
    
    it('should identify object far left outside frustum as not visible', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(-100, 0, 0);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.update(camera);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(false);
    });
    
    it('should identify object far right outside frustum as not visible', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(100, 0, 0);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.update(camera);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(false);
    });
  });
  
  describe('Distance-Based Culling', () => {
    it('should cull objects beyond distance threshold', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -300);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.setOptimizationLevel('aggressive');
      frustumCullingManager.update(camera);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(false);
    });
    
    it('should not cull objects within distance in conservative mode', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -300);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.setOptimizationLevel('conservative');
      frustumCullingManager.update(camera);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(true);
    });
  });
  
  describe('Visibility Levels', () => {
    it('should return critical visibility for very close objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -5);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const level = frustumCullingManager.getVisibilityLevel(mesh, camera);
      
      expect(level).toBe('critical');
    });
    
    it('should return high visibility for close objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -30);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const level = frustumCullingManager.getVisibilityLevel(mesh, camera);
      
      expect(level).toBe('high');
    });
    
    it('should return low visibility for distant objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -150);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const level = frustumCullingManager.getVisibilityLevel(mesh, camera);
      
      expect(level).toBe('low');
    });
  });
  
  describe('Shadow Rendering Threshold', () => {
    it('should enable shadows for close objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -50);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const shouldRenderShadow = frustumCullingManager.shouldRenderShadow(mesh, camera);
      
      expect(shouldRenderShadow).toBe(true);
    });
    
    it('should disable shadows for distant objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -100);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const shouldRenderShadow = frustumCullingManager.shouldRenderShadow(mesh, camera);
      
      expect(shouldRenderShadow).toBe(false);
    });
  });
  
  describe('LOD Levels', () => {
    it('should return high LOD for close objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -30);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const lodLevel = frustumCullingManager.getLodLevel(mesh, camera);
      
      expect(lodLevel).toBe('high');
    });
    
    it('should return medium LOD for medium distance', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -80);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const lodLevel = frustumCullingManager.getLodLevel(mesh, camera);
      
      expect(lodLevel).toBe('medium');
    });
    
    it('should return low LOD for distant objects', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -200);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      const lodLevel = frustumCullingManager.getLodLevel(mesh, camera);
      
      expect(lodLevel).toBe('low');
    });
  });
  
  describe('100 Object Integration Test', () => {
    it('should correctly identify visible and invisible objects in dense scene', () => {
      const allMeshes = [];
      
      for (let i = 0; i < 100; i++) {
        const mesh = new THREE.Mesh(
          new THREE.BoxGeometry(0.5, 0.5, 0.5),
          new THREE.MeshBasicMaterial()
        );
        
        const angle = (i / 100) * Math.PI * 4;
        const radius = 2 + (i % 10) * 0.5;
        
        if (i < 50) {
          mesh.position.set(
            Math.cos(angle) * radius,
            (i % 10) * 0.2,
            Math.sin(angle) * radius - 5
          );
        } else {
          mesh.position.set(
            Math.cos(angle) * radius,
            (i % 10) * 0.2,
            Math.sin(angle) * radius + 20
          );
        }
        
        mesh.updateMatrixWorld();
        scene.add(mesh);
        allMeshes.push(mesh);
        testObjects.push(mesh);
      }
      
      frustumCullingManager.update(camera);
      
      let visibleCount = 0;
      let invisibleCount = 0;
      
      for (const mesh of allMeshes) {
        const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
        if (isVisible) {
          visibleCount++;
        } else {
          invisibleCount++;
        }
      }
      
      expect(visibleCount).toBeGreaterThan(0);
      expect(invisibleCount).toBeGreaterThan(0);
      expect(visibleCount + invisibleCount).toBe(100);
      
      const frontHalfVisible = allMeshes.slice(0, 50).filter(m => 
        frustumCullingManager.isObjectVisible(m, camera)
      ).length;
      
      expect(frontHalfVisible).toBeGreaterThan(40);
      
      const backHalfVisible = allMeshes.slice(50, 100).filter(m => 
        frustumCullingManager.isObjectVisible(m, camera)
      ).length;
      
      expect(backHalfVisible).toBeLessThan(10);
    });
    
    it('should correctly update visibility when camera rotates', () => {
      const frontMesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      frontMesh.position.set(0, 0, -5);
      frontMesh.updateMatrixWorld();
      scene.add(frontMesh);
      testObjects.push(frontMesh);
      
      const backMesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      backMesh.position.set(0, 0, 5);
      backMesh.updateMatrixWorld();
      scene.add(backMesh);
      testObjects.push(backMesh);
      
      frustumCullingManager.update(camera);
      
      expect(frustumCullingManager.isObjectVisible(frontMesh, camera)).toBe(true);
      expect(frustumCullingManager.isObjectVisible(backMesh, camera)).toBe(false);
      
      camera.rotation.y = Math.PI;
      camera.updateMatrixWorld();
      frustumCullingManager.update(camera);
      
      expect(frustumCullingManager.isObjectVisible(frontMesh, camera)).toBe(false);
      expect(frustumCullingManager.isObjectVisible(backMesh, camera)).toBe(true);
    });
    
    it('should correctly update visibility when camera moves', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, -50);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.update(camera);
      expect(frustumCullingManager.isObjectVisible(mesh, camera)).toBe(true);
      
      camera.position.set(0, 0, -100);
      camera.lookAt(0, 0, -150);
      camera.updateMatrixWorld();
      
      frustumCullingManager.update(camera);
      expect(frustumCullingManager.isObjectVisible(mesh, camera)).toBe(false);
    });
  });
  
  describe('Toggle Culling', () => {
    it('should disable culling when toggleCulling is called with false', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, 100);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.toggleCulling(false);
      const isVisible = frustumCullingManager.isObjectVisible(mesh, camera);
      
      expect(isVisible).toBe(true);
    });
    
    it('should re-enable culling when toggleCulling is called with true', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, 100);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.toggleCulling(false);
      expect(frustumCullingManager.isObjectVisible(mesh, camera)).toBe(true);
      
      frustumCullingManager.toggleCulling(true);
      frustumCullingManager.update(camera);
      expect(frustumCullingManager.isObjectVisible(mesh, camera)).toBe(false);
    });
  });
  
  describe('Optimization Levels', () => {
    it('should set correct distance threshold for conservative level', () => {
      frustumCullingManager.setOptimizationLevel('conservative');
      const stats = frustumCullingManager.getStats();
      
      expect(stats.distanceThreshold).toBe(500);
    });
    
    it('should set correct distance threshold for moderate level', () => {
      frustumCullingManager.setOptimizationLevel('moderate');
      const stats = frustumCullingManager.getStats();
      
      expect(stats.distanceThreshold).toBe(300);
    });
    
    it('should set correct distance threshold for aggressive level', () => {
      frustumCullingManager.setOptimizationLevel('aggressive');
      const stats = frustumCullingManager.getStats();
      
      expect(stats.distanceThreshold).toBe(200);
    });
  });
  
  describe('Statistics', () => {
    it('should return correct stats', () => {
      frustumCullingManager.toggleCulling(true);
      frustumCullingManager.setOptimizationLevel('aggressive');
      
      const stats = frustumCullingManager.getStats();
      
      expect(stats.cullingEnabled).toBe(true);
      expect(stats.optimizationLevel).toBe('aggressive');
      expect(stats.distanceThreshold).toBe(200);
      expect(stats.visibleObjectsCount).toBeDefined();
    });
  });
  
  describe('Clear Function', () => {
    it('should clear visible objects set', () => {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(1, 1, 1),
        new THREE.MeshBasicMaterial()
      );
      mesh.position.set(0, 0, 0);
      mesh.updateMatrixWorld();
      scene.add(mesh);
      testObjects.push(mesh);
      
      frustumCullingManager.update(camera);
      frustumCullingManager.getVisibleObjects([mesh], camera);
      
      frustumCullingManager.clear();
      const stats = frustumCullingManager.getStats();
      
      expect(stats.visibleObjectsCount).toBe(0);
    });
  });
});
