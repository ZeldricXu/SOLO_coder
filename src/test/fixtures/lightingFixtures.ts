import * as THREE from 'three';
import type { LightSource } from '@/types/floorplan';
import { generateId } from '@/utils/geometry';

export const createTestLightSource = (
  type: 'ambient' | 'point' | 'spot' | 'area',
  options: Partial<LightSource> = {}
): LightSource => {
  const baseConfig = {
    id: generateId(),
    type,
    position: { x: 0, y: 2.5, z: 0 },
    color: { r: 1, g: 1, b: 1 },
    intensity: 1.0,
    enabled: true,
    castShadow: type !== 'ambient',
    ...options,
  };

  const defaultParams: Record<string, any> = {};
  switch (type) {
    case 'point':
      defaultParams.distance = 10;
      defaultParams.decay = 2;
      break;
    case 'spot':
      baseConfig.position = { x: 0, y: 3, z: 0 };
      baseConfig.target = { x: 0, y: 0, z: 0 };
      defaultParams.angle = Math.PI / 6;
      defaultParams.penumbra = 0.3;
      defaultParams.distance = 15;
      defaultParams.decay = 2;
      break;
    case 'area':
      baseConfig.position = { x: 0, y: 2.8, z: 0 };
      defaultParams.width = 1;
      defaultParams.height = 0.2;
      break;
    case 'ambient':
    default:
      break;
  }

  return {
    ...baseConfig,
    params: {
      ...defaultParams,
      ...(options.params || {}),
    },
  } as LightSource;
};

export const createTestThreeLight = (
  type: 'ambient' | 'point' | 'spot' | 'area',
  color: number = 0xffffff,
  intensity: number = 1
): THREE.Light => {
  switch (type) {
    case 'ambient':
      return new THREE.AmbientLight(color, intensity);
    case 'point':
      return new THREE.PointLight(color, intensity, 10, 2);
    case 'spot':
      return new THREE.SpotLight(color, intensity, 15, Math.PI / 6, 0.3, 2);
    case 'area':
      return new THREE.RectAreaLight(color, intensity, 1, 0.2);
    default:
      return new THREE.AmbientLight(color, intensity);
  }
};

export const validateShadowSettings = (
  light: THREE.Light,
  expectedCastShadow: boolean
): { valid: boolean; errors: string[] } => {
  const errors: string[] = [];

  if ('castShadow' in light) {
    const castShadow = (light as THREE.PointLight | THREE.SpotLight).castShadow;
    if (castShadow !== expectedCastShadow) {
      errors.push(`castShadow mismatch: expected ${expectedCastShadow}, got ${castShadow}`);
    }

    if (expectedCastShadow && (light as THREE.PointLight | THREE.SpotLight).shadow) {
      const shadow = (light as THREE.PointLight | THREE.SpotLight).shadow;
      if (!shadow.mapSize) {
        errors.push('Shadow mapSize is not set');
      }
      if (shadow.bias === undefined) {
        errors.push('Shadow bias is not set');
      }
    }
  }

  return { valid: errors.length === 0, errors };
};

export const calculateShadowDirection = (
  lightPosition: THREE.Vector3,
  targetPosition: THREE.Vector3
): THREE.Vector3 => {
  const direction = new THREE.Vector3()
    .subVectors(targetPosition, lightPosition)
    .normalize();
  return direction;
};

export const validateLightIntensity = (
  light: THREE.Light,
  expectedIntensity: number,
  tolerance: number = 0.001
): boolean => {
  return Math.abs(light.intensity - expectedIntensity) <= tolerance;
};

export const validateLightColor = (
  light: THREE.Light,
  expectedColor: number,
  tolerance: number = 0.001
): boolean => {
  const expected = new THREE.Color(expectedColor);
  return (
    Math.abs(light.color.r - expected.r) <= tolerance &&
    Math.abs(light.color.g - expected.g) <= tolerance &&
    Math.abs(light.color.b - expected.b) <= tolerance
  );
};

export const createLightIntensityTestCases = () => [
  { input: 1.0, expected: 1.0, description: 'normal intensity' },
  { input: 0.5, expected: 0.5, description: 'half intensity' },
  { input: 2.0, expected: 2.0, description: 'double intensity' },
  { input: 0.0, expected: 0.0, description: 'zero intensity' },
  { input: -0.5, expected: 0.0, description: 'negative intensity should clamp to 0' },
];

export const createShadowTestScene = (): {
  scene: THREE.Scene;
  light: THREE.SpotLight;
  ground: THREE.Mesh;
  object: THREE.Mesh;
} => {
  const scene = new THREE.Scene();

  const groundGeometry = new THREE.PlaneGeometry(10, 10);
  const groundMaterial = new THREE.MeshStandardMaterial({ color: 0x808080 });
  const ground = new THREE.Mesh(groundGeometry, groundMaterial);
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);

  const objectGeometry = new THREE.BoxGeometry(1, 1, 1);
  const objectMaterial = new THREE.MeshStandardMaterial({ color: 0xff0000 });
  const object = new THREE.Mesh(objectGeometry, objectMaterial);
  object.position.set(0, 0.5, 0);
  object.castShadow = true;
  scene.add(object);

  const light = new THREE.SpotLight(0xffffff, 1, 10, Math.PI / 4, 0.1, 1);
  light.position.set(0, 5, 2);
  light.target.position.set(0, 0, 0);
  light.castShadow = true;
  light.shadow.mapSize.width = 1024;
  light.shadow.mapSize.height = 1024;
  scene.add(light);
  scene.add(light.target);

  return { scene, light, ground, object };
};

export const lightingTestFixtures = {
  createTestLightSource,
  createTestThreeLight,
  validateShadowSettings,
  calculateShadowDirection,
  validateLightIntensity,
  validateLightColor,
  createLightIntensityTestCases,
  createShadowTestScene,
};

export default lightingTestFixtures;
