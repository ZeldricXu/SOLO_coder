import * as THREE from 'three';

export type ObjectKind = 'static' | 'dynamic' | 'unknown';

export interface BakeOptions {
  resolution?: number;
  bias?: number;
  normalBias?: number;
  clearOld?: boolean;
}

export class LightmapManager {
  private scene: THREE.Scene;
  private renderer: THREE.WebGLRenderer | null = null;
  private lightmapCache: Map<string, THREE.Texture> = new Map();
  private isBaking: boolean = false;
  private bakeCompleteCallbacks: Set<() => void> = new Set();

  constructor(scene: THREE.Scene, renderer?: THREE.WebGLRenderer) {
    this.scene = scene;
    this.renderer = renderer || null;
  }

  setRenderer(renderer: THREE.WebGLRenderer): void {
    this.renderer = renderer;
  }

  getObjectKind(obj: THREE.Object3D): ObjectKind {
    const t = obj.userData?.type;
    if (t === 'wall' || t === 'floor' || t === 'ceiling') return 'static';
    if (t === 'door' || t === 'window') return 'static';
    if (t === 'furniture') {
      return obj.userData?.fixed === true ? 'static' : 'dynamic';
    }
    if (t === 'drawing-annotation') return 'dynamic';
    return 'unknown';
  }

  isStaticMesh(obj: THREE.Object3D): boolean {
    return this.getObjectKind(obj) === 'static';
  }

  isDynamicMesh(obj: THREE.Object3D): boolean {
    return this.getObjectKind(obj) === 'dynamic';
  }

  collectStaticMeshes(): THREE.Mesh[] {
    const meshes: THREE.Mesh[] = [];
    this.scene.traverse((obj) => {
      if (obj instanceof THREE.Mesh && this.isStaticMesh(obj)) {
        meshes.push(obj);
      }
    });
    return meshes;
  }

  collectDynamicMeshes(): THREE.Mesh[] {
    const meshes: THREE.Mesh[] = [];
    this.scene.traverse((obj) => {
      if (obj instanceof THREE.Mesh && this.isDynamicMesh(obj)) {
        meshes.push(obj);
      }
    });
    return meshes;
  }

  configureShadows(): void {
    this.scene.traverse((obj) => {
      if (obj instanceof THREE.Mesh) {
        const kind = this.getObjectKind(obj);
        if (kind === 'static') {
          obj.castShadow = true;
          obj.receiveShadow = true;
        } else if (kind === 'dynamic') {
          obj.castShadow = true;
          obj.receiveShadow = true;
        }
      }

      if (obj instanceof THREE.Light) {
        const light = obj as THREE.Light & { shadow?: THREE.LightShadow };
        if (light.shadow) {
          light.shadow.autoUpdate = true;
          light.shadow.needsUpdate = true;
        }
      }
    });
  }

  async bakeLightmaps(options: BakeOptions = {}): Promise<void> {
    if (this.isBaking) {
      throw new Error('Lightmap bake already in progress');
    }
    if (!this.renderer) {
      throw new Error('Renderer is required for lightmap baking');
    }

    this.isBaking = true;

    const {
      resolution = 1024,
      bias = -0.0001,
      normalBias = 0.02,
      clearOld = true,
    } = options;

    try {
      if (clearOld) {
        this.clearLightmapCache();
      }

      const staticMeshes = this.collectStaticMeshes();
      const lights = this.collectLights();

      for (const mesh of staticMeshes) {
        const cacheKey = `lm_${mesh.uuid}_${resolution}`;
        if (this.lightmapCache.has(cacheKey)) {
          (mesh.material as THREE.MeshStandardMaterial).lightMap = this.lightmapCache.get(cacheKey)!;
          continue;
        }

        const lightmap = this.bakeSingleMesh(mesh, lights, { resolution, bias, normalBias });
        if (lightmap) {
          this.lightmapCache.set(cacheKey, lightmap);
          const mat = mesh.material as THREE.MeshStandardMaterial;
          mat.lightMap = lightmap;
          mat.lightMapIntensity = 1.0;
          mat.needsUpdate = true;
        }
      }

      this.disableStaticShadowCasting();
      this.bakeCompleteCallbacks.forEach((cb) => cb());
    } finally {
      this.isBaking = false;
    }
  }

  private bakeSingleMesh(
    mesh: THREE.Mesh,
    lights: THREE.Light[],
    opts: { resolution: number; bias: number; normalBias: number }
  ): THREE.Texture | null {
    if (!this.renderer) return null;
    if (!mesh.geometry.attributes.uv) {
      mesh.geometry.setAttribute('uv', this.generateUVs(mesh.geometry));
    }

    const renderTarget = new THREE.WebGLRenderTarget(opts.resolution, opts.resolution, {
      type: THREE.HalfFloatType,
      magFilter: THREE.LinearFilter,
      minFilter: THREE.LinearFilter,
      generateMipmaps: true,
    });

    const oldAutoClear = this.renderer.autoClear;
    this.renderer.autoClear = true;

    const orthoCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 100);
    orthoCam.position.set(0, 10, 0);
    orthoCam.lookAt(0, 0, 0);

    const renderScene = new THREE.Scene();
    const renderMesh = mesh.clone();
    renderMesh.material = new THREE.MeshBasicMaterial({ color: 0xffffff });
    renderScene.add(renderMesh);
    lights.forEach((l) => renderScene.add(l.clone()));

    this.renderer.setRenderTarget(renderTarget);
    this.renderer.render(renderScene, orthoCam);
    this.renderer.setRenderTarget(null);

    this.renderer.autoClear = oldAutoClear;

    renderScene.clear();
    renderMesh.geometry.dispose();
    (renderMesh.material as THREE.Material).dispose();

    return renderTarget.texture;
  }

  private generateUVs(geometry: THREE.BufferGeometry): THREE.BufferAttribute {
    const pos = geometry.attributes.position;
    const count = pos.count;
    const uvs = new Float32Array(count * 2);

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (let i = 0; i < count; i++) {
      minX = Math.min(minX, pos.getX(i));
      minY = Math.min(minY, pos.getY(i));
      maxX = Math.max(maxX, pos.getX(i));
      maxY = Math.max(maxY, pos.getY(i));
    }

    const rangeX = maxX - minX || 1;
    const rangeY = maxY - minY || 1;

    for (let i = 0; i < count; i++) {
      uvs[i * 2] = (pos.getX(i) - minX) / rangeX;
      uvs[i * 2 + 1] = (pos.getY(i) - minY) / rangeY;
    }

    return new THREE.BufferAttribute(uvs, 2);
  }

  private collectLights(): THREE.Light[] {
    const lights: THREE.Light[] = [];
    this.scene.traverse((obj) => {
      if (obj instanceof THREE.Light && obj.userData.lightId !== 'default-ambient') {
        lights.push(obj);
      }
    });
    return lights;
  }

  private disableStaticShadowCasting(): void {
    this.scene.traverse((obj) => {
      if (obj instanceof THREE.Mesh && this.isStaticMesh(obj)) {
        obj.castShadow = false;
      }
    });
  }

  enableDynamicShadowCasting(): void {
    this.scene.traverse((obj) => {
      if (obj instanceof THREE.Mesh && this.isDynamicMesh(obj)) {
        obj.castShadow = true;
      }
    });
  }

  clearLightmapCache(): void {
    this.lightmapCache.forEach((tex) => tex.dispose());
    this.lightmapCache.clear();
  }

  invalidate(mesh?: THREE.Mesh): void {
    if (mesh) {
      this.lightmapCache.forEach((tex, key) => {
        if (key.includes(mesh.uuid)) {
          tex.dispose();
          this.lightmapCache.delete(key);
        }
      });
    } else {
      this.clearLightmapCache();
    }
  }

  onBakeComplete(callback: () => void): () => void {
    this.bakeCompleteCallbacks.add(callback);
    return () => this.bakeCompleteCallbacks.delete(callback);
  }

  getCacheSize(): number {
    return this.lightmapCache.size;
  }

  getIsBaking(): boolean {
    return this.isBaking;
  }

  dispose(): void {
    this.clearLightmapCache();
    this.bakeCompleteCallbacks.clear();
  }
}
