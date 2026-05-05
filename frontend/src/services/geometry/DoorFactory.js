import * as THREE from 'three';
import GeometryFactory from './GeometryFactory';

class DoorFactory extends GeometryFactory {
  constructor() {
    super();
    this.type = 'door';
  }

  validateParams(params) {
    const errors = [];
    
    if (params.transform && params.transform.scale) {
      const scale = params.transform.scale;
      if (scale.x <= 0) {
        errors.push('Door width must be greater than 0');
      }
      if (scale.y <= 0) {
        errors.push('Door height must be greater than 0');
      }
    }
    
    return {
      valid: errors.length === 0,
      errors
    };
  }

  createGeometry(params) {
    const scale = params.transform?.scale || { x: 0.9, y: 2.1, z: 0.1 };
    
    return new THREE.BoxGeometry(
      scale.x,
      scale.y,
      scale.z
    );
  }

  createMaterial(params) {
    const materialId = params.material_id || 'mat_default_01';
    
    const colorMap = {
      'mat_default_01': 0x8b4513,
      'mat_wood_01': 0x654321,
      'mat_metal_01': 0xc0c0c0
    };
    
    const roughnessMap = {
      'mat_default_01': 0.6,
      'mat_wood_01': 0.7,
      'mat_metal_01': 0.3
    };
    
    const metalnessMap = {
      'mat_default_01': 0.2,
      'mat_wood_01': 0.1,
      'mat_metal_01': 0.8
    };
    
    return new THREE.MeshStandardMaterial({
      color: colorMap[materialId] || 0x8b4513,
      roughness: roughnessMap[materialId] || 0.6,
      metalness: metalnessMap[materialId] || 0.2
    });
  }

  getDefaultTransform() {
    return {
      position: { x: 0, y: 1.05, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 0.9, y: 2.1, z: 0.1 }
    };
  }

  shouldApplyScale() {
    return false;
  }
}

export default DoorFactory;
