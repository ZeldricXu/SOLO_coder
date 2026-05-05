import * as THREE from 'three';

class FrustumCullingManager {
  constructor() {
    this.frustum = new THREE.Frustum();
    this.projScreenMatrix = new THREE.Matrix4();
    this.boundingBox = new THREE.Box3();
    this.boundingSphere = new THREE.Sphere();
    this.visibleObjects = new Set();
    this.cullingEnabled = true;
    this.cullFrameInterval = 1;
    this.frameCounter = 0;
    this.lastVisibleSet = new Set();
    this.optimizationLevel = 'aggressive';
    this.distanceThreshold = 200;
    this.lodEnabled = true;
    this.lodLevels = [
      { distance: 50, detail: 'high' },
      { distance: 100, detail: 'medium' },
      { distance: 200, detail: 'low' }
    ];
  }

  update(camera) {
    if (!this.cullingEnabled) return;
    
    this.frameCounter++;
    if (this.frameCounter % this.cullFrameInterval !== 0) {
      return;
    }
    
    this.projScreenMatrix.multiplyMatrices(
      camera.projectionMatrix,
      camera.matrixWorldInverse
    );
    this.frustum.setFromProjectionMatrix(this.projScreenMatrix);
  }

  isObjectVisible(object, camera) {
    if (!this.cullingEnabled) return true;
    
    if (!object.geometry) return true;
    
    const box = new THREE.Box3().setFromObject(object);
    
    const center = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3());
    
    const radius = Math.max(size.x, size.y, size.z) * 0.5;
    
    const distance = center.distanceTo(camera.position);
    
    if (distance > this.distanceThreshold) {
      return false;
    }
    
    const sphere = new THREE.Sphere(center, radius);
    return this.frustum.intersectsSphere(sphere);
  }

  getDistanceFromCamera(object, camera) {
    return object.position.distanceTo(camera.position);
  }

  getVisibleObjects(objects, camera) {
    const visibleList = [];
    
    for (const object of objects) {
      if (this.isObjectVisible(object, camera)) {
        visibleList.push(object);
      }
    }
    
    this.visibleObjects = new Set(visibleList);
    return visibleList;
  }

  getVisibilityLevel(object, camera) {
    const distance = this.getDistanceFromCamera(object, camera);
    
    if (distance <= 20) return 'critical';
    if (distance <= 50) return 'high';
    if (distance <= 100) return 'medium';
    return 'low';
  }

  shouldRenderShadow(object, camera) {
    const distance = this.getDistanceFromCamera(object, camera);
    return distance <= 80;
  }

  getLodLevel(object, camera) {
    const distance = this.getDistanceFromCamera(object, camera);
    
    for (let i = this.lodLevels.length - 1; i >= 0; i--) {
      if (distance >= this.lodLevels[i].distance) {
        return this.lodLevels[i].detail;
      }
    }
    return 'high';
  }

  setOptimizationLevel(level) {
    this.optimizationLevel = level;
    
    switch (level) {
      case 'conservative':
        this.distanceThreshold = 500;
        this.cullFrameInterval = 3;
        break;
      case 'moderate':
        this.distanceThreshold = 300;
        this.cullFrameInterval = 2;
        break;
      case 'aggressive':
      default:
        this.distanceThreshold = 200;
        this.cullFrameInterval = 1;
        break;
    }
  }

  toggleCulling(enabled) {
    this.cullingEnabled = enabled;
  }

  clear() {
    this.visibleObjects.clear();
    this.lastVisibleSet.clear();
  }

  getStats() {
    return {
      cullingEnabled: this.cullingEnabled,
      optimizationLevel: this.optimizationLevel,
      distanceThreshold: this.distanceThreshold,
      visibleObjectsCount: this.visibleObjects.size
    };
  }
}

const frustumCullingManager = new FrustumCullingManager();
export default frustumCullingManager;
