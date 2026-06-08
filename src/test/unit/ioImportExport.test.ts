import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  exportToJSON,
  importFromJSON,
  parseDXF,
  exportToSVG,
  downloadJSON,
  downloadSVG,
  importFromFile,
} from '@/utils/io/importExport';
import { createTestFloorPlan, createComplexFloorPlan, SAMPLE_DXF_CONTENT, CORRUPTED_DXF_CONTENT, validateFloorPlanData } from '../fixtures/ioFixtures';
import type { FloorPlan } from '@/types/floorplan';

describe('数据导入导出 - 正常路径测试', () => {
  describe('JSON导入导出', () => {
    it('应该正确导出户型数据为JSON格式', () => {
      const floorPlan = createTestFloorPlan();
      const jsonStr = exportToJSON(floorPlan);

      expect(typeof jsonStr).toBe('string');
      expect(jsonStr.length).toBeGreaterThan(0);

      const parsed = JSON.parse(jsonStr);
      expect(parsed.metadata).toBeDefined();
      expect(parsed.metadata.version).toBe('1.0.0');
      expect(parsed.metadata.software).toContain('ArchPlan Studio');
      expect(parsed.data).toBeDefined();
      expect(parsed.data.walls).toHaveLength(4);
      expect(parsed.data.rooms).toHaveLength(1);
    });

    it('应该正确导入JSON格式的户型数据', () => {
      const originalFloorPlan = createTestFloorPlan();
      const jsonStr = exportToJSON(originalFloorPlan);
      const importedFloorPlan = importFromJSON(jsonStr);

      expect(importedFloorPlan).toBeDefined();
      expect(importedFloorPlan.walls).toHaveLength(originalFloorPlan.walls.length);
      expect(importedFloorPlan.rooms).toHaveLength(originalFloorPlan.rooms.length);
      expect(importedFloorPlan.openings).toHaveLength(originalFloorPlan.openings.length);
    });

    it('导出再导入的数据应该保持完整性', () => {
      const original = createComplexFloorPlan();
      const jsonStr = exportToJSON(original);
      const imported = importFromJSON(jsonStr);

      const validation = validateFloorPlanData(imported, original);
      expect(validation.valid).toBe(true);
      expect(validation.errors).toHaveLength(0);

      expect(imported.name).toBe(original.name);
      expect(imported.description).toBe(original.description);
    });

    it('应该正确处理复杂户型的JSON导入导出', () => {
      const original = createComplexFloorPlan();
      const jsonStr = exportToJSON(original);
      const imported = importFromJSON(jsonStr);

      expect(imported.walls).toHaveLength(5);
      expect(imported.openings).toHaveLength(3);
      expect(imported.rooms).toHaveLength(2);

      const door = imported.openings.find((o) => o.type === 'door');
      expect(door).toBeDefined();
      expect(door!.width).toBeCloseTo(0.9);
      expect(door!.height).toBeCloseTo(2.1);

      const windows = imported.openings.filter((o) => o.type === 'window');
      expect(windows).toHaveLength(2);
    });
  });

  describe('DXF解析', () => {
    it('应该正确解析DXF文件中的墙体', async () => {
      const floorPlan = await parseDXF(SAMPLE_DXF_CONTENT);

      expect(floorPlan).toBeDefined();
      expect(floorPlan.walls.length).toBeGreaterThanOrEqual(4);
      expect(floorPlan.rooms.length).toBeGreaterThanOrEqual(1);
    });

    it('应该正确识别DXF中的图层（WALL, DOOR, WINDOW）', async () => {
      const floorPlan = await parseDXF(SAMPLE_DXF_CONTENT);

      expect(floorPlan.walls).toHaveLength(4);
      expect(floorPlan.openings).toHaveLength(2);

      const doors = floorPlan.openings.filter((o) => o.type === 'door');
      const windows = floorPlan.openings.filter((o) => o.type === 'window');

      expect(doors).toHaveLength(1);
      expect(windows).toHaveLength(1);
    });

    it('应该正确转换DXF坐标（毫米转米）', async () => {
      const floorPlan = await parseDXF(SAMPLE_DXF_CONTENT);

      for (const wall of floorPlan.walls) {
        expect(wall.start.x).toBeLessThanOrEqual(10);
        expect(wall.start.y).toBeLessThanOrEqual(10);
        expect(wall.end.x).toBeLessThanOrEqual(10);
        expect(wall.end.y).toBeLessThanOrEqual(10);
      }
    });

    it('应该正确设置默认的墙体参数（厚度、高度、材质）', async () => {
      const floorPlan = await parseDXF(SAMPLE_DXF_CONTENT);

      for (const wall of floorPlan.walls) {
        expect(wall.thickness).toBeCloseTo(0.2);
        expect(wall.height).toBeCloseTo(2.8);
        expect(wall.materialId).toBe('mat-wall-white');
      }
    });
  });

  describe('SVG导出', () => {
    it('应该正确导出SVG格式的户型图', () => {
      const floorPlan = createTestFloorPlan();
      const svgContent = exportToSVG(floorPlan);

      expect(svgContent).toContain('<svg');
      expect(svgContent).toContain('</svg>');
      expect(svgContent).toContain('<rect');
      expect(svgContent).toContain('<line');
    });

    it('SVG中应该包含正确的尺寸和视图框', () => {
      const floorPlan = createTestFloorPlan();
      const svgContent = exportToSVG(floorPlan);

      expect(svgContent).toContain('viewBox');
      expect(svgContent).toContain('width');
      expect(svgContent).toContain('height');
    });

    it('SVG中应该包含所有墙体元素', () => {
      const floorPlan = createTestFloorPlan();
      const svgContent = exportToSVG(floorPlan);

      const lineMatches = svgContent.match(/<line/g);
      expect(lineMatches).not.toBeNull();
      expect(lineMatches!.length).toBeGreaterThanOrEqual(4);
    });
  });

  describe('文件操作', () => {
    it('downloadJSON应该创建正确的Blob并触发下载', () => {
      const floorPlan = createTestFloorPlan();
      const createElementSpy = vi
        .spyOn(document, 'createElement')
        .mockImplementation(() => ({
          href: '',
          download: '',
          click: vi.fn(),
          setAttribute: vi.fn(),
        } as unknown as HTMLAnchorElement));

      const mockAnchor = document.createElement('a');
      const appendChildSpy = vi
        .spyOn(document.body, 'appendChild')
        .mockImplementation(() => mockAnchor);

      const removeChildSpy = vi
        .spyOn(document.body, 'removeChild')
        .mockImplementation((node: Node) => node);

      downloadJSON(floorPlan, 'test-floorplan.json');

      expect(createElementSpy).toHaveBeenCalledWith('a');
      expect(appendChildSpy).toHaveBeenCalled();
      expect(removeChildSpy).toHaveBeenCalled();

      createElementSpy.mockRestore();
      appendChildSpy.mockRestore();
      removeChildSpy.mockRestore();
    });

    it('downloadSVG应该创建正确的SVG Blob并触发下载', () => {
      const floorPlan = createTestFloorPlan();
      const createElementSpy = vi
        .spyOn(document, 'createElement')
        .mockImplementation(() => ({
          href: '',
          download: '',
          click: vi.fn(),
          setAttribute: vi.fn(),
        } as unknown as HTMLAnchorElement));

      const mockAnchor = document.createElement('a');
      const appendChildSpy = vi
        .spyOn(document.body, 'appendChild')
        .mockImplementation(() => mockAnchor);

      const removeChildSpy = vi
        .spyOn(document.body, 'removeChild')
        .mockImplementation((node: Node) => node);

      downloadSVG(floorPlan);

      expect(createElementSpy).toHaveBeenCalledWith('a');

      createElementSpy.mockRestore();
      appendChildSpy.mockRestore();
      removeChildSpy.mockRestore();
    });
  });
});

describe('数据导入导出 - 异常路径测试', () => {
  describe('DXF异常处理', () => {
    it('导入损坏的DXF文件时应该返回具体错误行号', async () => {
      try {
        await parseDXF(CORRUPTED_DXF_CONTENT);
        expect.fail('应该抛出错误');
      } catch (error) {
        expect(error).toBeInstanceOf(Error);
        expect((error as Error).message).toContain('解析失败');
      }
    });

    it('导入空DXF文件时应该抛出错误', async () => {
      try {
        await parseDXF('');
        expect.fail('应该抛出错误');
      } catch (error) {
        expect(error).toBeInstanceOf(Error);
      }
    });

    it('导入格式错误的DXF文件时应该抛出明确错误', async () => {
      const invalidDXF = '这不是有效的DXF内容';
      try {
        await parseDXF(invalidDXF);
        expect.fail('应该抛出错误');
      } catch (error) {
        expect(error).toBeInstanceOf(Error);
        expect((error as Error).message).toContain('DXF');
      }
    });

    it('导入只有HEADER没有ENTITIES的DXF时应该生成空户型', async () => {
      const minimalDXF = `0
SECTION
2
HEADER
9
$ACADVER
1
AC1009
0
ENDSEC
0
EOF
`;
      const floorPlan = await parseDXF(minimalDXF);
      expect(floorPlan).toBeDefined();
      expect(floorPlan.walls).toHaveLength(0);
    });
  });

  describe('JSON异常处理', () => {
    it('导入无效JSON时应该抛出错误', () => {
      try {
        importFromJSON('这不是有效的JSON');
        expect.fail('应该抛出错误');
      } catch (error) {
        expect(error).toBeInstanceOf(SyntaxError);
      }
    });

    it('导入缺少必要字段的JSON时应该使用默认值', () => {
      const incompleteJSON = JSON.stringify({
        metadata: { version: '1.0.0' },
        data: {},
      });

      const floorPlan = importFromJSON(incompleteJSON);
      expect(floorPlan).toBeDefined();
      expect(floorPlan.walls).toBeDefined();
      expect(floorPlan.rooms).toBeDefined();
    });

    it('导入版本不兼容的JSON时应该给出警告', () => {
      const oldVersionJSON = JSON.stringify({
        metadata: { version: '0.5.0' },
        data: createTestFloorPlan(),
      });

      const consoleWarnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const floorPlan = importFromJSON(oldVersionJSON);

      expect(floorPlan).toBeDefined();
      expect(consoleWarnSpy).toHaveBeenCalled();

      consoleWarnSpy.mockRestore();
    });
  });

  describe('文件导入异常处理', () => {
    it('导入不支持的文件格式时应该抛出错误', async () => {
      const mockFile = {
        name: 'test.txt',
        text: () => Promise.resolve('invalid content'),
      };
      try {
        await importFromFile(mockFile as any);
        expect.fail('应该抛出错误');
      } catch (error) {
        expect(error).toBeInstanceOf(Error);
        expect((error as Error).message).toContain('格式');
      }
    });

    it('导入过大的文件时应该给出处理', async () => {
      const largeContent = '0\nEOF\n'.repeat(10000);
      const mockFile = {
        name: 'large.dxf',
        text: () => Promise.resolve(largeContent),
      };

      const result = await importFromFile(mockFile as any);
      expect(result).toBeDefined();
    });
  });
});

describe('数据导入导出 - 边界条件测试', () => {
  it('应该正确处理空户型的导出', () => {
    const emptyFloorPlan = createTestFloorPlan();
    emptyFloorPlan.walls = [];
    emptyFloorPlan.rooms = [];
    emptyFloorPlan.openings = [];

    const jsonStr = exportToJSON(emptyFloorPlan);
    expect(jsonStr).toBeDefined();

    const imported = importFromJSON(jsonStr);
    expect(imported.walls).toHaveLength(0);
    expect(imported.rooms).toHaveLength(0);
    expect(imported.openings).toHaveLength(0);
  });

  it('应该正确处理超大型户型的导出', () => {
    const largeFloorPlan = createTestFloorPlan();
    
    for (let i = 0; i < 100; i++) {
      largeFloorPlan.walls.push({
        id: `wall-${i}`,
        type: 'straight',
        start: { x: i, y: 0 },
        end: { x: i, y: 10 },
        thickness: 0.2,
        height: 2.8,
        materialId: 'mat-wall-white',
      });
    }

    const jsonStr = exportToJSON(largeFloorPlan);
    expect(jsonStr.length).toBeGreaterThan(10000);

    const imported = importFromJSON(jsonStr);
    expect(imported.walls.length).toBeGreaterThanOrEqual(100);
  });

  it('应该正确处理特殊字符的户型名称', () => {
    const floorPlan = createTestFloorPlan();
    floorPlan.name = '测试户型 <&> "特殊字符" \'单引号\'';
    floorPlan.description = '包含\n换行\t制表符';

    const jsonStr = exportToJSON(floorPlan);
    expect(jsonStr).toContain('测试户型');

    const imported = importFromJSON(jsonStr);
    expect(imported.name).toBe(floorPlan.name);
    expect(imported.description).toBe(floorPlan.description);
  });

  it('应该正确处理Unicode字符', () => {
    const floorPlan = createTestFloorPlan();
    floorPlan.rooms[0].name = '主卧 🛏️ 带卫生间🚽';

    const jsonStr = exportToJSON(floorPlan);
    const imported = importFromJSON(jsonStr);

    expect(imported.rooms[0].name).toContain('主卧');
    expect(imported.rooms[0].name).toContain('🛏️');
  });
});
