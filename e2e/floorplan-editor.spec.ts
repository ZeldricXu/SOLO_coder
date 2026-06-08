import { test, expect, Page, Locator } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

test.describe('平面图编辑器 - 完整工作流集成测试', () => {
  let page: Page;
  let canvas2d: Locator;
  let viewToggle3d: Locator;
  let toolbar: Locator;
  let furnitureLibrary: Locator;
  let lightingPanel: Locator;
  let exportButton: Locator;
  let importButton: Locator;

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage();
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    canvas2d = page.locator('[data-testid="canvas-2d"]');
    viewToggle3d = page.locator('[data-testid="view-toggle-3d"]');
    toolbar = page.locator('[data-testid="drawing-toolbar"]');
    furnitureLibrary = page.locator('[data-testid="furniture-library"]');
    lightingPanel = page.locator('[data-testid="lighting-panel"]');
    exportButton = page.locator('[data-testid="export-json"]');
    importButton = page.locator('[data-testid="import-file"]');
  });

  test.beforeEach(async () => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  test.describe('2D绘制引擎测试', () => {
    test('应该能够从零开始绘制房间墙体', async () => {
      const wallTool = toolbar.locator('[data-testid="tool-wall"]');
      await wallTool.click();
      await expect(wallTool).toHaveAttribute('data-active', 'true');

      const canvasBox = await canvas2d.boundingBox();
      expect(canvasBox).not.toBeNull();

      const startX = canvasBox!.x + 100;
      const startY = canvasBox!.y + 100;

      await page.mouse.move(startX, startY);
      await page.mouse.down();
      await page.mouse.move(startX + 400, startY);
      await page.mouse.up();
      await page.waitForTimeout(500);

      await page.mouse.move(startX + 400, startY);
      await page.mouse.down();
      await page.mouse.move(startX + 400, startY + 300);
      await page.mouse.up();
      await page.waitForTimeout(500);

      await page.mouse.move(startX + 400, startY + 300);
      await page.mouse.down();
      await page.mouse.move(startX, startY + 300);
      await page.mouse.up();
      await page.waitForTimeout(500);

      await page.mouse.move(startX, startY + 300);
      await page.mouse.down();
      await page.mouse.move(startX, startY);
      await page.mouse.up();
      await page.waitForTimeout(1000);

      const wallCount = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return store?.floorPlan?.walls?.length || 0;
      });
      expect(wallCount).toBeGreaterThanOrEqual(4);

      const roomCount = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return store?.floorPlan?.rooms?.length || 0;
      });
      expect(roomCount).toBeGreaterThanOrEqual(1);
    });

    test('在2D画布上验证墙体显示', async () => {
      await canvas2d.screenshot({ path: 'test-results/2d-walls.png' });

      const walls = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return store?.floorPlan?.walls || [];
      });

      expect(walls.length).toBeGreaterThan(0);

      for (const wall of walls) {
        expect(wall.start).toBeDefined();
        expect(wall.end).toBeDefined();
        expect(typeof wall.start.x).toBe('number');
        expect(typeof wall.start.y).toBe('number');
      }
    });

    test('绘制自相交墙体时应该显示提示并阻止', async () => {
      const wallTool = toolbar.locator('[data-testid="tool-wall"]');
      await wallTool.click();

      const canvasBox = await canvas2d.boundingBox();
      const cx = canvasBox!.x + canvasBox!.width / 2;
      const cy = canvasBox!.y + canvasBox!.height / 2;

      await page.mouse.click(cx - 100, cy);
      await page.mouse.click(cx + 100, cy);
      await page.waitForTimeout(300);

      await page.mouse.click(cx + 100, cy);
      await page.mouse.click(cx + 100, cy + 100);
      await page.waitForTimeout(300);

      await page.mouse.click(cx + 100, cy + 100);
      await page.mouse.click(cx - 100, cy + 100);
      await page.waitForTimeout(300);

      await page.mouse.click(cx - 100, cy + 100);
      await page.mouse.click(cx - 100, cy - 50);
      await page.waitForTimeout(300);

      await page.mouse.click(cx - 100, cy - 50);
      await page.mouse.click(cx + 50, cy + 50);
      await page.waitForTimeout(500);

      const toast = page.locator('[data-testid="toast-message"]').filter({ hasText: '自相交' });
      await expect(toast).toBeVisible({ timeout: 3000 });
    });

    test('应该显示尺寸标注', async () => {
      const dimensionTool = toolbar.locator('[data-testid="tool-dimension"]');
      await dimensionTool.click();

      const canvasBox = await canvas2d.boundingBox();
      await page.mouse.click(canvasBox!.x + 100, canvasBox!.y + 100);
      await page.mouse.click(canvasBox!.x + 300, canvasBox!.y + 100);
      await page.waitForTimeout(500);

      const dimensionText = canvas2d.locator('text=2.00m');
      await expect(dimensionText).toBeVisible();
    });
  });

  test.describe('3D场景管理器测试', () => {
    test('切换到3D视图验证墙体自动拉伸', async () => {
      const wallTool = toolbar.locator('[data-testid="tool-wall"]');
      await wallTool.click();

      const canvasBox = await canvas2d.boundingBox();
      const sx = canvasBox!.x + 150;
      const sy = canvasBox!.y + 150;

      await page.mouse.click(sx, sy);
      await page.mouse.click(sx + 300, sy);
      await page.mouse.click(sx + 300, sy + 250);
      await page.mouse.click(sx, sy + 250);
      await page.mouse.click(sx, sy);
      await page.waitForTimeout(1000);

      await viewToggle3d.click();
      await page.waitForTimeout(2000);

      const canvas3d = page.locator('[data-testid="canvas-3d"]');
      await expect(canvas3d).toBeVisible();

      const wallMeshes = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        const walls = store?.floorPlan?.walls || [];
        return walls.length;
      });
      expect(wallMeshes).toBeGreaterThanOrEqual(4);

      await canvas3d.screenshot({ path: 'test-results/3d-walls.png' });

      const has3DWalls = await page.evaluate(() => {
        const scene = (window as any).__THREE_SCENE__;
        if (!scene) return false;
        let wallCount = 0;
        scene.traverse((obj: any) => {
          if (obj.userData?.isWall) wallCount++;
        });
        return wallCount >= 4;
      });
      expect(has3DWalls).toBe(true);
    });

    test('3D场景中应该有地板和天花板', async () => {
      await viewToggle3d.click();
      await page.waitForTimeout(2000);

      const hasFloorCeiling = await page.evaluate(() => {
        const scene = (window as any).__THREE_SCENE__;
        if (!scene) return { floor: false, ceiling: false };

        let floor = false, ceiling = false;
        scene.traverse((obj: any) => {
          if (obj.userData?.isFloor) floor = true;
          if (obj.userData?.isCeiling) ceiling = true;
        });
        return { floor, ceiling };
      });

      expect(hasFloorCeiling.floor).toBe(true);
      expect(hasFloorCeiling.ceiling).toBe(true);
    });
  });

  test.describe('家具库与碰撞检测测试', () => {
    test('应该能够从家具库拖入家具', async () => {
      await viewToggle3d.click();
      await page.waitForTimeout(1000);

      const libraryTab = page.locator('[data-testid="tab-furniture"]');
      await libraryTab.click();

      const sofaItem = furnitureLibrary.locator('[data-furniture-id="sofa-modern"]');
      await expect(sofaItem).toBeVisible();

      const sofaBox = await sofaItem.boundingBox();
      const canvas3d = page.locator('[data-testid="canvas-3d"]');
      const canvasBox = await canvas3d.boundingBox();

      await page.mouse.move(sofaBox!.x + sofaBox!.width / 2, sofaBox!.y + sofaBox!.height / 2);
      await page.mouse.down();
      await page.mouse.move(canvasBox!.x + canvasBox!.width / 2, canvasBox!.y + canvasBox!.height / 2);
      await page.mouse.up();
      await page.waitForTimeout(1000);

      const furnitureCount = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return store?.floorPlan?.furniture?.length || 0;
      });
      expect(furnitureCount).toBeGreaterThanOrEqual(1);

      await canvas3d.screenshot({ path: 'test-results/3d-furniture.png' });
    });

    test('拖入家具到已有家具上时应该阻止碰撞', async () => {
      await viewToggle3d.click();
      await page.waitForTimeout(1000);

      const libraryTab = page.locator('[data-testid="tab-furniture"]');
      await libraryTab.click();

      const sofaItem = furnitureLibrary.locator('[data-furniture-id="sofa-modern"]');
      const sofaBox = await sofaItem.boundingBox();
      const canvas3d = page.locator('[data-testid="canvas-3d"]');
      const canvasBox = await canvas3d.boundingBox();

      const dropX = canvasBox!.x + canvasBox!.width / 2;
      const dropY = canvasBox!.y + canvasBox!.height / 2;

      await page.mouse.move(sofaBox!.x + sofaBox!.width / 2, sofaBox!.y + sofaBox!.height / 2);
      await page.mouse.down();
      await page.mouse.move(dropX, dropY);
      await page.mouse.up();
      await page.waitForTimeout(1000);

      const initialCount = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return store?.floorPlan?.furniture?.length || 0;
      });

      await page.mouse.move(sofaBox!.x + sofaBox!.width / 2, sofaBox!.y + sofaBox!.height / 2);
      await page.mouse.down();
      await page.mouse.move(dropX, dropY);
      await page.mouse.up();
      await page.waitForTimeout(1000);

      const finalCount = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return store?.floorPlan?.furniture?.length || 0;
      });

      expect(finalCount).toBe(initialCount);

      const collisionToast = page.locator('[data-testid="toast-message"]').filter({ hasText: '碰撞' });
      await expect(collisionToast).toBeVisible({ timeout: 3000 });
    });
  });

  test.describe('实时灯光模拟测试', () => {
    test('调整灯光方向验证阴影变化', async () => {
      await viewToggle3d.click();
      await page.waitForTimeout(1000);

      const lightingTab = page.locator('[data-testid="tab-lighting"]');
      await lightingTab.click();

      const addPointLight = lightingPanel.locator('[data-testid="add-point-light"]');
      await addPointLight.click();
      await page.waitForTimeout(1000);

      const beforeScreenshot = await page.screenshot({ path: 'test-results/shadow-before.png', fullPage: true });

      const lightSliderX = lightingPanel.locator('[data-testid="light-pos-x"] input[type="range"]');
      const sliderBox = await lightSliderX.boundingBox();

      if (sliderBox) {
        await page.mouse.move(sliderBox.x + sliderBox.width * 0.2, sliderBox.y + sliderBox.height / 2);
        await page.mouse.down();
        await page.mouse.move(sliderBox.x + sliderBox.width * 0.8, sliderBox.y + sliderBox.height / 2);
        await page.mouse.up();
      }

      await page.waitForTimeout(1500);
      const afterScreenshot = await page.screenshot({ path: 'test-results/shadow-after.png', fullPage: true });

      expect(beforeScreenshot).not.toEqual(afterScreenshot);
    });

    test('灯光强度设为0时场景应该降级显示', async () => {
      await viewToggle3d.click();
      await page.waitForTimeout(1000);

      const lightingTab = page.locator('[data-testid="tab-lighting"]');
      await lightingTab.click();

      const intensitySlider = lightingPanel.locator('[data-testid="light-intensity"] input[type="range"]');
      const sliderBox = await intensitySlider.boundingBox();

      if (sliderBox) {
        await page.mouse.move(sliderBox.x + sliderBox.width * 0.9, sliderBox.y + sliderBox.height / 2);
        await page.mouse.down();
        await page.mouse.move(sliderBox.x + sliderBox.width * 0.1, sliderBox.y + sliderBox.height / 2);
        await page.mouse.up();
      }

      await page.waitForTimeout(1000);

      const intensityValue = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        const lights = store?.floorPlan?.lights || [];
        return lights.length > 0 ? lights[0].intensity : 1;
      });

      expect(intensityValue).toBeCloseTo(0, 1);

      await page.screenshot({ path: 'test-results/zero-intensity.png', fullPage: true });

      const hasAmbientBackup = await page.evaluate(() => {
        const scene = (window as any).__THREE_SCENE__;
        if (!scene) return false;
        let ambientCount = 0;
        scene.traverse((obj: any) => {
          if (obj.isAmbientLight && obj.intensity > 0) ambientCount++;
        });
        return ambientCount > 0;
      });
      expect(hasAmbientBackup).toBe(true);
    });
  });

  test.describe('导入导出测试', () => {
    test('导出JSON户型再重新导入验证数据完整性', async () => {
      const wallTool = toolbar.locator('[data-testid="tool-wall"]');
      await wallTool.click();

      const canvasBox = await canvas2d.boundingBox();
      const sx = canvasBox!.x + 200;
      const sy = canvasBox!.y + 150;

      await page.mouse.click(sx, sy);
      await page.mouse.click(sx + 350, sy);
      await page.mouse.click(sx + 350, sy + 280);
      await page.mouse.click(sx, sy + 280);
      await page.mouse.click(sx, sy);
      await page.waitForTimeout(1000);

      const [download] = await Promise.all([
        page.waitForEvent('download'),
        exportButton.click(),
      ]);

      const downloadPath = await download.path();
      expect(downloadPath).toBeDefined();

      const exportedData = JSON.parse(fs.readFileSync(downloadPath!, 'utf-8'));
      expect(exportedData.metadata).toBeDefined();
      expect(exportedData.data.walls).toHaveLength(4);

      const originalWalls = exportedData.data.walls.length;
      const originalRooms = exportedData.data.rooms.length;

      const fileChooserPromise = page.waitForEvent('filechooser');
      await importButton.click();
      const fileChooser = await fileChooserPromise;
      await fileChooser.setFiles(downloadPath!);

      await page.waitForTimeout(1500);

      const importedData = await page.evaluate(() => {
        const store = (window as any).useFloorPlanStore?.getState?.();
        return {
          walls: store?.floorPlan?.walls?.length || 0,
          rooms: store?.floorPlan?.rooms?.length || 0,
        };
      });

      expect(importedData.walls).toBe(originalWalls);
      expect(importedData.rooms).toBeGreaterThanOrEqual(originalRooms);
    });

    test('应该支持DXF文件导入', async () => {
      const sampleDXFPath = path.join(__dirname, 'fixtures', 'sample-house.dxf');
      
      if (fs.existsSync(sampleDXFPath)) {
        const fileChooserPromise = page.waitForEvent('filechooser');
        await importButton.click();
        const fileChooser = await fileChooserPromise;
        await fileChooser.setFiles(sampleDXFPath);

        await page.waitForTimeout(3000);

        const importedWalls = await page.evaluate(() => {
          const store = (window as any).useFloorPlanStore?.getState?.();
          return store?.floorPlan?.walls?.length || 0;
        });

        expect(importedWalls).toBeGreaterThan(0);
        await canvas2d.screenshot({ path: 'test-results/dxf-import.png' });
      }
    });
  });

  test.describe('完整端到端流程', () => {
    test('从零开始的完整户型设计流程', async () => {
      test.info().annotations.push({ type: 'workflow', description: '绘制→3D→家具→灯光→导出' });

      const wallTool = toolbar.locator('[data-testid="tool-wall"]');
      await wallTool.click();

      const canvasBox = await canvas2d.boundingBox();
      const sx = canvasBox!.x + 150;
      const sy = canvasBox!.y + 150;

      await page.mouse.click(sx, sy);
      await page.mouse.click(sx + 400, sy);
      await page.mouse.click(sx + 400, sy + 320);
      await page.mouse.click(sx, sy + 320);
      await page.mouse.click(sx, sy);
      await page.waitForTimeout(1500);

      await viewToggle3d.click();
      await page.waitForTimeout(2000);

      const libraryTab = page.locator('[data-testid="tab-furniture"]');
      await libraryTab.click();

      const sofaItem = furnitureLibrary.locator('[data-furniture-id="sofa-modern"]');
      const sofaBox = await sofaItem.boundingBox();
      const canvas3d = page.locator('[data-testid="canvas-3d"]');
      const c3dBox = await canvas3d.boundingBox();

      await page.mouse.move(sofaBox!.x + sofaBox!.width / 2, sofaBox!.y + sofaBox!.height / 2);
      await page.mouse.down();
      await page.mouse.move(c3dBox!.x + c3dBox!.width / 3, c3dBox!.y + c3dBox!.height / 2);
      await page.mouse.up();
      await page.waitForTimeout(1000);

      const lightingTab = page.locator('[data-testid="tab-lighting"]');
      await lightingTab.click();

      const addSpotLight = lightingPanel.locator('[data-testid="add-spot-light"]');
      await addSpotLight.click();
      await page.waitForTimeout(1000);

      const [download] = await Promise.all([
        page.waitForEvent('download'),
        exportButton.click(),
      ]);

      const downloadPath = await download.path();
      const exported = JSON.parse(fs.readFileSync(downloadPath!, 'utf-8'));

      expect(exported.data.walls.length).toBeGreaterThanOrEqual(4);
      expect(exported.data.rooms.length).toBeGreaterThanOrEqual(1);
      expect(exported.data.furniture.length).toBeGreaterThanOrEqual(1);
      expect(exported.data.lights.length).toBeGreaterThanOrEqual(1);

      await page.screenshot({ path: 'test-results/full-workflow.png', fullPage: true });
    });
  });

  test.afterAll(async () => {
    await page.close();
  });
});
