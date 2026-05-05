class MessageCompressor {
  constructor() {
    this.fieldAliases = {
      object_id: 'oid',
      operation_type: 'ot',
      operation_id: 'opid',
      target_object: 'to',
      parameters: 'p',
      transform: 't',
      position: 'pos',
      rotation: 'rot',
      scale: 's',
      material_id: 'mid',
      asset_id: 'aid',
      object_type: 'objt',
      user_id: 'uid',
      version: 'v',
      timestamp: 'ts',
      x: 'x',
      y: 'y',
      z: 'z',
      scene_id: 'sid',
      users: 'u',
      current_version: 'cv',
      objects: 'objs',
      name: 'n',
      user_name: 'un'
    };
    
    this.fieldUnAliases = this.invertMap(this.fieldAliases);
    
    this.operationTypeAliases = {
      object_create: 'c',
      transform_update: 'tu',
      object_delete: 'd',
      material_update: 'mu',
      lock_acquire: 'la',
      lock_release: 'lr'
    };
    
    this.operationTypeUnAliases = this.invertMap(this.operationTypeAliases);
    
    this.objectTypeAliases = {
      wall: 'w',
      door: 'd',
      window: 'wn',
      furniture: 'f'
    };
    
    this.objectTypeUnAliases = this.invertMap(this.objectTypeAliases);
  }

  invertMap(map) {
    const inverted = {};
    for (const [key, value] of Object.entries(map)) {
      inverted[value] = key;
    }
    return inverted;
  }

  compress(message) {
    if (typeof message !== 'object' || message === null) {
      return message;
    }
    
    if (Array.isArray(message)) {
      return message.map(item => this.compress(item));
    }
    
    const compressed = {};
    
    for (const [key, value] of Object.entries(message)) {
      const newKey = this.fieldAliases[key] || key;
      
      if (key === 'operation_type' && typeof value === 'string') {
        compressed[newKey] = this.operationTypeAliases[value] || value;
      } else if (key === 'object_type' && typeof value === 'string') {
        compressed[newKey] = this.objectTypeAliases[value] || value;
      } else if (typeof value === 'object' && value !== null) {
        compressed[newKey] = this.compress(value);
      } else if (typeof value === 'number' && !Number.isInteger(value)) {
        compressed[newKey] = Math.round(value * 1000) / 1000;
      } else {
        compressed[newKey] = value;
      }
    }
    
    return compressed;
  }

  decompress(message) {
    if (typeof message !== 'object' || message === null) {
      return message;
    }
    
    if (Array.isArray(message)) {
      return message.map(item => this.decompress(item));
    }
    
    const decompressed = {};
    
    for (const [key, value] of Object.entries(message)) {
      const newKey = this.fieldUnAliases[key] || key;
      
      if (newKey === 'operation_type' && typeof value === 'string') {
        decompressed[newKey] = this.operationTypeUnAliases[value] || value;
      } else if (newKey === 'object_type' && typeof value === 'string') {
        decompressed[newKey] = this.objectTypeUnAliases[value] || value;
      } else if (typeof value === 'object' && value !== null) {
        decompressed[newKey] = this.decompress(value);
      } else {
        decompressed[newKey] = value;
      }
    }
    
    return decompressed;
  }

  compressTransform(transform) {
    if (!transform) return null;
    
    const compressed = {};
    
    if (transform.position) {
      compressed.pos = {
        x: this.roundFloat(transform.position.x),
        y: this.roundFloat(transform.position.y),
        z: this.roundFloat(transform.position.z)
      };
    }
    
    if (transform.rotation) {
      compressed.rot = {
        x: this.roundFloat(transform.rotation.x),
        y: this.roundFloat(transform.rotation.y),
        z: this.roundFloat(transform.rotation.z)
      };
    }
    
    if (transform.scale) {
      compressed.s = {
        x: this.roundFloat(transform.scale.x),
        y: this.roundFloat(transform.scale.y),
        z: this.roundFloat(transform.scale.z)
      };
    }
    
    return compressed;
  }

  decompressTransform(compressedTransform) {
    if (!compressedTransform) return null;
    
    const transform = {};
    
    if (compressedTransform.pos) {
      transform.position = {
        x: compressedTransform.pos.x,
        y: compressedTransform.pos.y,
        z: compressedTransform.pos.z
      };
    }
    
    if (compressedTransform.rot) {
      transform.rotation = {
        x: compressedTransform.rot.x,
        y: compressedTransform.rot.y,
        z: compressedTransform.rot.z
      };
    }
    
    if (compressedTransform.s) {
      transform.scale = {
        x: compressedTransform.s.x,
        y: compressedTransform.s.y,
        z: compressedTransform.s.z
      };
    }
    
    return transform;
  }

  roundFloat(value, precision = 3) {
    const factor = Math.pow(10, precision);
    return Math.round(value * factor) / factor;
  }

  createDeltaMessage(original, modified) {
    const delta = {};
    
    if (original.transform && modified.transform) {
      const transformDelta = {};
      
      if (!this.arePositionsEqual(original.transform.position, modified.transform.position)) {
        transformDelta.position = modified.transform.position;
      }
      
      if (!this.areRotationsEqual(original.transform.rotation, modified.transform.rotation)) {
        transformDelta.rotation = modified.transform.rotation;
      }
      
      if (!this.areScalesEqual(original.transform.scale, modified.transform.scale)) {
        transformDelta.scale = modified.transform.scale;
      }
      
      if (Object.keys(transformDelta).length > 0) {
        delta.transform = transformDelta;
      }
    }
    
    if (original.material_id !== modified.material_id) {
      delta.material_id = modified.material_id;
    }
    
    return delta;
  }

  arePositionsEqual(pos1, pos2) {
    if (!pos1 || !pos2) return false;
    return this.roundFloat(pos1.x) === this.roundFloat(pos2.x) &&
           this.roundFloat(pos1.y) === this.roundFloat(pos2.y) &&
           this.roundFloat(pos1.z) === this.roundFloat(pos2.z);
  }

  areRotationsEqual(rot1, rot2) {
    if (!rot1 || !rot2) return false;
    return this.roundFloat(rot1.x) === this.roundFloat(rot2.x) &&
           this.roundFloat(rot1.y) === this.roundFloat(rot2.y) &&
           this.roundFloat(rot1.z) === this.roundFloat(rot2.z);
  }

  areScalesEqual(scale1, scale2) {
    if (!scale1 || !scale2) return false;
    return this.roundFloat(scale1.x) === this.roundFloat(scale2.x) &&
           this.roundFloat(scale1.y) === this.roundFloat(scale2.y) &&
           this.roundFloat(scale1.z) === this.roundFloat(scale2.z);
  }

  estimateSize(message) {
    return JSON.stringify(message).length;
  }

  getCompressionRatio(original, compressed) {
    const originalSize = this.estimateSize(original);
    const compressedSize = this.estimateSize(compressed);
    return {
      originalSize,
      compressedSize,
      ratio: originalSize > 0 ? ((1 - compressedSize / originalSize) * 100).toFixed(2) : 0
    };
  }
}

const messageCompressor = new MessageCompressor();
export default messageCompressor;
