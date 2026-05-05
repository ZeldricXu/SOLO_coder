import * as THREE from 'three';

class ResourcePool {
  constructor() {
    this.geometryPool = new Map();
    this.materialPool = new Map();
    this.pendingRelease = [];
    this.releaseTimer = null;
    this.releaseDelay = 5000;
    this.maxPoolSize = 50;
  }

  generateGeometryKey(geometryType, params) {
    const paramStr = Object.keys(params || {})
      .sort()
      .map(k => `${k}:${params[k]}`)
      .join(',');
    return `${geometryType}:${paramStr}`;
  }

  generateMaterialKey(materialType, params) {
    const paramStr = Object.keys(params || {})
      .sort()
      .map(k => `${k}:${params[k]}`)
      .join(',');
    return `${materialType}:${paramStr}`;
  }

  acquireGeometry(geometryType, params) {
    const key = this.generateGeometryKey(geometryType, params);
    
    if (this.geometryPool.has(key)) {
      const poolEntry = this.geometryPool.get(key);
      poolEntry.lastUsed = Date.now();
      poolEntry.referenceCount++;
      return poolEntry.geometry;
    }
    
    const geometry = this.createGeometry(geometryType, params);
    
    if (this.geometryPool.size < this.maxPoolSize) {
      this.geometryPool.set(key, {
        geometry,
        referenceCount: 1,
        lastUsed: Date.now(),
        key
      });
    }
    
    return geometry;
  }

  createGeometry(geometryType, params) {
    const { width, height, depth, radius } = params || {};
    
    switch (geometryType) {
      case 'box':
        return new THREE.BoxGeometry(
          width || 1,
          height || 1,
          depth || 1
        );
      case 'sphere':
        return new THREE.SphereGeometry(radius || 0.5, 16, 16);
      case 'cylinder':
        return new THREE.CylinderGeometry(
          radius || 0.5,
          radius || 0.5,
          height || 1,
          16
        );
      case 'plane':
        return new THREE.PlaneGeometry(width || 1, height || 1);
      default:
        return new THREE.BoxGeometry(1, 1, 1);
    }
  }

  releaseGeometry(geometry) {
    for (const [key, entry] of this.geometryPool.entries()) {
      if (entry.geometry === geometry) {
        entry.referenceCount--;
        if (entry.referenceCount <= 0) {
          this.scheduleRelease(key, 'geometry');
        }
        return;
      }
    }
    
    this.scheduleImmediateDispose(geometry);
  }

  acquireMaterial(materialType, params) {
    const key = this.generateMaterialKey(materialType, params);
    
    if (this.materialPool.has(key)) {
      const poolEntry = this.materialPool.get(key);
      poolEntry.lastUsed = Date.now();
      poolEntry.referenceCount++;
      return poolEntry.material;
    }
    
    const material = this.createMaterial(materialType, params);
    
    if (this.materialPool.size < this.maxPoolSize) {
      this.materialPool.set(key, {
        material,
        referenceCount: 1,
        lastUsed: Date.now(),
        key
      });
    }
    
    return material;
  }

  createMaterial(materialType, params) {
    const { color, roughness, metalness, transparent, opacity } = params || {};
    
    switch (materialType) {
      case 'standard':
        return new THREE.MeshStandardMaterial({
          color: color || 0xffffff,
          roughness: roughness !== undefined ? roughness : 0.5,
          metalness: metalness !== undefined ? metalness : 0.3
        });
      case 'basic':
        return new THREE.MeshBasicMaterial({
          color: color || 0xffffff
        });
      case 'phong':
        return new THREE.MeshPhongMaterial({
          color: color || 0xffffff,
          shininess: 30
        });
      case 'physical':
        return new THREE.MeshPhysicalMaterial({
          color: color || 0xffffff,
          roughness: roughness !== undefined ? roughness : 0.5,
          metalness: metalness !== undefined ? metalness : 0.3
        });
      default:
        return new THREE.MeshStandardMaterial({
          color: color || 0xffffff
        });
    }
  }

  releaseMaterial(material) {
    for (const [key, entry] of this.materialPool.entries()) {
      if (entry.material === material) {
        entry.referenceCount--;
        if (entry.referenceCount <= 0) {
          this.scheduleRelease(key, 'material');
        }
        return;
      }
    }
    
    this.scheduleImmediateDispose(material);
  }

  scheduleRelease(key, type) {
    this.pendingRelease.push({
      key,
      type,
      scheduledTime: Date.now() + this.releaseDelay
    });
    
    if (!this.releaseTimer) {
      this.releaseTimer = setInterval(() => {
        this.processPendingReleases();
      }, 1000);
    }
  }

  processPendingReleases() {
    const now = Date.now();
    const toRelease = [];
    const remaining = [];
    
    for (const item of this.pendingRelease) {
      if (now >= item.scheduledTime) {
        toRelease.push(item);
      } else {
        remaining.push(item);
      }
    }
    
    this.pendingRelease = remaining;
    
    for (const item of toRelease) {
      this.performRelease(item.key, item.type);
    }
    
    if (this.pendingRelease.length === 0 && this.releaseTimer) {
      clearInterval(this.releaseTimer);
      this.releaseTimer = null;
    }
  }

  performRelease(key, type) {
    if (type === 'geometry') {
      const entry = this.geometryPool.get(key);
      if (entry && entry.referenceCount <= 0) {
        entry.geometry.dispose();
        this.geometryPool.delete(key);
        console.log(`Geometry released: ${key}`);
      }
    } else if (type === 'material') {
      const entry = this.materialPool.get(key);
      if (entry && entry.referenceCount <= 0) {
        entry.material.dispose();
        this.materialPool.delete(key);
        console.log(`Material released: ${key}`);
      }
    }
  }

  scheduleImmediateDispose(resource) {
    setTimeout(() => {
      if (resource && typeof resource.dispose === 'function') {
        resource.dispose();
      }
    }, 100);
  }

  getBoxGeometry(width, height, depth) {
    return this.acquireGeometry('box', { width, height, depth });
  }

  getStandardMaterial(color, roughness, metalness, transparent = false, opacity = 1) {
    return this.acquireMaterial('standard', {
      color,
      roughness,
      metalness,
      transparent,
      opacity
    });
  }

  clear() {
    if (this.releaseTimer) {
      clearInterval(this.releaseTimer);
      this.releaseTimer = null;
    }
    
    for (const [key, entry] of this.geometryPool.entries()) {
      entry.geometry.dispose();
    }
    this.geometryPool.clear();
    
    for (const [key, entry] of this.materialPool.entries()) {
      entry.material.dispose();
    }
    this.materialPool.clear();
    
    this.pendingRelease = [];
    
    console.log('ResourcePool cleared');
  }

  getStats() {
    return {
      geometryPoolSize: this.geometryPool.size,
      materialPoolSize: this.materialPool.size,
      pendingReleases: this.pendingRelease.length
    };
  }
}

const resourcePool = new ResourcePool();
export default resourcePool;
