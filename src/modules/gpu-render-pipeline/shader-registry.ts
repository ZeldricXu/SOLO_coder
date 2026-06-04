export interface VertexLayoutDescriptor {
  arrayStride: number;
  attributes: GPUVertexAttribute[];
}

export interface ShaderCacheKey {
  sourceHash: string;
  vertexLayoutHash: string;
}

export interface CachedShader {
  module: GPUShaderModule;
  timestamp: number;
  hitCount: number;
}

export interface CachedPipelineLayout {
  layout: GPUPipelineLayout;
  bindGroupLayouts: GPUBindGroupLayout[];
  timestamp: number;
  hitCount: number;
}

export interface CachedRenderPipeline {
  pipeline: GPURenderPipeline;
  timestamp: number;
  hitCount: number;
}

export interface CachedComputePipeline {
  pipeline: GPUComputePipeline;
  timestamp: number;
  hitCount: number;
}

function hashString(str: string): string {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash) ^ str.charCodeAt(i);
  }
  return (hash >>> 0).toString(16);
}

function hashBindGroupLayouts(layouts: GPUBindGroupLayoutDescriptor[]): string {
  return hashString(JSON.stringify(layouts));
}

const CACHE_CLEANUP_THRESHOLD = 100;
const CACHE_MAX_AGE_MS = 5 * 60 * 1000;

export class ShaderRegistry {
  private device: GPUDevice | null = null;
  private shaderModules: Map<string, CachedShader> = new Map();
  private pipelineLayouts: Map<string, CachedPipelineLayout> = new Map();
  private renderPipelines: Map<string, CachedRenderPipeline> = new Map();
  private computePipelines: Map<string, CachedComputePipeline> = new Map();

  init(device: GPUDevice): void {
    this.device = device;
  }

  getShaderModule(source: string, label?: string): GPUShaderModule {
    if (!this.device) {
      throw new Error('ShaderRegistry not initialized');
    }

    const sourceHash = hashString(source);
    const cached = this.shaderModules.get(sourceHash);

    if (cached) {
      cached.hitCount++;
      cached.timestamp = Date.now();
      return cached.module;
    }

    const module = this.device.createShaderModule({
      code: source,
      label: label || `shader_${sourceHash}`,
    });

    this.shaderModules.set(sourceHash, {
      module,
      timestamp: Date.now(),
      hitCount: 1,
    });

    this.cleanupIfNeeded();
    return module;
  }

  getPipelineLayout(
    bindGroupLayoutDescriptors: GPUBindGroupLayoutDescriptor[],
    label?: string
  ): GPUPipelineLayout {
    if (!this.device) {
      throw new Error('ShaderRegistry not initialized');
    }

    const layoutHash = hashBindGroupLayouts(bindGroupLayoutDescriptors);
    const cached = this.pipelineLayouts.get(layoutHash);

    if (cached) {
      cached.hitCount++;
      cached.timestamp = Date.now();
      return cached.layout;
    }

    const bindGroupLayouts = bindGroupLayoutDescriptors.map(desc =>
      this.device!.createBindGroupLayout(desc)
    );

    const layout = this.device.createPipelineLayout({
      bindGroupLayouts,
      label: label || `pipeline_layout_${layoutHash}`,
    });

    this.pipelineLayouts.set(layoutHash, {
      layout,
      bindGroupLayouts,
      timestamp: Date.now(),
      hitCount: 1,
    });

    this.cleanupIfNeeded();
    return layout;
  }

  getRenderPipeline(
    descriptor: GPURenderPipelineDescriptor,
    label?: string
  ): GPURenderPipeline {
    if (!this.device) {
      throw new Error('ShaderRegistry not initialized');
    }

    const pipelineHash = this.hashRenderPipelineDescriptor(descriptor);
    const cached = this.renderPipelines.get(pipelineHash);

    if (cached) {
      cached.hitCount++;
      cached.timestamp = Date.now();
      return cached.pipeline;
    }

    const pipeline = this.device.createRenderPipeline({
      ...descriptor,
      label: label || `render_pipeline_${pipelineHash}`,
    });

    this.renderPipelines.set(pipelineHash, {
      pipeline,
      timestamp: Date.now(),
      hitCount: 1,
    });

    this.cleanupIfNeeded();
    return pipeline;
  }

  getComputePipeline(
    descriptor: GPUComputePipelineDescriptor,
    label?: string
  ): GPUComputePipeline {
    if (!this.device) {
      throw new Error('ShaderRegistry not initialized');
    }

    const pipelineHash = this.hashComputePipelineDescriptor(descriptor);
    const cached = this.computePipelines.get(pipelineHash);

    if (cached) {
      cached.hitCount++;
      cached.timestamp = Date.now();
      return cached.pipeline;
    }

    const pipeline = this.device.createComputePipeline({
      ...descriptor,
      label: label || `compute_pipeline_${pipelineHash}`,
    });

    this.computePipelines.set(pipelineHash, {
      pipeline,
      timestamp: Date.now(),
      hitCount: 1,
    });

    this.cleanupIfNeeded();
    return pipeline;
  }

  getBindGroupLayout(
    descriptor: GPUBindGroupLayoutDescriptor,
    label?: string
  ): GPUBindGroupLayout {
    if (!this.device) {
      throw new Error('ShaderRegistry not initialized');
    }

    return this.device.createBindGroupLayout({
      ...descriptor,
      label,
    });
  }

  getStats(): {
    shaderCount: number;
    pipelineLayoutCount: number;
    renderPipelineCount: number;
    computePipelineCount: number;
    totalHits: number;
  } {
    const totalHits =
      Array.from(this.shaderModules.values()).reduce((s, c) => s + c.hitCount, 0) +
      Array.from(this.pipelineLayouts.values()).reduce((s, c) => s + c.hitCount, 0) +
      Array.from(this.renderPipelines.values()).reduce((s, c) => s + c.hitCount, 0) +
      Array.from(this.computePipelines.values()).reduce((s, c) => s + c.hitCount, 0);

    return {
      shaderCount: this.shaderModules.size,
      pipelineLayoutCount: this.pipelineLayouts.size,
      renderPipelineCount: this.renderPipelines.size,
      computePipelineCount: this.computePipelines.size,
      totalHits,
    };
  }

  clear(): void {
    this.shaderModules.clear();
    this.pipelineLayouts.clear();
    this.renderPipelines.clear();
    this.computePipelines.clear();
  }

  destroy(): void {
    this.clear();
    this.device = null;
  }

  private cleanupIfNeeded(): void {
    const totalEntries =
      this.shaderModules.size +
      this.pipelineLayouts.size +
      this.renderPipelines.size +
      this.computePipelines.size;

    if (totalEntries < CACHE_CLEANUP_THRESHOLD) return;

    const now = Date.now();
    const cutoff = now - CACHE_MAX_AGE_MS;

    for (const [key, entry] of this.shaderModules) {
      if (entry.timestamp < cutoff && entry.hitCount < 2) {
        this.shaderModules.delete(key);
      }
    }

    for (const [key, entry] of this.renderPipelines) {
      if (entry.timestamp < cutoff && entry.hitCount < 2) {
        this.renderPipelines.delete(key);
      }
    }

    for (const [key, entry] of this.computePipelines) {
      if (entry.timestamp < cutoff && entry.hitCount < 2) {
        this.computePipelines.delete(key);
      }
    }
  }

  private hashRenderPipelineDescriptor(desc: GPURenderPipelineDescriptor): string {
    const simple = {
      vertex: {
        entryPoint: desc.vertex.entryPoint,
        buffers: desc.vertex.buffers,
      },
      fragment: desc.fragment
        ? {
            entryPoint: desc.fragment.entryPoint,
            targets: desc.fragment.targets,
          }
        : null,
      primitive: desc.primitive,
      depthStencil: desc.depthStencil,
      multisample: desc.multisample,
    };
    return hashString(JSON.stringify(simple));
  }

  private hashComputePipelineDescriptor(desc: GPUComputePipelineDescriptor): string {
    const simple = {
      compute: {
        entryPoint: desc.compute.entryPoint,
      },
    };
    return hashString(JSON.stringify(simple));
  }
}
