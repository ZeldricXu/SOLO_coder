import * as THREE from 'three';
import type { Material, PBRProperties } from '@/types/floorplan';

export class PBRMaterialFactory {
  private textureLoader: THREE.TextureLoader;

  constructor() {
    this.textureLoader = new THREE.TextureLoader();
  }

  createMaterial(materialData: Material): THREE.MeshStandardMaterial {
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

  createWireframeMaterial(): THREE.LineBasicMaterial {
    return new THREE.LineBasicMaterial({
      color: 0xff6b35,
      transparent: true,
      opacity: 0.8,
    });
  }

  createSelectionOutline(): THREE.MeshBasicMaterial {
    return new THREE.MeshBasicMaterial({
      color: 0xff6b35,
      transparent: true,
      opacity: 0.3,
      side: THREE.DoubleSide,
    });
  }

  createGlassMaterial(): THREE.MeshPhysicalMaterial {
    return new THREE.MeshPhysicalMaterial({
      color: 0xe0f0ff,
      metalness: 0,
      roughness: 0,
      transmission: 0.9,
      thickness: 0.05,
      transparent: true,
      opacity: 0.3,
      envMapIntensity: 1,
      clearcoat: 1,
      clearcoatRoughness: 0,
      ior: 1.5,
    });
  }

  createPreviewMaterial(): THREE.MeshStandardMaterial {
    return new THREE.MeshStandardMaterial({
      color: 0x00d4ff,
      transparent: true,
      opacity: 0.5,
      roughness: 0.5,
      metalness: 0,
    });
  }
}
