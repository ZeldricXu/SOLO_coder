import * as THREE from 'three';
import GeometryFactory from './GeometryFactory';

class WallFactory extends GeometryFactory {
  constructor() {
    super();
    this.type = 'wall';
  }

  validateParams(params) {
    const errors = [];
    
    if (params.transform && params.transform.scale) {
      const scale = params.transform.scale;
      if (scale.x <= 0) {
        errors.push('Wall width must be greater than 0');
      }
      if (scale.y <= 0) {
        errors.push('Wall height must be greater than 0');
      }
      if (scale.z <= 0) {
        errors.push('Wall thickness must be greater than 0');
      }
    }
    
    return {
      valid: errors.length === 0,
      errors
    };
  }

  createGeometry(params) {
    const scale = params.transform?.scale || { x: 5, y: 3, z: 0.2 };
    
    return new THREE.BoxGeometry(
      scale.x,
      scale.y,
      scale.z
    );
  }

  createMaterial(params) {
    const materialId = params.material_id || 'mat_default_01';
    
    const colorMap = {
      'mat_default_01': 0x8b7355,
      'mat_concrete_01': 0x808080,
      'mat_brick_01': 0xb22222,
      'mat_wood_01': 0x8b4513
    };
    
    const roughnessMap = {
      'mat_default_01': 0.8,
      'mat_concrete_01': 0.9,
      'mat_brick_01': 0.85,
      'mat_wood_01': 0.7
    };
    
    const metalnessMap = {
      'mat_default_01': 0.1,
      'mat_concrete_01': 0.05,
      'mat_brick_01': 0.05,
      'mat_wood_01': 0.1
    };
    
    return new THREE.MeshStandardMaterial({
      color: colorMap[materialId] || 0x8b7355,
      roughness: roughnessMap[materialId] || 0.8,
      metalness: metalnessMap[materialId] || 0.1
    });
  }

  getDefaultTransform() {
    return {
      position: { x: 0, y: 1.5, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 5, y: 3, z: 0.2 }
    };
  }

  shouldApplyScale() {
    return false;
  }
}

export default WallFactory;
