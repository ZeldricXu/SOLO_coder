import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { WallBuilder } from '@/engine/scene/WallBuilder';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';
import { createWallGeometry, createOpeningCutter, cutOpeningInWall } from '@/utils/csg';
import { DEFAULT_MATERIALS } from '@/types/materials';
import {
  createTestOpening,
  createTestWallWithOpenings,
  createTestMaterial,
  validateWallGeometry,
  validateNormals,
  getPBRMaterialParams,
} from '../fixtures/sceneFixtures';
import {
  createTestWall,
  createTestPoint,
} from '../fixtures/drawingFixtures';

describe('3D场景管理器 - 正常路径测试', () => {
  describe('2D平面图拉伸成3D墙体', () => {
    let wallBuilder: WallBuilder;
    let materialFactory: PBRMaterialFactory;

    beforeEach(() => {
      wallBuilder = new WallBuilder();
      materialFactory = new PBRMaterialFactory();
    });

    it('应该正确生成3D墙体的BoxGeometry', () => {
      const start = new THREE.Vector3(0, 0, 0);
      const end = new THREE.Vector3(5, 0, 0);
      const thickness = 0.2;
      const height = 2.8;

      const wallMesh = createWallGeometry(start, end, thickness, height);

      expect(wallMesh).toBeInstanceOf(THREE.Mesh);
      expect(wallMesh.geometry).toBeInstanceOf(THREE.BoxGeometry);

      const validation = validateWallGeometry(wallMesh, 5, 2.8, 0.2);
      expect(validation.valid).toBe(true);
      expect(validation.errors).toHaveLength(0);
    });

    it('应该正确设置3D墙体的位置和旋转', () => {
      const start = new THREE.Vector3(0, 0, 0);
      const end = new THREE.Vector3(5, 3, 0);

      const wallMesh = createWallGeometry(start, end, 0.2, 2.8);

      const midX = (start.x + end.x) / 2;
      const midZ = (start.z + end.z) / 2;
      expect(wallMesh.position.x).toBeCloseTo(midX);
      expect(wallMesh.position.y).toBeCloseTo(1.4);
      expect(wallMesh.position.z).toBeCloseTo(midZ);
    });

    it('应该正确生成墙体的法线向量', () => {
      const start = new THREE.Vector3(0, 0, 0);
      const end = new THREE.Vector3(5, 0, 0);

      const wallMesh = createWallGeometry(start, end, 0.2, 2.8);

      const normalsValid = validateNormals(wallMesh.geometry as THREE.BufferGeometry);
      expect(normalsValid).toBe(true);
    });

    it('应该正确计算墙体的顶点数量', () => {
      const start = new THREE.Vector3(0, 0, 0);
      const end = new THREE.Vector3(5, 0, 0);

      const wallMesh = createWallGeometry(start, end, 0.2, 2.8);
      const geometry = wallMesh.geometry as THREE.BufferGeometry;

      const positionAttr = geometry.getAttribute('position');
      expect(positionAttr.count).toBe(24);

      const normalAttr = geometry.getAttribute('normal');
      expect(normalAttr.count).toBe(24);
    });
  });

  describe('门窗CSG布尔开洞', () => {
    it('应该在墙体上正确切出门洞', () => {
      const start = new THREE.Vector3(0, 0, 0);
      const end = new THREE.Vector3(5, 0, 0);
      const wallMesh = createWallGeometry(start, end, 0.2, 2.8);

      const doorCutter = createOpeningCutter(
        new THREE.Vector3(2, 0, 0),
        0.9,
        2.1,
        0.3
      );

      const wallWithDoor = cutOpeningInWall(wallMesh, doorCutter);

      expect(wallWithDoor).toBeInstanceOf(THREE.Mesh);
      expect(wallWithDoor.geometry.getAttribute).toBeDefined();

      const positionAttr = wallWithDoor.geometry.getAttribute('position');
      expect(positionAttr).toBeDefined();
      expect(positionAttr.count).toBeGreaterThan(24);
    });

    it('应该在墙体上正确切出窗洞', () => {
      const start = new THREE.Vector3(0, 0, 0);
      const end = new THREE.Vector3(5, 0, 0);
      const wallMesh = createWallGeometry(start, end, 0.2, 2.8);

      const windowCutter = createOpeningCutter(
        new THREE.Vector3(3, 0.9, 0),
        1.2,
        1.5,
        0.3
      );

      const wallWithWindow = cutOpeningInWall(wallMesh, windowCutter);

      expect(wallWithWindow).toBeInstanceOf(THREE.Mesh);

      const geometry = wallWithWindow.geometry as THREE.BufferGeometry;
      const positionAttr = geometry.getAttribute('position');
      expect(positionAttr.count).toBeGreaterThan(0);
    });

    it('应该正确处理墙体上的多个开口', () => {
      const { wall, openings } = createTestWallWithOpenings();
      const start = new THREE.Vector3(wall.start.x, 0, wall.start.y);
      const end = new THREE.Vector3(wall.end.x, 0, wall.end.y);
      let wallMesh = createWallGeometry(start, end, wall.thickness, wall.height);

      for (const opening of openings) {
        const cutter = createOpeningCutter(
          new THREE.Vector3(opening.positionX, opening.sillHeight, 0),
          opening.width,
          opening.height,
          wall.thickness + 0.1
        );
        wallMesh = cutOpeningInWall(wallMesh, cutter);
      }

      expect(wallMesh).toBeInstanceOf(THREE.Mesh);
      const geometry = wallMesh.geometry as THREE.BufferGeometry;
      const positionAttr = geometry.getAttribute('position');
      expect(positionAttr.count).toBeGreaterThan(24);
    });
  });

  describe('PBR材质系统', () => {
    let materialFactory: PBRMaterialFactory;

    beforeEach(() => {
      materialFactory = new PBRMaterialFactory();
    });

    it('应该正确应用roughness参数到MeshStandardMaterial', () => {
      const materialData = DEFAULT_MATERIALS[0];
      const material = materialFactory.createMaterial(materialData.id);

      expect(material).toBeInstanceOf(THREE.MeshStandardMaterial);
      
      const params = getPBRMaterialParams(material as THREE.MeshStandardMaterial);
      expect(params.roughness).toBeCloseTo((materialData.properties as any).roughness);
    });

    it('应该正确应用metalness参数到MeshStandardMaterial', () => {
      const metalMaterial = DEFAULT_MATERIALS.find((m) => (m.properties as any).metalness > 0.5);
      expect(metalMaterial).toBeDefined();

      if (metalMaterial) {
        const material = materialFactory.createMaterial(metalMaterial.id);
        const params = getPBRMaterialParams(material as THREE.MeshStandardMaterial);
        expect(params.metalness).toBeCloseTo((metalMaterial.properties as any).metalness);
      }
    });

    it('应该正确应用颜色参数', () => {
      const materialData = DEFAULT_MATERIALS[0];
      const material = materialFactory.createMaterial(materialData.id);
      const params = getPBRMaterialParams(material as THREE.MeshStandardMaterial);

      const props = materialData.properties as any;
      const expectedColor = new THREE.Color(props.color.r, props.color.g, props.color.b).getHex();
      expect(params.color).toBe(expectedColor);
    });

    it('应该实现材质缓存，避免重复创建', () => {
      const materialData = DEFAULT_MATERIALS[0];
      const material1 = materialFactory.createMaterial(materialData.id);
      const material2 = materialFactory.createMaterial(materialData.id);

      expect(material1).toBe(material2);
    });

    it('应该正确处理带纹理的材质创建', () => {
      const materialData: any = {
        id: 'test-textured',
        name: '测试纹理材质',
        type: 'pbr',
        properties: {
          color: { r: 1, g: 0, b: 0 },
          roughness: 0.3,
          metalness: 0.7,
          normalMap: 'test-normal.jpg',
          roughnessMap: 'test-roughness.jpg',
          metalnessMap: 'test-metalness.jpg',
        },
      };

      materialFactory.registerMaterial(materialData);

      const loadTextureMock = vi
        .spyOn(THREE.TextureLoader.prototype, 'load')
        .mockImplementation((url: string, onLoad?: (texture: THREE.Texture) => void) => {
          onLoad?.(new THREE.Texture());
          return new THREE.Texture();
        });

      const material = materialFactory.createMaterial(materialData.id);
      const params = getPBRMaterialParams(material as THREE.MeshStandardMaterial);

      expect(params.normalMap).toBeDefined();
      expect(params.roughnessMap).toBeDefined();
      expect(params.metalnessMap).toBeDefined();

      loadTextureMock.mockRestore();
    });
  });

  describe('地板和天花板生成', () => {
    let wallBuilder: WallBuilder;

    beforeEach(() => {
      wallBuilder = new WallBuilder();
    });

    it('应该正确生成地板几何体', () => {
      const boundary = [
        { x: 0, y: 0 },
        { x: 5, y: 0 },
        { x: 5, y: 4 },
        { x: 0, y: 4 },
      ];

      const floorMesh = wallBuilder.buildFloor(boundary, 'mat-floor-wood', []);

      expect(floorMesh).toBeInstanceOf(THREE.Mesh);
      expect(floorMesh.geometry).toBeInstanceOf(THREE.ExtrudeGeometry);
      expect(floorMesh.receiveShadow).toBe(true);
    });

    it('应该正确生成天花板几何体', () => {
      const boundary = [
        { x: 0, y: 0 },
        { x: 5, y: 0 },
        { x: 5, y: 4 },
        { x: 0, y: 4 },
      ];

      const ceilingMesh = wallBuilder.buildCeiling(boundary, 2.8, 'mat-ceiling-white', []);

      expect(ceilingMesh).toBeInstanceOf(THREE.Mesh);
      expect(ceilingMesh.position.y).toBeCloseTo(2.8);
    });
  });
});

describe('3D场景管理器 - 边界条件测试', () => {
  it('应该正确处理零长度墙体', () => {
    const start = new THREE.Vector3(0, 0, 0);
    const end = new THREE.Vector3(0, 0, 0);

    const wallMesh = createWallGeometry(start, end, 0.2, 2.8);
    const geometry = wallMesh.geometry as THREE.BoxGeometry;

    expect(geometry.parameters.width).toBeCloseTo(0);
  });

  it('应该正确处理负高度墙体（取绝对值）', () => {
    const start = new THREE.Vector3(0, 0, 0);
    const end = new THREE.Vector3(5, 0, 0);

    const wallMesh = createWallGeometry(start, end, 0.2, -2.8);
    const geometry = wallMesh.geometry as THREE.BoxGeometry;

    expect(geometry.parameters.height).toBeGreaterThan(0);
  });

  it('应该正确处理超厚墙体', () => {
    const start = new THREE.Vector3(0, 0, 0);
    const end = new THREE.Vector3(5, 0, 0);

    const wallMesh = createWallGeometry(start, end, 1.0, 2.8);
    const validation = validateWallGeometry(wallMesh, 5, 2.8, 1.0);

    expect(validation.valid).toBe(true);
  });
});
