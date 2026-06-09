import * as THREE from 'three';
import type { Material, PBRProperties } from '@/types/floorplan';
import type { UIHelperMaterials } from '@/types/theme';
import { themeManager } from '@/lib/themeManager';

export class PBRMaterialFactory {
  private textureLoader: THREE.TextureLoader;
  private materialCache: Map<string, THREE.MeshStandardMaterial> = new Map();
  private materialRegistry: Map<string, Material> = new Map();

  constructor() {
    this.textureLoader = new THREE.TextureLoader();
    this.loadDefaultMaterials();
  }

  private loadDefaultMaterials(): void {
    const defaults = themeManager.getDefaultMaterials();
    defaults.forEach((m) => this.materialRegistry.set(m.id, m));
  }

  private getUIHelpers(): UIHelperMaterials {
    return themeManager.getCurrentTheme().materials.uiHelpers;
  }

  reloadMaterials(): void {
    this.clearCache();
    this.materialRegistry.clear();
    this.loadDefaultMaterials();
  }

  createMaterial(
    materialIdOrData: string | Material,
    overrideData?: Partial<Material>
  ): THREE.MeshStandardMaterial {
    let materialData: Material;

    if (typeof materialIdOrData === 'string') {
      const cached = this.materialCache.get(materialIdOrData);
      if (cached && !overrideData) {
        return cached;
      }

      const registered = this.materialRegistry.get(materialIdOrData);
      if (!registered) {
        throw new Error(`Material not found: ${materialIdOrData}`);
      }
      materialData = overrideData ? { ...registered, ...overrideData } : registered;
    } else {
      materialData = overrideData ? { ...materialIdOrData, ...overrideData } : materialIdOrData;
    }

    const cacheKey = typeof materialIdOrData === 'string' ? materialIdOrData : materialData.id;

    if (!overrideData && this.materialCache.has(cacheKey)) {
      return this.materialCache.get(cacheKey)!;
    }

    const properties = materialData.properties as PBRProperties;

    const color = new THREE.Color(
      properties.color.r,
      properties.color.g,
      properties.color.b
    );

    const materialParams: THREE.MeshStandardMaterialParameters = {
      color,
      roughness: properties.roughness,
      metalness: properties.metalness,
    };

    if (properties.emissive) {
      materialParams.emissive = new THREE.Color(
        properties.emissive.r,
        properties.emissive.g,
        properties.emissive.b
      );
      materialParams.emissiveIntensity = properties.emissiveIntensity || 1.0;
    }

    const material = new THREE.MeshStandardMaterial(materialParams);

    if (properties.normalMap) {
      this.loadTexture(properties.normalMap).then((tex) => {
        if (tex) {
          material.normalMap = tex;
          material.needsUpdate = true;
        }
      });
    }

    if (properties.roughnessMap) {
      this.loadTexture(properties.roughnessMap).then((tex) => {
        if (tex) {
          material.roughnessMap = tex;
          material.needsUpdate = true;
        }
      });
    }

    if (properties.metalnessMap) {
      this.loadTexture(properties.metalnessMap).then((tex) => {
        if (tex) {
          material.metalnessMap = tex;
          material.needsUpdate = true;
        }
      });
    }

    if (!overrideData) {
      this.materialCache.set(cacheKey, material);
    }

    return material;
  }

  private async loadTexture(path: string): Promise<THREE.Texture | null> {
    return new Promise((resolve) => {
      this.textureLoader.load(
        path,
        (texture) => {
          texture.wrapS = THREE.RepeatWrapping;
          texture.wrapT = THREE.RepeatWrapping;
          resolve(texture);
        },
        undefined,
        () => resolve(null)
      );
    });
  }

  getMaterialCache(): Map<string, THREE.MeshStandardMaterial> {
    return this.materialCache;
  }

  clearCache(): void {
    this.materialCache.forEach((mat) => mat.dispose());
    this.materialCache.clear();
  }

  registerMaterial(material: Material): void {
    this.materialRegistry.set(material.id, material);
  }

  createWireframeMaterial(): THREE.LineBasicMaterial {
    const helpers = this.getUIHelpers();
    return new THREE.LineBasicMaterial({
      color: new THREE.Color(helpers.wireframe.color),
      transparent: true,
      opacity: helpers.wireframe.opacity,
    });
  }

  createSelectionOutline(): THREE.MeshBasicMaterial {
    const helpers = this.getUIHelpers();
    return new THREE.MeshBasicMaterial({
      color: new THREE.Color(helpers.selectionOutline.color),
      transparent: true,
      opacity: helpers.selectionOutline.opacity,
      side: THREE.DoubleSide,
    });
  }

  createGlassMaterial(): THREE.MeshPhysicalMaterial {
    const helpers = this.getUIHelpers();
    return new THREE.MeshPhysicalMaterial({
      color: new THREE.Color(helpers.glass.color),
      metalness: 0,
      roughness: 0,
      transmission: helpers.glass.transmission,
      thickness: 0.05,
      transparent: true,
      opacity: helpers.glass.opacity,
      envMapIntensity: 1,
      clearcoat: 1,
      clearcoatRoughness: 0,
      ior: 1.5,
    });
  }

  createPreviewMaterial(): THREE.MeshStandardMaterial {
    const helpers = this.getUIHelpers();
    return new THREE.MeshStandardMaterial({
      color: new THREE.Color(helpers.preview.color),
      transparent: true,
      opacity: helpers.preview.opacity,
      roughness: 0.5,
      metalness: 0,
    });
  }
}
