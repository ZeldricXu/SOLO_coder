import * as THREE from 'three';

class GeometryFactory {
  constructor() {
    this.type = 'base';
  }

  validateParams(params) {
    return {
      valid: true,
      errors: []
    };
  }

  createGeometry(params) {
    return new THREE.BoxGeometry(1, 1, 1);
  }

  createMaterial(params) {
    return new THREE.MeshStandardMaterial({
      color: 0x888888,
      roughness: 0.5,
      metalness: 0.3
    });
  }

  getDefaultTransform() {
    return {
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1, y: 1, z: 1 }
    };
  }

  getObjectType() {
    return this.type;
  }

  buildMesh(params) {
    const validation = this.validateParams(params);
    
    if (!validation.valid) {
      console.error('Validation errors:', validation.errors);
      return null;
    }
    
    const geometry = this.createGeometry(params);
    const material = this.createMaterial(params);
    
    const mesh = new THREE.Mesh(geometry, material);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    
    this.applyTransform(mesh, params);
    
    return mesh;
  }

  applyTransform(mesh, params) {
    const transform = params.transform || this.getDefaultTransform();
    
    mesh.position.set(
      transform.position.x,
      transform.position.y,
      transform.position.z
    );
    
    mesh.rotation.set(
      transform.rotation.x,
      transform.rotation.y,
      transform.rotation.z
    );
    
    if (this.shouldApplyScale()) {
      mesh.scale.set(
        transform.scale.x,
        transform.scale.y,
        transform.scale.z
      );
    }
  }

  shouldApplyScale() {
    return false;
  }

  disposeMesh(mesh) {
    if (mesh) {
      if (mesh.geometry) {
        mesh.geometry.dispose();
      }
      if (mesh.material) {
        if (Array.isArray(mesh.material)) {
          mesh.material.forEach(m => m.dispose());
        } else {
          mesh.material.dispose();
        }
      }
    }
  }
}

export default GeometryFactory;
