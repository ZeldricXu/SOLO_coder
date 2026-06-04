export interface DrawCommand {
  id: string;
  pipeline: GPURenderPipeline | GPUComputePipeline;
  pipelineType: 'render' | 'compute';
  bindGroups: Array<{ index: number; bindGroup: GPUBindGroup }>;
  drawParams: DrawParams | DispatchParams;
  sortKey: number;
}

export interface DrawParams {
  vertexCount: number;
  instanceCount?: number;
  firstVertex?: number;
  firstInstance?: number;
}

export interface DispatchParams {
  workgroupCountX: number;
  workgroupCountY?: number;
  workgroupCountZ?: number;
}

export interface RenderPassConfig {
  colorAttachments: GPURenderPassColorAttachment[];
  depthStencilAttachment?: GPURenderPassDepthStencilAttachment;
  label?: string;
}

export type PipelineStateKey = number;

function generateSortKey(
  pipelineType: 'render' | 'compute',
  pipelineHash: number,
  bindGroupHash: number,
  priority: number = 0
): number {
  return (
    (pipelineType === 'render' ? 0 : 1) * 1000000000 +
    priority * 100000000 +
    (pipelineHash % 1000000) * 1000 +
    (bindGroupHash % 1000)
  );
}

function hashPipeline(pipeline: GPURenderPipeline | GPUComputePipeline): number {
  const str = pipeline.toString();
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function hashBindGroups(bindGroups: Array<{ index: number; bindGroup: GPUBindGroup }>): number {
  let hash = 0;
  for (const bg of bindGroups) {
    const str = bg.bindGroup.toString();
    for (let i = 0; i < str.length; i++) {
      hash = ((hash << 3) - hash + str.charCodeAt(i)) | 0;
    }
  }
  return Math.abs(hash);
}

export class DrawBatcher {
  private device: GPUDevice | null = null;
  private commands: DrawCommand[] = [];
  private renderPassConfig: RenderPassConfig | null = null;
  private autoSort = true;
  private batchIdCounter = 0;

  init(device: GPUDevice): void {
    this.device = device;
  }

  setRenderPassConfig(config: RenderPassConfig): void {
    this.renderPassConfig = config;
  }

  setAutoSort(enabled: boolean): void {
    this.autoSort = enabled;
  }

  addDrawCall(
    pipeline: GPURenderPipeline,
    bindGroups: Array<{ index: number; bindGroup: GPUBindGroup }>,
    drawParams: DrawParams,
    priority: number = 0
  ): string {
    const id = `draw_${this.batchIdCounter++}`;
    const sortKey = generateSortKey(
      'render',
      hashPipeline(pipeline),
      hashBindGroups(bindGroups),
      priority
    );

    this.commands.push({
      id,
      pipeline,
      pipelineType: 'render',
      bindGroups,
      drawParams,
      sortKey,
    });

    return id;
  }

  addDispatchCall(
    pipeline: GPUComputePipeline,
    bindGroups: Array<{ index: number; bindGroup: GPUBindGroup }>,
    dispatchParams: DispatchParams,
    priority: number = 0
  ): string {
    const id = `dispatch_${this.batchIdCounter++}`;
    const sortKey = generateSortKey(
      'compute',
      hashPipeline(pipeline),
      hashBindGroups(bindGroups),
      priority
    );

    this.commands.push({
      id,
      pipeline,
      pipelineType: 'compute',
      bindGroups,
      drawParams: dispatchParams,
      sortKey,
    });

    return id;
  }

  removeCommand(id: string): boolean {
    const index = this.commands.findIndex(c => c.id === id);
    if (index >= 0) {
      this.commands.splice(index, 1);
      return true;
    }
    return false;
  }

  clear(): void {
    this.commands = [];
    this.batchIdCounter = 0;
  }

  sort(): void {
    this.commands.sort((a, b) => a.sortKey - b.sortKey);
  }

  submit(): void {
    if (!this.device) {
      throw new Error('DrawBatcher not initialized');
    }

    if (this.autoSort) {
      this.sort();
    }

    const commandEncoder = this.device.createCommandEncoder();

    const renderCommands = this.commands.filter(c => c.pipelineType === 'render');
    const computeCommands = this.commands.filter(c => c.pipelineType === 'compute');

    if (renderCommands.length > 0 && this.renderPassConfig) {
      const passEncoder = commandEncoder.beginRenderPass({
        colorAttachments: this.renderPassConfig.colorAttachments,
        depthStencilAttachment: this.renderPassConfig.depthStencilAttachment,
        label: this.renderPassConfig.label,
      });

      let currentPipeline: GPURenderPipeline | null = null;

      for (const cmd of renderCommands) {
        const pipeline = cmd.pipeline as GPURenderPipeline;
        const params = cmd.drawParams as DrawParams;

        if (pipeline !== currentPipeline) {
          passEncoder.setPipeline(pipeline);
          currentPipeline = pipeline;
        }

        for (const bg of cmd.bindGroups) {
          passEncoder.setBindGroup(bg.index, bg.bindGroup);
        }

        passEncoder.draw(
          params.vertexCount,
          params.instanceCount || 1,
          params.firstVertex || 0,
          params.firstInstance || 0
        );
      }

      passEncoder.end();
    }

    for (const cmd of computeCommands) {
      const pipeline = cmd.pipeline as GPUComputePipeline;
      const params = cmd.drawParams as DispatchParams;

      const passEncoder = commandEncoder.beginComputePass();
      passEncoder.setPipeline(pipeline);

      for (const bg of cmd.bindGroups) {
        passEncoder.setBindGroup(bg.index, bg.bindGroup);
      }

      passEncoder.dispatchWorkgroups(
        params.workgroupCountX,
        params.workgroupCountY || 1,
        params.workgroupCountZ || 1
      );
      passEncoder.end();
    }

    this.device.queue.submit([commandEncoder.finish()]);
  }

  getStats(): {
    totalCommands: number;
    renderCommands: number;
    computeCommands: number;
    pipelineSwitches: number;
  } {
    if (this.autoSort) {
      this.sort();
    }

    let pipelineSwitches = 0;
    let lastPipeline: GPURenderPipeline | GPUComputePipeline | null = null;

    for (const cmd of this.commands) {
      if (cmd.pipeline !== lastPipeline) {
        pipelineSwitches++;
        lastPipeline = cmd.pipeline;
      }
    }

    return {
      totalCommands: this.commands.length,
      renderCommands: this.commands.filter(c => c.pipelineType === 'render').length,
      computeCommands: this.commands.filter(c => c.pipelineType === 'compute').length,
      pipelineSwitches,
    };
  }

  submitCustom(
    callback: (
      encoder: GPUCommandEncoder,
      commands: DrawCommand[]
    ) => void
  ): void {
    if (!this.device) {
      throw new Error('DrawBatcher not initialized');
    }

    if (this.autoSort) {
      this.sort();
    }

    const commandEncoder = this.device.createCommandEncoder();
    callback(commandEncoder, this.commands);
    this.device.queue.submit([commandEncoder.finish()]);
  }

  destroy(): void {
    this.clear();
    this.device = null;
    this.renderPassConfig = null;
  }
}
