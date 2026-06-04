import { test, expect, _electron as electron } from '@playwright/test';
import path from 'path';

test.describe('KnowledgeForge 集成测试', () => {
  let electronApp: any;
  let mainWindow: any;

  test.beforeAll(async () => {
    electronApp = await electron.launch({
      args: [path.join(__dirname, '../../dist/main/index.js')],
    });
    mainWindow = await electronApp.firstWindow();
    await mainWindow.waitForLoadState('networkidle');
  });

  test.afterAll(async () => {
    await electronApp.close();
  });

  test('应用启动后应该显示欢迎页面', async () => {
    await expect(mainWindow.getByText('KnowledgeForge')).toBeVisible();
    await expect(mainWindow.getByText('欢迎使用')).toBeVisible();
  });

  test.describe('新建文档流程', () => {
    test('应该能够创建新文档', async () => {
      await mainWindow.getByRole('button', { name: /新建文档/i }).click();
      await mainWindow.waitForSelector('[data-testid="editor"]');

      const titleInput = mainWindow.getByPlaceholder('文档标题');
      await titleInput.fill('测试文档');

      await expect(titleInput).toHaveValue('测试文档');
    });

    test('应该能够编辑Markdown内容', async () => {
      const editor = mainWindow.locator('[data-testid="editor"] .cm-editor');
      await editor.click();

      await mainWindow.keyboard.type('# 测试标题\n\n这是测试内容');

      const preview = mainWindow.locator('[data-testid="preview"]');
      await expect(preview).toContainText('测试标题');
      await expect(preview).toContainText('这是测试内容');
    });

    test('应该能够保存文档', async () => {
      await mainWindow.getByRole('button', { name: /保存/i }).click();

      await expect(mainWindow.getByText(/保存成功/i)).toBeVisible({ timeout: 3000 });
    });
  });

  test.describe('搜索功能', () => {
    test.beforeAll(async () => {
      for (let i = 0; i < 5; i++) {
        await mainWindow.getByRole('button', { name: /新建文档/i }).click();
        await mainWindow.waitForSelector('[data-testid="editor"]');

        const titleInput = mainWindow.getByPlaceholder('文档标题');
        await titleInput.fill(`搜索测试文档 ${i}`);

        const editor = mainWindow.locator('[data-testid="editor"] .cm-editor');
        await editor.click();
        await mainWindow.keyboard.type(`这是第 ${i} 个测试文档，包含 TypeScript 和 React 内容。`);

        await mainWindow.getByRole('button', { name: /保存/i }).click();
        await mainWindow.waitForTimeout(500);
      }
    });

    test('应该能够搜索文档', async () => {
      const searchInput = mainWindow.getByPlaceholder('搜索...');
      await searchInput.click();
      await searchInput.fill('TypeScript');
      await mainWindow.keyboard.press('Enter');

      const searchResults = mainWindow.locator('[data-testid="search-results"]');
      await expect(searchResults).toBeVisible();

      const resultItems = searchResults.locator('[data-testid="result-item"]');
      expect(await resultItems.count()).toBeGreaterThan(0);
    });

    test('搜索结果应该包含关键词高亮', async () => {
      const firstResult = mainWindow.locator('[data-testid="result-item"]').first();
      const highlightMark = firstResult.locator('mark');
      await expect(highlightMark).toBeVisible();
    });

    test('应该能够从搜索结果打开文档', async () => {
      const firstResult = mainWindow.locator('[data-testid="result-item"]').first();
      await firstResult.click();

      await mainWindow.waitForSelector('[data-testid="editor"]');
      const title = mainWindow.getByPlaceholder('文档标题');
      await expect(title).toHaveValue(/搜索测试文档/);
    });
  });

  test.describe('双向链接和知识图谱', () => {
    test.beforeAll(async () => {
      await mainWindow.getByRole('button', { name: /新建文档/i }).click();
      await mainWindow.waitForSelector('[data-testid="editor"]');

      const titleInput = mainWindow.getByPlaceholder('文档标题');
      await titleInput.fill('源文档');

      const editor = mainWindow.locator('[data-testid="editor"] .cm-editor');
      await editor.click();
      await mainWindow.keyboard.type('参考 [[目标文档]] 了解更多信息。');

      await mainWindow.getByRole('button', { name: /保存/i }).click();
      await mainWindow.waitForTimeout(500);

      await mainWindow.getByRole('button', { name: /新建文档/i }).click();
      await mainWindow.waitForSelector('[data-testid="editor"]');

      const titleInput2 = mainWindow.getByPlaceholder('文档标题');
      await titleInput2.fill('目标文档');

      const editor2 = mainWindow.locator('[data-testid="editor"] .cm-editor');
      await editor2.click();
      await mainWindow.keyboard.type('这是目标文档的内容。');

      await mainWindow.getByRole('button', { name: /保存/i }).click();
      await mainWindow.waitForTimeout(500);
    });

    test('编辑器中的双向链接应该可点击', async () => {
      const wikilink = mainWindow.locator('.cm-wikilink');
      await expect(wikilink).toBeVisible();
    });

    test('知识图谱页面应该显示节点', async () => {
      await mainWindow.getByRole('link', { name: /图谱/i }).click();
      await mainWindow.waitForSelector('[data-testid="graph-container"]');

      const graphNodes = mainWindow.locator('[data-testid="graph-node"]');
      expect(await graphNodes.count()).toBeGreaterThan(0);
    });

    test('知识图谱中的节点应该可点击跳转', async () => {
      const firstNode = mainWindow.locator('[data-testid="graph-node"]').first();
      await firstNode.click();

      await mainWindow.waitForSelector('[data-testid="editor"]');
      const title = mainWindow.getByPlaceholder('文档标题');
      await expect(title).toHaveValue(/文档/);
    });

    test('双向链接应该在图谱中显示为边', async () => {
      await mainWindow.getByRole('link', { name: /图谱/i }).click();
      await mainWindow.waitForSelector('[data-testid="graph-container"]');

      const graphEdges = mainWindow.locator('[data-testid="graph-edge"]');
      expect(await graphEdges.count()).toBeGreaterThan(0);
    });
  });

  test.describe('完整用户流程', () => {
    test('新建文档 -> 编辑内容 -> 添加双向链接 -> 保存 -> 搜索 -> 验证图谱', async () => {
      await test.step('1. 新建文档 A', async () => {
        await mainWindow.getByRole('button', { name: /新建文档/i }).click();
        await mainWindow.waitForSelector('[data-testid="editor"]');

        const titleInput = mainWindow.getByPlaceholder('文档标题');
        await titleInput.fill('文档 A');

        const editor = mainWindow.locator('[data-testid="editor"] .cm-editor');
        await editor.click();
        await mainWindow.keyboard.type('# 文档 A\n\n这是文档 A 的内容，参考了 [[文档 B]]。');

        await mainWindow.getByRole('button', { name: /保存/i }).click();
        await expect(mainWindow.getByText(/保存成功/i)).toBeVisible({ timeout: 3000 });
      });

      await test.step('2. 新建文档 B', async () => {
        await mainWindow.getByRole('button', { name: /新建文档/i }).click();
        await mainWindow.waitForSelector('[data-testid="editor"]');

        const titleInput = mainWindow.getByPlaceholder('文档标题');
        await titleInput.fill('文档 B');

        const editor = mainWindow.locator('[data-testid="editor"] .cm-editor');
        await editor.click();
        await mainWindow.keyboard.type('# 文档 B\n\n这是文档 B 的内容。');

        await mainWindow.getByRole('button', { name: /保存/i }).click();
        await expect(mainWindow.getByText(/保存成功/i)).toBeVisible({ timeout: 3000 });
      });

      await test.step('3. 搜索文档 A', async () => {
        const searchInput = mainWindow.getByPlaceholder('搜索...');
        await searchInput.click();
        await searchInput.fill('文档 A');
        await mainWindow.keyboard.press('Enter');

        const firstResult = mainWindow.locator('[data-testid="result-item"]').first();
        await expect(firstResult).toContainText('文档 A');
        await firstResult.click();
      });

      await test.step('4. 验证双向链接在编辑器中显示', async () => {
        await mainWindow.waitForSelector('[data-testid="editor"]');
        const wikilink = mainWindow.locator('.cm-wikilink');
        await expect(wikilink).toBeVisible();
        await expect(wikilink).toContainText('文档 B');
      });

      await test.step('5. 验证知识图谱中显示链接关系', async () => {
        await mainWindow.getByRole('link', { name: /图谱/i }).click();
        await mainWindow.waitForSelector('[data-testid="graph-container"]');

        const nodeA = mainWindow.locator('[data-testid="graph-node"]', { hasText: '文档 A' });
        const nodeB = mainWindow.locator('[data-testid="graph-node"]', { hasText: '文档 B' });

        await expect(nodeA).toBeVisible();
        await expect(nodeB).toBeVisible();

        const edges = mainWindow.locator('[data-testid="graph-edge"]');
        expect(await edges.count()).toBeGreaterThan(0);
      });

      await test.step('6. 点击图谱节点跳转', async () => {
        const nodeB = mainWindow.locator('[data-testid="graph-node"]', { hasText: '文档 B' });
        await nodeB.click();

        await mainWindow.waitForSelector('[data-testid="editor"]');
        const title = mainWindow.getByPlaceholder('文档标题');
        await expect(title).toHaveValue('文档 B');
      });
    });
  });

  test.describe('主题切换', () => {
    test('应该能够切换到深色模式', async () => {
      await mainWindow.getByRole('button', { name: /设置/i }).click();
      await mainWindow.getByLabel(/主题/i).selectOption({ label: /深色/i });

      const body = mainWindow.locator('body');
      await expect(body).toHaveClass(/dark/);
    });

    test('应该能够切换到浅色模式', async () => {
      await mainWindow.getByLabel(/主题/i).selectOption({ label: /浅色/i });

      const body = mainWindow.locator('body');
      await expect(body).not.toHaveClass(/dark/);
    });
  });

  test.describe('性能测试', () => {
    test('新建文档应该在 500ms 内完成', async () => {
      const startTime = Date.now();

      await mainWindow.getByRole('button', { name: /新建文档/i }).click();
      await mainWindow.waitForSelector('[data-testid="editor"]');

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(1000);
    });

    test('搜索响应时间应该在 300ms 内', async () => {
      const searchInput = mainWindow.getByPlaceholder('搜索...');
      await searchInput.click();
      await searchInput.fill('TypeScript');

      const startTime = Date.now();
      await mainWindow.keyboard.press('Enter');
      await mainWindow.waitForSelector('[data-testid="search-results"]');

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(500);
    });

    test('保存文档应该在 500ms 内完成', async () => {
      await mainWindow.getByRole('button', { name: /新建文档/i }).click();
      await mainWindow.waitForSelector('[data-testid="editor"]');

      const titleInput = mainWindow.getByPlaceholder('文档标题');
      await titleInput.fill('性能测试文档');

      const editor = mainWindow.locator('[data-testid="editor"] .cm-editor');
      await editor.click();
      await mainWindow.keyboard.type('测试内容');

      const startTime = Date.now();
      await mainWindow.getByRole('button', { name: /保存/i }).click();
      await expect(mainWindow.getByText(/保存成功/i)).toBeVisible({ timeout: 3000 });

      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(1000);
    });
  });
});
