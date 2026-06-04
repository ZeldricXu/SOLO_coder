import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import { RenderPipeline } from './pipeline';
import { BufferManager } from './buffer-manager';
import { ShaderRegistry } from './shader-registry';
import { DrawBatcher } from './draw-batcher';
import { makeWaterMolecule } from '@/test/fixtures';
import type { Bond } from '@/modules/molecule-parser/types';
import type { CameraState } from '@/modules/camera-controller';

beforeAll(() => {
  globalThis.GPUShaderStage = { VERTEX: 1, FRAGMENT: 2, COMPUTE: 4 } as unknown as typeof GPUShaderStage;
  globalThis.GPUBufferUsage = {
    MAP_READ: 0x0001, MAP_WRITE: 0x0002, COPY_SRC: 0x0004, COPY_DST: 0x0008,
    INDEX: 0x0010, VERTEX: 0x0020, UNIFORM: 0x0040, STORAGE: 0x0080,
    INDIRECT: 0x0100, QUERY_RESOLVE: 0x0200,
  } as unknown as typeof GPUBufferUsage;
  globalThis.GPUTextureUsage = {
    COPY_SRC: 0x01, COPY_DST: 0x02, TEXTURE_BINDING: 0x04,
    STORAGE_BINDING: 0x08, RENDER_ATTACHMENT: 0x10, TRANSIENT_ATTACHMENT: 0x20,
  } as unknown as typeof GPUTextureUsage;
});

const mockDevice = {
  createShaderModule: vi.fn(() => ({})),
  createRenderPipeline: vi.fn(() => ({ getBindGroupLayout: vi.fn(() => ({})) })),
  createComputePipeline: vi.fn(() => ({})),
  createBuffer: vi.fn(() => ({ getMappedRange: vi.fn(), unmap: vi.fn(), destroy: vi.fn() })),
  createTexture: vi.fn(() => ({ createView: vi.fn(() => ({})) })),
  createBindGroupLayout: vi.fn(() => ({})),
  createPipelineLayout: vi.fn(() => ({})),
  createBindGroup: vi.fn(() => ({})),
  createCommandEncoder: vi.fn(() => ({
    beginRenderPass: vi.fn(() => ({
      setBindGroup: vi.fn(),
      setPipeline: vi.fn(),
      draw: vi.fn(),
      end: vi.fn(),
    })),
    finish: vi.fn(() => ({})),
  })),
  queue: { writeBuffer: vi.fn(), writeTexture: vi.fn(), submit: vi.fn() },
  destroy: vi.fn(),
  lost: Promise.resolve({ reason: 'destroyed' as GPUDeviceLostReason, message: 'Device was destroyed' }),
};

function setupFullGpuMock(canvas: HTMLCanvasElement) {
  const mockContext = {
    configure: vi.fn(),
    getCurrentTexture: vi.fn(() => ({ createView: vi.fn(() => ({})) })),
  };
  vi.spyOn(canvas, 'getContext').mockReturnValue(mockContext as any);
  Object.defineProperty(globalThis.navigator, 'gpu', {
    value: {
      requestAdapter: vi.fn().mockResolvedValue({
        requestDevice: vi.fn().mockResolvedValue(mockDevice),
      }),
      getPreferredCanvasFormat: vi.fn().mockReturnValue('bgra8unorm'),
    },
    writable: true,
    configurable: true,
  });
  return mockContext;
}

const defaultCameraState: CameraState = {
  viewMatrix: [
    1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    0, 0, 0, 1,
  ],
  eye: [0, 0, 5],
  target: [0, 0, 0],
  up: [0, 1, 0],
  fov: 60,
  near: 0.1,
  far: 1000,
};

describe('RenderPipeline initialization', () => {
  it('init returns false when navigator.gpu is undefined', async () => {
    Object.defineProperty(globalThis.navigator, 'gpu', {
      value: undefined,
      writable: true,
      configurable: true,
    });
    const pipeline = new RenderPipeline();
    const canvas = document.createElement('canvas');
    const result = await pipeline.init(canvas);
    expect(result).toBe(false);
  });

  it('init returns false when requestAdapter returns null', async () => {
    Object.defineProperty(globalThis.navigator, 'gpu', {
      value: {
        requestAdapter: vi.fn().mockResolvedValue(null),
        getPreferredCanvasFormat: vi.fn(),
      },
      writable: true,
      configurable: true,
    });
    const pipeline = new RenderPipeline();
    const canvas = document.createElement('canvas');
    const result = await pipeline.init(canvas);
    expect(result).toBe(false);
  });
});

describe('RenderPipeline with mocked WebGPU', () => {
  let pipeline: RenderPipeline;
  let canvas: HTMLCanvasElement;

  beforeEach(() => {
    vi.clearAllMocks();
    pipeline = new RenderPipeline();
    canvas = document.createElement('canvas');
    setupFullGpuMock(canvas);
  });

  it('with full mock: init returns true', async () => {
    const result = await pipeline.init(canvas);
    expect(result).toBe(true);
  });

  it('after init: BufferManager, ShaderRegistry, and DrawBatcher are accessible', async () => {
    await pipeline.init(canvas);
    expect(pipeline.getBufferManager()).toBeInstanceOf(BufferManager);
    expect(pipeline.getShaderRegistry()).toBeInstanceOf(ShaderRegistry);
    expect(pipeline.getDrawBatcher()).toBeInstanceOf(DrawBatcher);
  });

  it('after init: pipelines are created', async () => {
    await pipeline.init(canvas);
    expect(mockDevice.createRenderPipeline).toHaveBeenCalled();
    expect(mockDevice.createComputePipeline).toHaveBeenCalled();
  });

  it('after init: uniform buffer is created via BufferManager', async () => {
    await pipeline.init(canvas);
    expect(mockDevice.createBuffer).toHaveBeenCalled();
  });
});

describe('uploadAtoms', () => {
  let pipeline: RenderPipeline;
  let canvas: HTMLCanvasElement;

  beforeEach(async () => {
    vi.clearAllMocks();
    pipeline = new RenderPipeline();
    canvas = document.createElement('canvas');
    setupFullGpuMock(canvas);
    await pipeline.init(canvas);
  });

  it('after uploadAtoms(water, bonds): atomCount=3, bondCount=bonds.length', () => {
    const water = makeWaterMolecule();
    const bonds: Bond[] = [
      { atomIndex1: 0, atomIndex2: 1, order: 1 },
      { atomIndex1: 0, atomIndex2: 2, order: 1 },
    ];
    pipeline.uploadAtoms(water, bonds);
    expect(pipeline.getAtomCount()).toBe(3);
    expect(pipeline.getBondCount()).toBe(bonds.length);
  });

  it('data is written to GPU buffers via BufferManager', () => {
    const water = makeWaterMolecule();
    const bonds: Bond[] = [
      { atomIndex1: 0, atomIndex2: 1, order: 1 },
    ];
    const writeCallsBefore = mockDevice.queue.writeBuffer.mock.calls.length;
    pipeline.uploadAtoms(water, bonds);
    const newWriteCalls = mockDevice.queue.writeBuffer.mock.calls.length - writeCallsBefore;
    expect(newWriteCalls).toBeGreaterThan(0);
  });

  it('bond data is written to GPU buffers', () => {
    const water = makeWaterMolecule();
    const bonds: Bond[] = [
      { atomIndex1: 0, atomIndex2: 1, order: 1 },
      { atomIndex1: 0, atomIndex2: 2, order: 1 },
    ];
    const writeCallsBefore = mockDevice.queue.writeBuffer.mock.calls.length;
    pipeline.uploadAtoms(water, bonds);
    const newWriteCalls = mockDevice.queue.writeBuffer.mock.calls.length - writeCallsBefore;
    expect(newWriteCalls).toBeGreaterThan(0);
  });

  it('octree is built internally', () => {
    const water = makeWaterMolecule();
    const bonds: Bond[] = [];
    pipeline.uploadAtoms(water, bonds);
    expect(pipeline.getOctree()).not.toBeNull();
  });
});

describe('Device loss handling', () => {
  it('when device.lost promise resolves, pipeline should handle gracefully', async () => {
    vi.clearAllMocks();
    let lostResolve!: (info: { reason: GPUDeviceLostReason; message: string }) => void;
    const lostPromise = new Promise<{ reason: GPUDeviceLostReason; message: string }>((resolve) => {
      lostResolve = resolve;
    });
    const deviceWithLost = {
      ...mockDevice,
      lost: lostPromise,
    };
    const canvas = document.createElement('canvas');
    const mockContext = {
      configure: vi.fn(),
      getCurrentTexture: vi.fn(() => ({ createView: vi.fn(() => ({})) })),
    };
    vi.spyOn(canvas, 'getContext').mockReturnValue(mockContext as any);
    Object.defineProperty(globalThis.navigator, 'gpu', {
      value: {
        requestAdapter: vi.fn().mockResolvedValue({
          requestDevice: vi.fn().mockResolvedValue(deviceWithLost),
        }),
        getPreferredCanvasFormat: vi.fn().mockReturnValue('bgra8unorm'),
      },
      writable: true,
      configurable: true,
    });
    const pipeline = new RenderPipeline();
    await pipeline.init(canvas);
    lostResolve({ reason: 'destroyed', message: 'Device was destroyed' });
    await lostPromise;
    expect(pipeline.getDevice()).not.toBeNull();
  });

  it('render() with null device does not crash', () => {
    Object.defineProperty(globalThis.navigator, 'gpu', {
      value: undefined,
      writable: true,
      configurable: true,
    });
    const pipeline = new RenderPipeline();
    expect(() => pipeline.render(defaultCameraState, 800, 600)).not.toThrow();
  });
});

describe('BufferManager component', () => {
  it('getMemoryStats returns arena statistics', async () => {
    vi.clearAllMocks();
    const pipeline = new RenderPipeline();
    const canvas = document.createElement('canvas');
    setupFullGpuMock(canvas);
    await pipeline.init(canvas);

    const bufferManager = pipeline.getBufferManager();
    const stats = bufferManager.getMemoryStats();
    expect(stats).toHaveProperty('arenaCount');
    expect(stats).toHaveProperty('totalAllocated');
    expect(stats).toHaveProperty('totalUsed');
    expect(stats).toHaveProperty('allocationCount');
  });

  it('allocateOrReuse reuses buffers with same label', async () => {
    vi.clearAllMocks();
    const pipeline = new RenderPipeline();
    const canvas = document.createElement('canvas');
    setupFullGpuMock(canvas);
    await pipeline.init(canvas);

    const bufferManager = pipeline.getBufferManager();
    const usage = new Set(['uniform' as const]);
    const alloc1 = bufferManager.allocateOrReuse('test-buffer', {
      size: 256,
      usage,
    });
    const alloc2 = bufferManager.allocateOrReuse('test-buffer', {
      size: 256,
      usage,
    });
    expect(alloc1.buffer).toBe(alloc2.buffer);
  });
});

describe('ShaderRegistry component', () => {
  it('getStats returns cache statistics', async () => {
    vi.clearAllMocks();
    const pipeline = new RenderPipeline();
    const canvas = document.createElement('canvas');
    setupFullGpuMock(canvas);
    await pipeline.init(canvas);

    const shaderRegistry = pipeline.getShaderRegistry();
    const stats = shaderRegistry.getStats();
    expect(stats).toHaveProperty('shaderCount');
    expect(stats).toHaveProperty('pipelineLayoutCount');
    expect(stats).toHaveProperty('renderPipelineCount');
    expect(stats).toHaveProperty('computePipelineCount');
  });
});

describe('DrawBatcher component', () => {
  it('getStats returns batch statistics', async () => {
    vi.clearAllMocks();
    const pipeline = new RenderPipeline();
    const canvas = document.createElement('canvas');
    setupFullGpuMock(canvas);
    await pipeline.init(canvas);

    const drawBatcher = pipeline.getDrawBatcher();
    const stats = drawBatcher.getStats();
    expect(stats).toHaveProperty('totalCommands');
    expect(stats).toHaveProperty('renderCommands');
    expect(stats).toHaveProperty('computeCommands');
  });
});
