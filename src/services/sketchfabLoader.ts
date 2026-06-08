import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/addons/loaders/DRACOLoader.js';
import type { SketchfabImportOptions, SketchfabImportResult } from '@/types/sketchfab';
import { sketchfabAPI } from './sketchfabAPI';

export class SketchfabModelLoader {
  private gltfLoader: GLTFLoader;
  private dracoLoader: DRACOLoader;
  private loadingCache: Map<string, THREE.Group> = new Map();

  constructor() {
    this.gltfLoader = new GLTFLoader();
    this.dracoLoader = new DRACOLoader();
    this.dracoLoader.setDecoderPath('https://www.gstatic.com/draco/versioned/decoders/1.5.6/');
    this.gltfLoader.setDRACOLoader(this.dracoLoader);
  }

  async loadFromSketchfab(
    uid: string,
    options: SketchfabImportOptions = {}
  ): Promise<SketchfabImportResult> {
    const cached = this.loadingCache.get(uid);
    if (cached) {
      return this.buildResult(uid, 'cached', cached.clone(), options);
    }

    const modelInfo = await sketchfabAPI.getModel(uid);
    const downloadUrl = await sketchfabAPI.getModelDownloadUrl(uid);

    if (!downloadUrl) {
      throw new Error('Model is not available for download');
    }

    const gltf = await this.gltfLoader.loadAsync(downloadUrl);
    const group = gltf.scene;

    this.loadingCache.set(uid, group.clone());

    return this.buildResult(uid, modelInfo.name, group, options);
  }

  private buildResult(
    uid: string,
    name: string,
    group: THREE.Group,
    options: SketchfabImportOptions
  ): SketchfabImportResult {
    const {
      autoScale = true,
      targetSize = 1.5,
      rotateX = 0,
      centerModel = true,
    } = options;

    group.traverse((obj) => {
      if ((obj as THREE.Mesh).isMesh) {
        const mesh = obj as THREE.Mesh;
        mesh.castShadow = true;
        mesh.receiveShadow = true;
      }
    });

    const bbox = new THREE.Box3().setFromObject(group);
    const originalSize = new THREE.Vector3();
    bbox.getSize(originalSize);

    let scaledSize = originalSize.clone();

    if (rotateX !== 0) {
      group.rotation.x = rotateX;
    }

    if (centerModel) {
      const center = new THREE.Vector3();
      bbox.getCenter(center);
      group.position.sub(center);
      group.position.y = 0;
    }

    if (autoScale && targetSize > 0) {
      const maxDim = Math.max(originalSize.x, originalSize.y, originalSize.z);
      if (maxDim > 0) {
        const scale = targetSize / maxDim;
        group.scale.setScalar(scale);
        scaledSize.multiplyScalar(scale);
      }
    }

    const finalBbox = new THREE.Box3().setFromObject(group);

    return {
      uid,
      name,
      group,
      boundingBox: finalBbox,
      originalSize,
      scaledSize,
    };
  }

  async loadFromUrl(
    url: string,
    name: string,
    options: SketchfabImportOptions = {}
  ): Promise<SketchfabImportResult> {
    const gltf = await this.gltfLoader.loadAsync(url);
    return this.buildResult('local', name, gltf.scene, options);
  }

  clearCache(): void {
    this.loadingCache.clear();
  }

  dispose(): void {
    this.dracoLoader.dispose();
    this.loadingCache.forEach((group) => {
      group.traverse((obj) => {
        const mesh = obj as THREE.Mesh;
        if (mesh.geometry) mesh.geometry.dispose();
        if (mesh.material) {
          const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
          materials.forEach((mat) => mat.dispose());
        }
      });
    });
    this.loadingCache.clear();
  }
}

export const sketchfabLoader = new SketchfabModelLoader();
