import { Scene } from '../types/scene';
import { pack, unpack } from 'msgpackr';

export function serializeScene(scene: Scene): Uint8Array {
  const plainScene = {
    ...scene,
    metadata: { ...scene.metadata },
    objects: [...scene.objects],
    sensors: [...scene.sensors],
    simulationConfig: { ...scene.simulationConfig },
    fieldVisualizations: [...scene.fieldVisualizations],
    background: { ...scene.background },
    camera: { ...scene.camera },
  };
  return pack(plainScene);
}

export function deserializeScene(data: Uint8Array): Scene {
  return unpack(data) as Scene;
}

export function sceneToJSON(scene: Scene): string {
  return JSON.stringify(scene, null, 2);
}

export function sceneFromJSON(json: string): Scene {
  return JSON.parse(json) as Scene;
}

export function validateScene(scene: unknown): scene is Scene {
  if (typeof scene !== 'object' || scene === null) return false;
  const s = scene as Record<string, unknown>;
  return (
    typeof s.metadata === 'object' &&
    Array.isArray(s.objects) &&
    Array.isArray(s.sensors) &&
    typeof s.simulationConfig === 'object'
  );
}
