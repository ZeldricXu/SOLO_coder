class MockGPUBuffer {
  size: number;
  usage: number;
  _mapped = false;
  _data: ArrayBuffer | null = null;

  constructor(size: number, usage: number, mappedAtCreation: boolean) {
    this.size = size;
    this.usage = usage;
    if (mappedAtCreation) {
      this._mapped = true;
      this._data = new ArrayBuffer(size);
    }
  }

  getMappedRange(): ArrayBuffer {
    return this._data!;
  }

  unmap(): void {
    this._mapped = false;
  }

  destroy(): void {}
}

class MockGPUTexture {
  size: [number, number];
  _views: MockGPUTextureView[] = [];

  constructor(size: [number, number]) {
    this.size = size;
  }

  createView(): MockGPUTextureView {
    const view = new MockGPUTextureView(this);
    this._views.push(view);
    return view;
  }

  destroy(): void {}
}

class MockGPUTextureView {
  texture: MockGPUTexture;
  constructor(texture: MockGPUTexture) {
    this.texture = texture;
  }
}

class MockGPURenderPipeline {
  _bindGroupLayout = new MockGPUBindGroupLayout();
  getBindGroupLayout(): MockGPUBindGroupLayout {
    return this._bindGroupLayout;
  }
}

class MockGPUComputePipeline {
  _bindGroupLayout = new MockGPUBindGroupLayout();
  getBindGroupLayout(): MockGPUBindGroupLayout {
    return this._bindGroupLayout;
  }
}

class MockGPUBindGroupLayout {}
class MockGPUBindGroup {}
class MockGPUPipelineLayout {}
class MockGPUShaderModule {
  code: string;
  constructor(code: string) { this.code = code; }
}
class MockGPUCommandEncoder {
  beginRenderPass(): MockGPURenderPassEncoder {
    return new MockGPURenderPassEncoder();
  }
  beginComputePass(): MockGPUComputePassEncoder {
    return new MockGPUComputePassEncoder();
  }
  finish(): MockGPUCommandBuffer { return new MockGPUCommandBuffer(); }
}
class MockGPURenderPassEncoder {
  setBindGroup(): void {}
  setPipeline(): void {}
  draw(_vertexCount: number, _instanceCount: number, _firstVertex: number, _firstInstance: number): void {}
  end(): void {}
}
class MockGPUComputePassEncoder {
  setBindGroup(): void {}
  setPipeline(): void {}
  dispatchWorkgroups(): void {}
  end(): void {}
}
class MockGPUCommandBuffer {}

class MockGPUQueue {
  writeBuffer(_buffer: MockGPUBuffer, _offset: number, _data: BufferSource): void {}
  writeTexture(_destination: unknown, _data: BufferSource, _dataLayout: unknown, _size: unknown): void {}
  submit(_commandBuffers: MockGPUCommandBuffer[]): void {}
}

class MockGPUDevice {
  queue: MockGPUQueue;
  lost: Promise<GPUDeviceLostInfo>;
  _lostResolve: ((info: GPUDeviceLostInfo) => void) | null = null;
  createShaderModuleCount = 0;
  createRenderPipelineCount = 0;
  createComputePipelineCount = 0;
  createBufferCount = 0;

  constructor() {
    this.queue = new MockGPUQueue();
    this.lost = new Promise<GPUDeviceLostInfo>((resolve) => {
      this._lostResolve = resolve;
    });
  }

  createShaderModule(desc: { code: string }): MockGPUShaderModule {
    this.createShaderModuleCount++;
    return new MockGPUShaderModule(desc.code);
  }

  createRenderPipeline(): MockGPURenderPipeline {
    this.createRenderPipelineCount++;
    return new MockGPURenderPipeline();
  }

  createComputePipeline(): MockGPUComputePipeline {
    this.createComputePipelineCount++;
    return new MockGPUComputePipeline();
  }

  createBuffer(desc: { size: number; usage: number; mappedAtCreation?: boolean }): MockGPUBuffer {
    this.createBufferCount++;
    return new MockGPUBuffer(desc.size, desc.usage, desc.mappedAtCreation ?? false);
  }

  createTexture(desc: { size: number[]; format: string; usage: number }): MockGPUTexture {
    return new MockGPUTexture([desc.size[0] as number, desc.size[1] as number]);
  }

  createBindGroupLayout(): MockGPUBindGroupLayout {
    return new MockGPUBindGroupLayout();
  }

  createPipelineLayout(): MockGPUPipelineLayout {
    return new MockGPUPipelineLayout();
  }

  createBindGroup(): MockGPUBindGroup {
    return new MockGPUBindGroup();
  }

  createCommandEncoder(): MockGPUCommandEncoder {
    return new MockGPUCommandEncoder();
  }

  destroy(): void {}
}

class MockGPUAdapter {
  async requestDevice(): Promise<MockGPUDevice> {
    return new MockGPUDevice();
  }
}

class MockGPU {
  async requestAdapter(): Promise<MockGPUAdapter | null> {
    return new MockGPUAdapter();
  }

  getPreferredCanvasFormat(): string {
    return 'bgra8unorm';
  }
}

const mockGPU = new MockGPU();

Object.defineProperty(navigator, 'gpu', {
  value: mockGPU,
  writable: false,
  configurable: true,
});

(window as any).__WGPU_MOCK__ = {
  MockGPUDevice,
  MockGPUBuffer,
  MockGPUTexture,
  MockGPURenderPipeline,
  MockGPUComputePipeline,
  MockGPUCommandEncoder,
};
