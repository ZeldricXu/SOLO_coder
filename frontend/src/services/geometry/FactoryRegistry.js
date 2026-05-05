import WallFactory from './WallFactory';
import DoorFactory from './DoorFactory';
import WindowFactory from './WindowFactory';
import FurnitureFactory from './FurnitureFactory';
import GeometryFactory from './GeometryFactory';

class FactoryRegistry {
  constructor() {
    this.factories = new Map();
    this.defaultFactory = new GeometryFactory();
    this.initializeDefaultFactories();
  }

  initializeDefaultFactories() {
    this.registerFactory('wall', new WallFactory());
    this.registerFactory('door', new DoorFactory());
    this.registerFactory('window', new WindowFactory());
    this.registerFactory('furniture', new FurnitureFactory());
  }

  registerFactory(type, factory) {
    if (this.factories.has(type)) {
      console.warn(`Factory for type '${type}' already registered, overwriting`);
    }
    
    this.factories.set(type, factory);
    console.log(`Factory registered for type: ${type}`);
  }

  unregisterFactory(type) {
    if (this.factories.has(type)) {
      this.factories.delete(type);
      console.log(`Factory unregistered for type: ${type}`);
      return true;
    }
    return false;
  }

  getFactory(type) {
    if (this.factories.has(type)) {
      return this.factories.get(type);
    }
    
    console.warn(`No factory found for type '${type}', using default factory`);
    return this.defaultFactory;
  }

  hasFactory(type) {
    return this.factories.has(type);
  }

  getRegisteredTypes() {
    return Array.from(this.factories.keys());
  }

  buildMesh(objectType, params) {
    const factory = this.getFactory(objectType);
    return factory.buildMesh(params);
  }

  validateParams(objectType, params) {
    const factory = this.getFactory(objectType);
    return factory.validateParams(params);
  }

  getDefaultTransform(objectType) {
    const factory = this.getFactory(objectType);
    return factory.getDefaultTransform();
  }

  disposeAll() {
    for (const [type, factory] of this.factories.entries()) {
      console.log(`Disposing factory: ${type}`);
    }
    this.factories.clear();
    this.initializeDefaultFactories();
  }

  getFactoryInfo(type) {
    const factory = this.getFactory(type);
    return {
      type: factory.type,
      hasFactory: this.hasFactory(type),
      isDefault: !this.hasFactory(type)
    };
  }

  createFromObjectData(objectData) {
    const { object_type, transform, material_id, asset_id } = objectData;
    
    const mesh = this.buildMesh(object_type, {
      transform,
      material_id,
      asset_id
    });
    
    if (mesh) {
      mesh.userData.objectId = objectData.object_id;
      mesh.userData.objectType = object_type;
      mesh.userData.assetId = asset_id;
    }
    
    return mesh;
  }

  updateMeshTransform(mesh, transform) {
    if (!mesh || !transform) return;
    
    if (transform.position) {
      mesh.position.set(
        transform.position.x,
        transform.position.y,
        transform.position.z
      );
    }
    
    if (transform.rotation) {
      mesh.rotation.set(
        transform.rotation.x,
        transform.rotation.y,
        transform.rotation.z
      );
    }
    
    if (transform.scale) {
      mesh.scale.set(
        transform.scale.x,
        transform.scale.y,
        transform.scale.z
      );
    }
  }

  cloneMesh(mesh) {
    if (!mesh) return null;
    
    const clonedMesh = mesh.clone();
    
    if (mesh.userData) {
      clonedMesh.userData = { ...mesh.userData };
    }
    
    return clonedMesh;
  }
}

const factoryRegistry = new FactoryRegistry();
export default factoryRegistry;
