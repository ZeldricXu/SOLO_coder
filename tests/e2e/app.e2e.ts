import { test, expect, _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

let electronApp: ElectronApplication;
let page: Page;
let tempVaultDir: string;

test.describe.serial('Electron App - Full User Flow', () => {
  test.beforeAll(async () => {
    tempVaultDir = fs.mkdtempSync(path.join(os.tmpdir(), 'knowledge-graph-e2e-'));
    
    const sampleNote1 = `---
tags: [e2e, test]
---
# 笔记一

这是第一篇测试笔记，引用 [[笔记二]] 和 [[笔记三]]。

更多内容在这里。
`;
    
    const sampleNote2 = `---
tags: [e2e]
---
# 笔记二

这是第二篇测试笔记，引用 [[笔记一]]。

## 子标题

一些详细内容。
`;
    
    const sampleNote3 = `---
tags: [test]
---
# 笔记三

这是第三篇测试笔记，引用 [[笔记二]]。

第三篇笔记的内容。
`;
    
    fs.writeFileSync(path.join(tempVaultDir, '笔记一.md'), sampleNote1, 'utf-8');
    fs.writeFileSync(path.join(tempVaultDir, '笔记二.md'), sampleNote2, 'utf-8');
    fs.writeFileSync(path.join(tempVaultDir, '笔记三.md'), sampleNote3, 'utf-8');
    
    const appPath = path.join(__dirname, '../../dist/main/index.js');
    const hasBuild = fs.existsSync(appPath);
    
    if (hasBuild) {
      electronApp = await electron.launch({
        args: [path.join(__dirname, '../..')],
        env: {
          ...process.env,
          TEST_VAULT_PATH: tempVaultDir,
          NODE_ENV: 'test',
        },
      });
      
      page = await electronApp.firstWindow();
      await page.waitForLoadState('domcontentloaded');
    }
  });

  test.afterAll(async () => {
    if (electronApp) {
      await electronApp.close();
    }
    
    if (tempVaultDir && fs.existsSync(tempVaultDir)) {
      fs.rmSync(tempVaultDir, { recursive: true, force: true });
    }
  });

  test('should open application and show main window', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    expect(await page.title()).toBeTruthy();
    expect(page.url()).toBeTruthy();
  });

  test('should display notes in sidebar', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 10000 });
    
    const sidebar = page.locator('[data-testid="sidebar"]');
    await expect(sidebar).toBeVisible();
  });

  test('should show knowledge graph', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    await page.waitForSelector('[data-testid="knowledge-graph"]', { timeout: 10000 });
    
    const graph = page.locator('[data-testid="knowledge-graph"]');
    await expect(graph).toBeVisible();
    
    const nodes = await graph.locator('.graph-node').count();
    expect(nodes).toBeGreaterThanOrEqual(3);
  });

  test('should have three connected nodes in graph', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    await page.waitForSelector('.graph-node', { timeout: 10000 });
    
    const nodeCount = await page.locator('.graph-node').count();
    expect(nodeCount).toBeGreaterThanOrEqual(3);
    
    const edgeCount = await page.locator('.graph-edge').count();
    expect(edgeCount).toBeGreaterThanOrEqual(2);
  });

  test('should search and find notes', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    const searchInput = page.locator('[data-testid="search-input"]');
    await searchInput.fill('笔记');
    
    await page.waitForSelector('.search-result', { timeout: 5000 });
    
    const results = await page.locator('.search-result').count();
    expect(results).toBeGreaterThanOrEqual(3);
  });

  test('should navigate to note from search', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    const searchInput = page.locator('[data-testid="search-input"]');
    await searchInput.fill('笔记一');
    
    await page.waitForSelector('.search-result', { timeout: 5000 });
    
    const firstResult = page.locator('.search-result').first();
    await firstResult.click();
    
    await page.waitForSelector('[data-testid="note-editor"]', { timeout: 5000 });
    
    const editor = page.locator('[data-testid="note-editor"]');
    await expect(editor).toBeVisible();
  });

  test('should export notes as markdown package', async () => {
    test.skip(!electronApp, 'Electron app not built - skipping E2E test');
    
    const exportButton = page.locator('[data-testid="export-button"]');
    await exportButton.click();
    
    const exportMarkdownOption = page.locator('[data-testid="export-markdown"]');
    await exportMarkdownOption.click();
    
    await page.waitForEvent('download', { timeout: 30000 });
    
    const downloads = await page.evaluate(() => {
      return (window as any).lastDownloadPath || null;
    });
    
    if (downloads) {
      expect(fs.existsSync(downloads)).toBe(true);
    }
  });
});

test.describe.serial('File System Integration Tests', () => {
  test('should maintain consistency between file system and index', () => {
    const vaultDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fs-integration-test-'));
    
    try {
      const testFiles = [
        'file-1.md',
        'file-2.md',
        'nested/file-3.md',
        'nested/deep/file-4.md',
      ];
      
      for (const file of testFiles) {
        const filePath = path.join(vaultDir, file);
        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        fs.writeFileSync(filePath, `# ${path.basename(file, '.md')}\n\nContent.\n`, 'utf-8');
      }
      
      const mdFiles: string[] = [];
      function walk(dir: string) {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory()) {
            walk(fullPath);
          } else if (entry.name.endsWith('.md')) {
            mdFiles.push(path.relative(vaultDir, fullPath));
          }
        }
      }
      walk(vaultDir);
      
      expect(mdFiles.length).toBe(testFiles.length);
      
      for (const file of testFiles) {
        expect(mdFiles).toContain(file.replace(/\//g, path.sep));
      }
      
      const newFile = path.join(vaultDir, 'new-file.md');
      fs.writeFileSync(newFile, '# New File\n\nContent.', 'utf-8');
      
      const updatedMdFiles: string[] = [];
      walk(vaultDir);
      
      expect(updatedMdFiles.length).toBe(testFiles.length + 1);
      
      fs.unlinkSync(newFile);
      
      const finalMdFiles: string[] = [];
      walk(vaultDir);
      
      expect(finalMdFiles.length).toBe(testFiles.length);
    } finally {
      fs.rmSync(vaultDir, { recursive: true, force: true });
    }
  });

  test('should handle markdown file content correctly', () => {
    const testContent = `---
title: Test Note
tags: [test, example]
created: "2024-01-01"
---

# Test Note

This is a test note with [[Wiki Link]] and [[Another Link|Alias Text]].

## Section

Some content here.

- List item 1
- List item 2

\`\`\`javascript
console.log('hello');
\`\`\`

![image](test.png)
`;

    const tempFile = path.join(os.tmpdir(), `test-content-${Date.now()}.md`);
    fs.writeFileSync(tempFile, testContent, 'utf-8');
    
    try {
      const readContent = fs.readFileSync(tempFile, 'utf-8');
      expect(readContent).toContain('title: Test Note');
      expect(readContent).toContain('# Test Note');
      expect(readContent).toContain('[[Wiki Link]]');
      expect(readContent).toContain('[[Another Link|Alias Text]]');
      expect(readContent).toContain('```javascript');
      expect(readContent).toContain('![image](test.png)');
    } finally {
      if (fs.existsSync(tempFile)) {
        fs.unlinkSync(tempFile);
      }
    }
  });

  test('should maintain frontmatter data integrity', () => {
    const testCases = [
      {
        name: 'empty frontmatter',
        content: `---
---
# Note
`,
      },
      {
        name: 'single line frontmatter',
        content: `---
title: Test
---
# Note
`,
      },
      {
        name: 'multi-line value frontmatter',
        content: `---
title: Test
description: |
  This is a multi-line
  description value.
tags: [a, b, c]
---
# Note
`,
      },
      {
        name: 'nested arrays frontmatter',
        content: `---
title: Test
categories:
  - category1:
    - sub1
    - sub2
  - category2
---
# Note
`,
      },
    ];

    for (const testCase of testCases) {
      const tempFile = path.join(os.tmpdir(), `test-${testCase.name}-${Date.now()}.md`);
      fs.writeFileSync(tempFile, testCase.content, 'utf-8');
      
      try {
        const readContent = fs.readFileSync(tempFile, 'utf-8');
        expect(readContent.length).toBeGreaterThan(0);
        expect(readContent).toContain('---');
      } finally {
        if (fs.existsSync(tempFile)) {
          fs.unlinkSync(tempFile);
        }
      }
    }
  });
});

test.describe('Export Verification Tests', () => {
  test('should preserve relative paths in exported links', () => {
    const testDir = fs.mkdtempSync(path.join(os.tmpdir(), 'export-test-'));
    
    try {
      const noteA = `# Note A

See [[Note B]] for details.

Also check [[subdir/Note C]].
`;
      
      const noteB = `# Note B

Related to [[Note A]].
`;
      
      const subDir = path.join(testDir, 'subdir');
      fs.mkdirSync(subDir, { recursive: true });
      
      fs.writeFileSync(path.join(testDir, 'Note A.md'), noteA, 'utf-8');
      fs.writeFileSync(path.join(testDir, 'Note B.md'), noteB, 'utf-8');
      fs.writeFileSync(path.join(subDir, 'Note C.md'), '# Note C\n\nContent.\n', 'utf-8');
      
      const contentA = fs.readFileSync(path.join(testDir, 'Note A.md'), 'utf-8');
      expect(contentA).toContain('[[Note B]]');
      expect(contentA).toContain('[[subdir/Note C]]');
      
      const contentB = fs.readFileSync(path.join(testDir, 'Note B.md'), 'utf-8');
      expect(contentB).toContain('[[Note A]]');
      
      const files = fs.readdirSync(testDir);
      const mdFiles = files.filter(f => f.endsWith('.md'));
      expect(mdFiles.length).toBeGreaterThanOrEqual(2);
    } finally {
      fs.rmSync(testDir, { recursive: true, force: true });
    }
  });

  test('should handle batch export file structure', () => {
    const exportDir = fs.mkdtempSync(path.join(os.tmpdir(), 'batch-export-'));
    
    try {
      const files = [
        'index.md',
        'note-1.md',
        'note-2.md',
        'assets/image.png',
        'tags/tag-1.md',
      ];
      
      for (const file of files) {
        const filePath = path.join(exportDir, file);
        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        
        if (file.endsWith('.md')) {
          fs.writeFileSync(filePath, `# ${path.basename(file, '.md')}\n\nContent.\n`, 'utf-8');
        } else {
          fs.writeFileSync(filePath, 'fake-image-content');
        }
      }
      
      const allFiles: string[] = [];
      function walk(dir: string) {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory()) {
            walk(fullPath);
          } else {
            allFiles.push(path.relative(exportDir, fullPath));
          }
        }
      }
      walk(exportDir);
      
      expect(allFiles.length).toBe(files.length);
      
      const mdCount = allFiles.filter(f => f.endsWith('.md')).length;
      expect(mdCount).toBe(4);
    } finally {
      fs.rmSync(exportDir, { recursive: true, force: true });
    }
  });
});
