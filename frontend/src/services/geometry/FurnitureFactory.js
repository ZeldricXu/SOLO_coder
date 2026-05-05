import * as THREE from 'three';
import GeometryFactory from './GeometryFactory';

class FurnitureFactory extends GeometryFactory {
  constructor() {
    super();
    this.type = 'furniture';
  }

  validateParams(params) {
    const errors = [];
    
    if (params.transform && params.transform.scale) {
      const scale = params.transform.scale;
      if (scale.x <= 0 || scale.y <= 0 || scale.z <= 0) {
        errors.push('All scale dimensions must be greater than 0');
      }
    }
    
    return {
      valid: errors.length === 0,
      errors
    };
  }

  createGeometry(params) {
    const scale = params.transform?.scale || { x: 1, y: 1, z: 1 };
    const assetId = params.asset_id;
    
    if (assetId) {
      return this.createAssetGeometry(assetId, scale);
    }
    
    return new THREE.BoxGeometry(
      scale.x,
      scale.y,
      scale.z
    );
  }

  createAssetGeometry(assetId, scale) {
    switch (assetId) {
      case 'furniture_chair_01':
        return this.createChairGeometry(scale);
      case 'furniture_table_01':
        return this.createTableGeometry(scale);
      case 'furniture_sofa_01':
        return this.createSofaGeometry(scale);
      case 'furniture_cabinet_01':
        return this.createCabinetGeometry(scale);
      default:
        return new THREE.BoxGeometry(scale.x, scale.y, scale.z);
    }
  }

  createChairGeometry(scale) {
    return new THREE.BoxGeometry(scale.x * 0.8, scale.y, scale.z * 0.8);
  }

  createTableGeometry(scale) {
    return new THREE.BoxGeometry(scale.x, scale.y * 0.1, scale.z);
  }

  createSofaGeometry(scale) {
    return new THREE.BoxGeometry(scale.x, scale.y, scale.z);
  }

  createCabinetGeometry(scale) {
    return new THREE.BoxGeometry(scale.x, scale.y, scale.z);
  }

  createMaterial(params) {
    const materialId = params.material_id || 'mat_default_01';
    const assetId = params.asset_id;
    
    const assetColors = {
      'furniture_chair_01': 0x2c3e50,
      'furniture_table_01': 0x8b6914,
      'furniture_sofa_01': 0x5d4037,
      'furniture_cabinet_01': 0x795548
    };
    
    const defaultColor = assetId && assetColors[assetId] 
      ? assetColors[assetId] 
      : (materialId === 'mat_default_01' ? 0x666666 : 0x888888);
    
    const colorMap = {
      'mat_default_01': defaultColor,
      'mat_wood_01': 0x8b6914,
      'mat_metal_01': 0xb0c4de,
      'mat_leather_01': 0x5d4037,
      'mat_fabric_01': 0x4a4a4a
    };
    
    const roughnessMap = {
      'mat_default_01': 0.5,
      'mat_wood_01': 0.7,
      'mat_metal_01': 0.3,
      'mat_leather_01': 0.4,
      'mat_fabric_01': 0.8
    };
    
    const metalnessMap = {
      'mat_default_01': 0.3,
      'mat_wood_01': 0.1,
      'mat_metal_01': 0.8,
      'mat_leather_01': 0.2,
      'mat_fabric_01': 0.1
    };
    
    return new THREE.MeshStandardMaterial({
      color: colorMap[materialId] || defaultColor,
      roughness: roughnessMap[materialId] || 0.5,
      metalness: metalnessMap[materialId] || 0.3
    });
  }

  getDefaultTransform() {
    return {
      position: { x: 0, y: 0.5, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1, y: 1, z: 1 }
    };
  }

  shouldApplyScale() {
    return true;
  }
}

export default FurnitureFactory;
