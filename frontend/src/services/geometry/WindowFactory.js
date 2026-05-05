import * as THREE from 'three';
import GeometryFactory from './GeometryFactory';

class WindowFactory extends GeometryFactory {
  constructor() {
    super();
    this.type = 'window';
  }

  validateParams(params) {
    const errors = [];
    
    if (params.transform && params.transform.scale) {
      const scale = params.transform.scale;
      if (scale.x <= 0) {
        errors.push('Window width must be greater than 0');
      }
      if (scale.y <= 0) {
        errors.push('Window height must be greater than 0');
      }
    }
    
    return {
      valid: errors.length === 0,
      errors
    };
  }

  createGeometry(params) {
    const scale = params.transform?.scale || { x: 1.5, y: 1.2, z: 0.1 };
    
    return new THREE.BoxGeometry(
      scale.x,
      scale.y,
      scale.z
    );
  }

  createMaterial(params) {
    const materialId = params.material_id || 'mat_default_01';
    
    const colorMap = {
      'mat_default_01': 0x87ceeb,
      'mat_glass_01': 0xb0e0e6,
      'mat_tinted_01': 0x4682b4
    };
    
    const roughnessMap = {
      'mat_default_01': 0.1,
      'mat_glass_01': 0.05,
      'mat_tinted_01': 0.1
    };
    
    const metalnessMap = {
      'mat_default_01': 0.8,
      'mat_glass_01': 0.9,
      'mat_tinted_01': 0.7
    };
    
    return new THREE.MeshStandardMaterial({
      color: colorMap[materialId] || 0x87ceeb,
      transparent: true,
      opacity: 0.6,
      roughness: roughnessMap[materialId] || 0.1,
      metalness: metalnessMap[materialId] || 0.8
    });
  }

  getDefaultTransform() {
    return {
      position: { x: 0, y: 1.6, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1.5, y: 1.2, z: 0.1 }
    };
  }

  shouldApplyScale() {
    return false;
  }
}

export default WindowFactory;
