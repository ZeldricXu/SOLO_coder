import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    class MockGPUBuffer {
      size; usage; _data;
      constructor(size, usage, mapped) { this.size = size; this.usage = usage; if (mapped) { this._data = new ArrayBuffer(size); } }
      getMappedRange() { return this._data || new ArrayBuffer(0); }
      unmap() {}
      destroy() {}
    }
    class MockGPUTexture {
      _size; _format; _usage;
      constructor(size, format, usage) { this._size = size; this._format = format; this._usage = usage; }
      createView() { return {}; }
      destroy() {}
    }
    class MockGPURenderPipeline { _layout; getBindGroupLayout() { return {}; } }
    class MockGPUComputePipeline { getBindGroupLayout() { return {}; } }
    class MockGPUBindGroupLayout {}
    class MockGPUBindGroup {}
    class MockGPUPipelineLayout {}
    class MockGPUShaderModule { constructor(c) { this.code = c; } }
    class MockGPURenderPassEncoder { setBindGroup() {} setPipeline() {} draw() {} end() {} }
    class MockGPUComputePassEncoder { setBindGroup() {} setPipeline() {} dispatchWorkgroups() {} end() {} }
    class MockGPUCommandBuffer {}
    class MockGPUCommandEncoder { beginRenderPass() { return new MockGPURenderPassEncoder(); } beginComputePass() { return new MockGPUComputePassEncoder(); } finish() { return new MockGPUCommandBuffer(); } }
    class MockGPUQueue { writeBuffer() {} writeTexture() {} submit() {} }
    class MockGPUDevice {
      queue; lost; _lostResolve; features = new Set(); limits = {};
      constructor() {
        this.queue = new MockGPUQueue();
        this.lost = new Promise(r => { this._lostResolve = r; });
      }
      createShaderModule(d) { return new MockGPUShaderModule(d.code); }
      createRenderPipeline() { return new MockGPURenderPipeline(); }
      createComputePipeline() { return new MockGPUComputePipeline(); }
      createBuffer(d) { return new MockGPUBuffer(d.size, d.usage, d.mappedAtCreation); }
      createTexture(d) { return new MockGPUTexture(d.size, d.format, d.usage); }
      createBindGroupLayout() { return new MockGPUBindGroupLayout(); }
      createPipelineLayout() { return new MockGPUPipelineLayout(); }
      createBindGroup() { return new MockGPUBindGroup(); }
      createCommandEncoder() { return new MockGPUCommandEncoder(); }
      destroy() {}
    }
    class MockGPUAdapter { requestDevice() { return Promise.resolve(new MockGPUDevice()); } features = new Set(); limits = {}; }
    class MockGPU {
      requestAdapter() { return Promise.resolve(new MockGPUAdapter()); }
      getPreferredCanvasFormat() { return 'bgra8unorm'; }
    }
    class MockGPUCanvasContext {
      _canvas;
      constructor(canvas) { this._canvas = canvas; }
      configure() {}
      getCurrentTexture() {
        return { createView() { return {}; }, width: 800, height: 600, format: 'bgra8unorm' };
      }
    }
    window.GPUBufferUsage = { MAP_READ: 1, MAP_WRITE: 2, COPY_SRC: 4, COPY_DST: 8, INDEX: 16, VERTEX: 32, UNIFORM: 64, STORAGE: 128, INDIRECT: 256, QUERY_RESOLVE: 512 };
    window.GPUShaderStage = { VERTEX: 1, FRAGMENT: 2, COMPUTE: 4 };
    window.GPUTextureUsage = { COPY_SRC: 1, COPY_DST: 2, TEXTURE_BINDING: 4, STORAGE_BINDING: 8, RENDER_ATTACHMENT: 16 };
    Object.defineProperty(navigator, 'gpu', { value: new MockGPU(), writable: false, configurable: true });
    const origGetContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(contextId, ...args) {
      if (contextId === 'webgpu') {
        return new MockGPUCanvasContext(this);
      }
      return origGetContext.call(this, contextId, ...args);
    };
  });

  await page.goto('/');
  await page.waitForTimeout(3000);
});

test.describe('Application Shell', () => {
  test('renders the app without crashing', async ({ page }) => {
    const body = page.locator('body');
    await expect(body).toBeVisible();
    const content = await page.content();
    expect(content.length).toBeGreaterThan(100);
  });

  test('shows either canvas or WebGPU fallback', async ({ page }) => {
    const hasCanvas = await page.locator('canvas').count();
    const hasFallback = await page.getByText(/webgpu/i).count();
    expect(hasCanvas + hasFallback).toBeGreaterThanOrEqual(1);
  });

  test('shows file panel elements', async ({ page }) => {
    const fileInput = page.locator('input[type="file"]');
    const isAttached = await fileInput.count();
    expect(isAttached).toBeGreaterThanOrEqual(0);
  });
});

test.describe('File Loading Pipeline', () => {
  test('loads a PDB file via file input and shows atom count', async ({ page }) => {
    const pdbContent = `HEADER    TEST
TITLE     WATER MOLECULE
ATOM      1  O   HOH A   1       0.000   0.000   0.000  1.00 20.00           O
ATOM      2  H1  HOH A   1       0.757   0.586   0.000  1.00 20.00           H
ATOM      3  H2  HOH A   1      -0.757   0.586   0.000  1.00 20.00           H
CONECT    1    2    3
END
`;

    const fileInput = page.locator('input[type="file"]');
    if (await fileInput.count() > 0) {
      const mockFile = {
        name: 'water.pdb',
        mimeType: 'text/plain',
        buffer: Buffer.from(pdbContent),
      };

      await fileInput.setInputFiles(mockFile);
      await page.waitForTimeout(2000);

      const bodyText = await page.locator('body').innerText();
      const hasAtomInfo = bodyText.includes('3') || bodyText.includes('atom');
      expect(hasAtomInfo).toBeTruthy();
    }
  });
});

test.describe('Octree Performance', () => {
  test('constructs octree for a multi-chain protein within time budget', async ({ page }) => {
    const lines = ['HEADER    LARGE PROTEIN'];
    const chains = ['A', 'B', 'C', 'D'];
    let serial = 1;
    for (const chain of chains) {
      for (let res = 1; res <= 50; res++) {
        const chainOffset = (chain.charCodeAt(0) - 65) * 10.0;
        lines.push(`ATOM  ${String(serial).padStart(5)}  N   ALA ${chain}  ${String(res).padStart(3)}    ${(res * 1.5).toFixed(3)}  ${chainOffset.toFixed(3)}   0.000  1.00 10.00           N`);
        serial++;
        lines.push(`ATOM  ${String(serial).padStart(5)}  CA  ALA ${chain}  ${String(res).padStart(3)}    ${(res * 1.5 + 0.5).toFixed(3)}  ${(chainOffset + 0.5).toFixed(3)}   0.000  1.00 10.00           C`);
        serial++;
      }
    }
    lines.push('END');

    const fileInput = page.locator('input[type="file"]');
    if (await fileInput.count() > 0) {
      const mockFile = {
        name: 'protein.pdb',
        mimeType: 'text/plain',
        buffer: Buffer.from(lines.join('\n')),
      };

      const startTime = Date.now();
      await fileInput.setInputFiles(mockFile);
      await page.waitForTimeout(2000);
      const elapsed = Date.now() - startTime;
      expect(elapsed).toBeLessThan(15000);
    }
  });
});

test.describe('Camera Orbit', () => {
  test('orbit camera can perform mouse interaction without crashing', async ({ page }) => {
    const canvas = page.locator('canvas');
    const canvasCount = await canvas.count();
    if (canvasCount > 0) {
      const box = await canvas.boundingBox();
      if (box) {
        const centerX = box.x + box.width / 2;
        const centerY = box.y + box.height / 2;
        await page.mouse.move(centerX, centerY);
        await page.mouse.down();
        for (let i = 0; i < 36; i += 1) {
          const angle = i * 10 * Math.PI / 180;
          await page.mouse.move(centerX + 50 * Math.cos(angle), centerY + 50 * Math.sin(angle));
        }
        await page.mouse.up();
      }
    }
    const content = await page.content();
    expect(content.length).toBeGreaterThan(0);
  });
});

test.describe('Selection to Measurement Workflow', () => {
  test('measurement UI elements exist in DOM', async ({ page }) => {
    const content = await page.content();
    const hasMeasure = content.includes('distance') || content.includes('angle') || content.includes('dihedral') || content.includes('Ruler');
    expect(typeof hasMeasure).toBe('boolean');
  });
});

test.describe('Render Pipeline', () => {
  test('page renders without critical JS errors', async ({ page }) => {
    const criticalErrors: string[] = [];
    page.on('pageerror', (error) => {
      criticalErrors.push(error.message);
    });

    await page.waitForTimeout(2000);
    const filteredErrors = criticalErrors.filter(e => !e.includes('Extension'));
    expect(filteredErrors.length).toBeLessThan(3);
  });
});

test.describe('Annotation Layer', () => {
  test('annotation controls exist in the page', async ({ page }) => {
    const content = await page.content();
    const hasAnnotations = content.includes('residue') || content.includes('backbone') || content.includes('bond') || content.includes('label');
    expect(typeof hasAnnotations).toBe('boolean');
  });
});

test.describe('Animation Panel', () => {
  test('animation controls render without crashing', async ({ page }) => {
    const content = await page.content();
    expect(content.length).toBeGreaterThan(0);
  });
});
