import * as THREE from 'three';
import { RectAreaLightHelper } from 'three/addons/helpers/RectAreaLightHelper.js';
import type { LightSource } from '@/types/floorplan';

export class LightManager {
  private scene: THREE.Scene;
  private lights: Map<string, THREE.Light> = new Map();
  private helpers: Map<string, THREE.Object3D> = new Map();
  private showHelpers: boolean = true;

  constructor(scene: THREE.Scene) {
    this.scene = scene;
  }

  addLight(lightData: LightSource): void {
    let light: THREE.Light | null = null;
    const color = new THREE.Color(
      lightData.color.r,
      lightData.color.g,
      lightData.color.b
    );

    switch (lightData.type) {
      case 'point':
        light = new THREE.PointLight(
          color,
          lightData.intensity,
          lightData.params.distance || 0,
          lightData.params.decay || 2
        );
        break;
      case 'spot':
        light = new THREE.SpotLight(
          color,
          lightData.intensity,
          lightData.params.distance || 0,
          (lightData.params.angle || 45) * Math.PI / 180,
          lightData.params.penumbra || 0.3,
          lightData.params.decay || 2
        );
        break;
      case 'area':
        light = new THREE.RectAreaLight(
          color,
          lightData.intensity,
          lightData.params.width || 1,
          lightData.params.height || 1
        );
        break;
      case 'ambient':
        light = new THREE.AmbientLight(color, lightData.intensity);
        break;
    }

    if (!light) return;

    light.position.set(lightData.position.x, lightData.position.y, lightData.position.z);
    light.castShadow = lightData.castShadow;
    light.name = `light-${lightData.id}`;
    light.userData = { lightId: lightData.id };

    if (lightData.target && light instanceof THREE.SpotLight) {
      light.target.position.set(
        lightData.target.x,
        lightData.target.y,
        lightData.target.z
      );
      this.scene.add(light.target);
    }

    if (light instanceof THREE.PointLight || light instanceof THREE.SpotLight) {
      light.shadow.mapSize.width = 2048;
      light.shadow.mapSize.height = 2048;
      light.shadow.bias = -0.0001;
      light.shadow.normalBias = 0.02;
    }

    this.scene.add(light);
    this.lights.set(lightData.id, light);

    if (this.showHelpers) {
      this.createHelper(lightData, light);
    }
  }

  updateLight(lightData: LightSource): void {
    const light = this.lights.get(lightData.id);
    if (!light) return;

    const color = new THREE.Color(
      lightData.color.r,
      lightData.color.g,
      lightData.color.b
    );

    light.color.copy(color);
    light.intensity = lightData.intensity;
    light.position.set(lightData.position.x, lightData.position.y, lightData.position.z);
    light.castShadow = lightData.castShadow;

    if (light instanceof THREE.PointLight) {
      light.distance = lightData.params.distance || 0;
      light.decay = lightData.params.decay || 2;
    }

    if (light instanceof THREE.SpotLight) {
      light.distance = lightData.params.distance || 0;
      light.angle = (lightData.params.angle || 45) * Math.PI / 180;
      light.penumbra = lightData.params.penumbra || 0.3;
      light.decay = lightData.params.decay || 2;

      if (lightData.target) {
        light.target.position.set(
          lightData.target.x,
          lightData.target.y,
          lightData.target.z
        );
      }
    }

    if (light instanceof THREE.RectAreaLight) {
      light.width = lightData.params.width || 1;
      light.height = lightData.params.height || 1;
    }

    if (this.showHelpers) {
      this.removeHelper(lightData.id);
      this.createHelper(lightData, light);
    }
  }

  removeLight(lightId: string): void {
    const light = this.lights.get(lightId);
    if (light) {
      this.scene.remove(light);
      if (light instanceof THREE.SpotLight && light.target) {
        this.scene.remove(light.target);
      }
      this.lights.delete(lightId);
    }
    this.removeHelper(lightId);
  }

  clearAll(): void {
    this.lights.forEach((light, id) => {
      this.scene.remove(light);
      this.removeHelper(id);
    });
    this.lights.clear();
    this.helpers.clear();
  }

  private createHelper(lightData: LightSource, light: THREE.Light): void {
    let helper: THREE.Object3D | null = null;

    if (light instanceof THREE.PointLight) {
      helper = new THREE.PointLightHelper(light, 0.3);
    } else if (light instanceof THREE.SpotLight) {
      helper = new THREE.SpotLightHelper(light);
    } else if (light instanceof THREE.RectAreaLight) {
      helper = new RectAreaLightHelper(light);
    }

    if (helper) {
      helper.name = `helper-${lightData.id}`;
      this.scene.add(helper);
      this.helpers.set(lightData.id, helper);
    }
  }

  private removeHelper(lightId: string): void {
    const helper = this.helpers.get(lightId);
    if (helper) {
      this.scene.remove(helper);
      this.helpers.delete(lightId);
    }
  }

  setShowHelpers(show: boolean): void {
    this.showHelpers = show;
    this.helpers.forEach((helper) => {
      helper.visible = show;
    });
  }

  updateHelpers(): void {
    this.helpers.forEach((helper) => {
      if (helper instanceof THREE.PointLightHelper || helper instanceof THREE.SpotLightHelper) {
        helper.update();
      }
    });
  }

  getLight(lightId: string): THREE.Light | undefined {
    return this.lights.get(lightId);
  }

  setShadowQuality(quality: 'low' | 'medium' | 'high'): void {
    const sizeMap = {
      low: 512,
      medium: 1024,
      high: 2048,
    };
    const size = sizeMap[quality];

    this.lights.forEach((light) => {
      if (light instanceof THREE.PointLight || light instanceof THREE.SpotLight) {
        light.shadow.mapSize.width = size;
        light.shadow.mapSize.height = size;
        if (light.shadow.map) {
          light.shadow.map.dispose();
          light.shadow.map = null;
        }
      }
    });
  }

  createDefaultLights(): void {
    const ambient: LightSource = {
      id: 'default-ambient',
      type: 'ambient',
      name: '环境光',
      position: { x: 0, y: 5, z: 0 },
      color: { r: 0.7, g: 0.75, b: 0.85 },
      intensity: 0.4,
      castShadow: false,
      params: {},
    };

    const main: LightSource = {
      id: 'default-main',
      type: 'spot',
      name: '主光源',
      position: { x: 5, y: 8, z: 5 },
      target: { x: 0, y: 0, z: 0 },
      color: { r: 1.0, g: 0.95, b: 0.85 },
      intensity: 1.0,
      castShadow: true,
      params: {
        angle: 45,
        penumbra: 0.5,
        distance: 30,
        decay: 2,
      },
    };

    const fill: LightSource = {
      id: 'default-fill',
      type: 'point',
      name: '补光',
      position: { x: -5, y: 4, z: -5 },
      color: { r: 0.8, g: 0.85, b: 1.0 },
      intensity: 0.3,
      castShadow: false,
      params: {
        distance: 20,
        decay: 2,
      },
    };

    this.addLight(ambient);
    this.addLight(main);
    this.addLight(fill);
  }
}
