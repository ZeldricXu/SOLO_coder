import { Vec3, mat4Multiply, mat4Perspective, mat4Invert, degToRad } from '@/utils/math';
import type { Atom, Bond } from '../molecule-parser/types';
import { Octree } from '../spatial-octree';
import { extractFrustum } from '../spatial-octree/frustum';
import type { CameraState } from '../camera-controller';
import { BufferManager, type BufferUsage } from './buffer-manager';
import { ShaderRegistry } from './shader-registry';
import { DrawBatcher } from './draw-batcher';

import commonWGSL from './shaders/common.wgsl?raw';
import atomImpostorWGSL from './shaders/atom-impostor.wgsl?raw';
import bondImpostorWGSL from './shaders/bond-impostor.wgsl?raw';
import ssaoWGSL from './shaders/ssao.wgsl?raw';

const LIGHT_DATA_SIZE = 8;

export type ColorMode = 'element' | 'bFactor' | 'chain' | 'residue';

interface LightData {
  direction: Vec3;
  color: Vec3;
  intensity: number;
}

const DEFAULT_LIGHTS: LightData[] = [
  { direction: [0.5, 0.8, 0.3], color: [1.0, 0.95, 0.9], intensity: 1.2 },
  { direction: [-0.4, -0.3, 0.6], color: [0.7, 0.8, 1.0], intensity: 0.6 },
  { direction: [0.0, 0.0, -1.0], color: [0.0, 0.83, 0.67], intensity: 0.4 },
];

export class RenderPipeline {
  private device: GPUDevice | null = null;
  private context: GPUCanvasContext | null = null;
  private format: GPUTextureFormat = 'bgra8unorm';
  private width = 0;
  private height = 0;
  private atomCount = 0;
  private bondCount = 0;
  private octree: Octree | null = null;
  private octreeRoot: import('../spatial-octree/octree').OctreeNode | null = null;
  private _atoms: Atom[] = [];
  private _bonds: Bond[] = [];
  private _colorMode: ColorMode = 'element';
  private _chainIsolation: { isActive: boolean; isolatedChainId: string | null; fadeOpacity: number } = {
    isActive: false,
    isolatedChainId: null,
    fadeOpacity: 0.15,
  };

  private bufferManager: BufferManager = new BufferManager();
  private shaderRegistry: ShaderRegistry = new ShaderRegistry();
  private drawBatcher: DrawBatcher = new DrawBatcher();

  private atomPipeline: GPURenderPipeline | null = null;
  private bondPipeline: GPURenderPipeline | null = null;
  private ssaoPipeline: GPUComputePipeline | null = null;
  private bindGroupRef: GPUBindGroup | null = null;
  private ssaoBindGroupRef: GPUBindGroup | null = null;

  private depthTexture: GPUTexture | null = null;
  private normalTexture: GPUTexture | null = null;
  private ssaoTexture: GPUTexture | null = null;
  private noiseTexture: GPUTexture | null = null;

  async init(canvas: HTMLCanvasElement): Promise<boolean> {
    if (!navigator.gpu) return false;

    const adapter = await navigator.gpu.requestAdapter();
    if (!adapter) return false;

    this.device = await adapter.requestDevice();
    this.context = canvas.getContext('webgpu') as GPUCanvasContext;
    if (!this.context) return false;

    this.format = navigator.gpu.getPreferredCanvasFormat();
    this.context.configure({
      device: this.device,
      format: this.format,
      alphaMode: 'premultiplied',
    });

    this.bufferManager.init(this.device);
    this.shaderRegistry.init(this.device);
    this.drawBatcher.init(this.device);

    this.createPipelines();
    this.createBuffers();
    this.createNoiseTexture();

    return true;
  }

  setColorMode(mode: ColorMode): void {
    this._colorMode = mode;
  }

  getColorMode(): ColorMode {
    return this._colorMode;
  }

  setChainIsolation(isActive: boolean, isolatedChainId: string | null, fadeOpacity: number = 0.15): void {
    this._chainIsolation = { isActive, isolatedChainId, fadeOpacity };
    this._reuploadAtomData();
  }

  private _chainIdToIndex(chainId: string | undefined): number {
    if (!chainId) return 0;
    const firstChar = chainId.charCodeAt(0);
    if (firstChar >= 65 && firstChar <= 90) return firstChar - 65;
    if (firstChar >= 97 && firstChar <= 122) return firstChar - 97;
    return 0;
  }

  private _reuploadAtomData(): void {
    if (!this.device || this._atoms.length === 0) return;
    this.uploadAtoms(this._atoms, this._bonds);
  }

  private createPipelines(): void {
    if (!this.device) return;

    const uniformBindGroupLayout = this.shaderRegistry.getBindGroupLayout({
      entries: [
        { binding: 0, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT | GPUShaderStage.COMPUTE, buffer: { type: 'uniform' } },
        { binding: 1, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
        { binding: 2, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
        { binding: 3, visibility: GPUShaderStage.VERTEX, buffer: { type: 'read-only-storage' } },
      ],
    }, 'uniform_bind_group');

    const atomModule = this.shaderRegistry.getShaderModule(
      commonWGSL + '\n' + atomImpostorWGSL,
      'atom_impostor'
    );

    const pipelineLayout = this.device.createPipelineLayout({
      bindGroupLayouts: [uniformBindGroupLayout],
    });

    this.atomPipeline = this.shaderRegistry.getRenderPipeline({
      layout: pipelineLayout,
      vertex: { module: atomModule, entryPoint: 'vertexMain' },
      fragment: {
        module: atomModule,
        entryPoint: 'fragmentMain',
        targets: [
          { format: this.format, blend: {
            color: { srcFactor: 'src-alpha', dstFactor: 'one-minus-src-alpha', operation: 'add' },
            alpha: { srcFactor: 'one', dstFactor: 'one-minus-src-alpha', operation: 'add' },
          }},
          { format: 'rgba8unorm', blend: {
            color: { srcFactor: 'src-alpha', dstFactor: 'one-minus-src-alpha', operation: 'add' },
            alpha: { srcFactor: 'one', dstFactor: 'one-minus-src-alpha', operation: 'add' },
          }},
        ],
      },
      primitive: { topology: 'triangle-list' },
      depthStencil: { depthWriteEnabled: true, depthCompare: 'less', format: 'depth24plus' },
    }, 'atom_pipeline');

    const bondModule = this.shaderRegistry.getShaderModule(
      commonWGSL + '\n' + bondImpostorWGSL,
      'bond_impostor'
    );

    this.bondPipeline = this.shaderRegistry.getRenderPipeline({
      layout: pipelineLayout,
      vertex: { module: bondModule, entryPoint: 'vertexMain' },
      fragment: {
        module: bondModule,
        entryPoint: 'fragmentMain',
        targets: [
          { format: this.format, blend: {
            color: { srcFactor: 'src-alpha', dstFactor: 'one-minus-src-alpha', operation: 'add' },
            alpha: { srcFactor: 'one', dstFactor: 'one-minus-src-alpha', operation: 'add' },
          }},
          { format: 'rgba8unorm', blend: {
            color: { srcFactor: 'src-alpha', dstFactor: 'one-minus-src-alpha', operation: 'add' },
            alpha: { srcFactor: 'one', dstFactor: 'one-minus-src-alpha', operation: 'add' },
          }},
        ],
      },
      primitive: { topology: 'triangle-list' },
      depthStencil: { depthWriteEnabled: true, depthCompare: 'less', format: 'depth24plus' },
    }, 'bond_pipeline');

    const ssaoModule = this.shaderRegistry.getShaderModule(ssaoWGSL, 'ssao');

    const ssaoBindGroupLayout0 = this.shaderRegistry.getBindGroupLayout({
      entries: [
        { binding: 0, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT | GPUShaderStage.COMPUTE, buffer: { type: 'uniform' } },
        { binding: 1, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
        { binding: 2, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
        { binding: 3, visibility: GPUShaderStage.VERTEX, buffer: { type: 'read-only-storage' } },
      ],
    }, 'ssao_bind_group_0');

    const ssaoBindGroupLayout1 = this.shaderRegistry.getBindGroupLayout({
      entries: [
        { binding: 0, visibility: GPUShaderStage.COMPUTE, texture: { sampleType: 'depth' } },
        { binding: 1, visibility: GPUShaderStage.COMPUTE, texture: { sampleType: 'float' } },
        { binding: 2, visibility: GPUShaderStage.COMPUTE, texture: { sampleType: 'float' } },
      ],
    }, 'ssao_bind_group_1');

    const ssaoBindGroupLayout2 = this.shaderRegistry.getBindGroupLayout({
      entries: [
        { binding: 0, visibility: GPUShaderStage.COMPUTE, storageTexture: { access: 'write-only', format: 'rgba8unorm' } },
      ],
    }, 'ssao_bind_group_2');

    const ssaoPipelineLayout = this.device.createPipelineLayout({
      bindGroupLayouts: [ssaoBindGroupLayout0, ssaoBindGroupLayout1, ssaoBindGroupLayout2],
    });

    this.ssaoPipeline = this.shaderRegistry.getComputePipeline({
      layout: ssaoPipelineLayout,
      compute: { module: ssaoModule, entryPoint: 'computeMain' },
    }, 'ssao_pipeline');
  }

  private createBuffers(): void {
    if (!this.device) return;

    const uniformSize = 64 + 64 + 16 + 16 + 16 + LIGHT_DATA_SIZE * 16;
    this.bufferManager.allocateOrReuse('uniform', {
      size: uniformSize,
      usage: new Set<BufferUsage>(['uniform', 'copyDst']),
    });

    this.bufferManager.allocateOrReuse('atomStorage', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });

    this.bufferManager.allocateOrReuse('bondStorage', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });

    this.bufferManager.allocateOrReuse('visibleAtomIndex', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });

    this.bufferManager.allocateOrReuse('visibleBondIndex', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });

    this.bufferManager.allocateOrReuse('visibleAtomCount', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });

    this.bufferManager.allocateOrReuse('visibleBondCount', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
  }

  private createNoiseTexture(): void {
    if (!this.device) return;
    const size = 64;
    const data = new Uint8Array(size * size * 4);
    for (let i = 0; i < data.length; i += 4) {
      data[i] = Math.random() * 255;
      data[i + 1] = Math.random() * 255;
      data[i + 2] = 0;
      data[i + 3] = 255;
    }
    this.noiseTexture = this.device.createTexture({
      size: [size, size],
      format: 'rgba8unorm',
      usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
    });
    this.device.queue.writeTexture(
      { texture: this.noiseTexture },
      data,
      { bytesPerRow: size * 4 },
      [size, size]
    );
  }

  uploadAtoms(atoms: Atom[], bonds: Bond[]): void {
    if (!this.device) return;
    this._atoms = atoms;
    this._bonds = bonds;
    this.atomCount = atoms.length;
    this.bondCount = bonds.length;

    const atomData = new Float32Array(atoms.length * 12);
    for (let i = 0; i < atoms.length; i++) {
      const a = atoms[i];
      let alpha = 1.0;
      if (this._chainIsolation.isActive && this._chainIsolation.isolatedChainId) {
        if (a.chainId !== this._chainIsolation.isolatedChainId) {
          alpha = this._chainIsolation.fadeOpacity;
        }
      }

      atomData[i * 12 + 0] = a.x;
      atomData[i * 12 + 1] = a.y;
      atomData[i * 12 + 2] = a.z;
      atomData[i * 12 + 3] = 1.0;
      atomData[i * 12 + 4] = a.color[0];
      atomData[i * 12 + 5] = a.color[1];
      atomData[i * 12 + 6] = a.color[2];
      atomData[i * 12 + 7] = a.vdWRadius;
      atomData[i * 12 + 8] = alpha;
      atomData[i * 12 + 9] = a.bFactor ?? 20.0;
      atomData[i * 12 + 10] = this._chainIdToIndex(a.chainId);
      atomData[i * 12 + 11] = 0;
    }

    const atomAlloc = this.bufferManager.allocateOrReuse('atomStorage', {
      size: atomData.byteLength,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
    this.bufferManager.writeData(atomAlloc, atomData);

    const bondData = new Float32Array(bonds.length * 4);
    for (let i = 0; i < bonds.length; i++) {
      const b = bonds[i];
      bondData[i * 4 + 0] = b.atomIndex1;
      bondData[i * 4 + 1] = b.atomIndex2;
      bondData[i * 4 + 2] = b.order;
      bondData[i * 4 + 3] = 0;
    }

    const bondAlloc = this.bufferManager.allocateOrReuse('bondStorage', {
      size: bondData.byteLength,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
    this.bufferManager.writeData(bondAlloc, bondData);

    this.octree = new Octree();
    const simpleAtoms = atoms.map(a => ({ index: a.index, x: a.x, y: a.y, z: a.z }));
    this.octreeRoot = this.octree.build(simpleAtoms);

    const allAtomIndices = new Uint32Array(atoms.map((_, i) => i));
    const atomIdxAlloc = this.bufferManager.allocateOrReuse('visibleAtomIndex', {
      size: allAtomIndices.byteLength || 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
    this.bufferManager.writeData(atomIdxAlloc, allAtomIndices);

    const allBondIndices = new Uint32Array(bonds.map((_, i) => i));
    const bondIdxAlloc = this.bufferManager.allocateOrReuse('visibleBondIndex', {
      size: allBondIndices.byteLength || 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
    this.bufferManager.writeData(bondIdxAlloc, allBondIndices);

    const atomCountAlloc = this.bufferManager.allocateOrReuse('visibleAtomCount', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
    this.bufferManager.writeData(atomCountAlloc, new Uint32Array([atoms.length]));

    const bondCountAlloc = this.bufferManager.allocateOrReuse('visibleBondCount', {
      size: 4,
      usage: new Set<BufferUsage>(['storage', 'copyDst']),
    });
    this.bufferManager.writeData(bondCountAlloc, new Uint32Array([bonds.length]));
  }

  render(cameraState: CameraState, width: number, height: number): void {
    if (!this.device || !this.context || !this.atomPipeline || !this.bondPipeline) return;
    if (width === 0 || height === 0) return;

    if (this.width !== width || this.height !== height) {
      this.resizeTextures(width, height);
      this.width = width;
      this.height = height;
    }

    this.updateUniforms(cameraState, width, height);
    this.updateVisibility(cameraState);

    const depthView = this.depthTexture!.createView();
    const normalView = this.normalTexture!.createView();

    this.drawBatcher.setRenderPassConfig({
      colorAttachments: [
        {
          view: this.context.getCurrentTexture().createView(),
          clearValue: { r: 0.102, g: 0.102, b: 0.18, a: 1.0 },
          loadOp: 'clear',
          storeOp: 'store',
        },
        {
          view: normalView,
          clearValue: { r: 0.5, g: 0.5, b: 0.5, a: 1.0 },
          loadOp: 'clear',
          storeOp: 'store',
        },
      ],
      depthStencilAttachment: {
        view: depthView,
        depthClearValue: 1.0,
        depthLoadOp: 'clear',
        depthStoreOp: 'store',
      },
      label: 'main_render_pass',
    });

    const bindGroup = this.createBindGroup();
    this.bindGroupRef = bindGroup;

    this.drawBatcher.clear();

    if (bindGroup) {
      this.drawBatcher.addDrawCall(
        this.atomPipeline,
        [{ index: 0, bindGroup }],
        { vertexCount: 6, instanceCount: this.atomCount },
        0
      );

      if (this.bondCount > 0) {
        const bondBindGroup = this.createBondBindGroup();
        if (bondBindGroup) {
          this.drawBatcher.addDrawCall(
            this.bondPipeline,
            [{ index: 0, bindGroup: bondBindGroup }],
            { vertexCount: 6, instanceCount: this.bondCount },
            1
          );
        }
      }
    }

    this.drawBatcher.submit();

    this.ssaoBindGroupRef = this.ssaoPipeline ? null : null;
  }

  private updateVisibility(cameraState: CameraState): void {
    if (!this.device || !this.octree) return;

    const viewProj = mat4Multiply(cameraState.viewMatrix, mat4Perspective(
      degToRad(cameraState.fov),
      this.width / (this.height || 1),
      cameraState.near,
      cameraState.far
    ));
    mat4Invert(viewProj);

    const frustum = extractFrustum(viewProj);

    if (!this.octree || !this.octreeRoot) return;

    const visibleIndices = this.octree.cull(this.octreeRoot, frustum);
    const visibleUint = new Uint32Array(visibleIndices);
    this.bufferManager.writeDataToLabel('visibleAtomIndex', visibleUint);
    this.bufferManager.writeDataToLabel('visibleAtomCount', new Uint32Array([visibleIndices.length]));

    const visibleBondIndices = new Uint32Array(this.bondCount);
    for (let i = 0; i < this.bondCount; i++) visibleBondIndices[i] = i;
    this.bufferManager.writeDataToLabel('visibleBondIndex', visibleBondIndices);
  }

  private updateUniforms(cameraState: CameraState, width: number, height: number): void {
    if (!this.device) return;

    const projMatrix = mat4Perspective(degToRad(cameraState.fov), width / (height || 1), cameraState.near, cameraState.far);
    const viewProj = mat4Multiply(cameraState.viewMatrix, projMatrix);
    const invViewProj = mat4Invert(viewProj);

    const colorModeValue = this._colorMode === 'element' ? 0 : this._colorMode === 'bFactor' ? 1 : this._colorMode === 'chain' ? 2 : 3;

    const data = new Float32Array(16 + 16 + 4 + 4 + 4 + LIGHT_DATA_SIZE);
    data.set(viewProj as unknown as ArrayLike<number>, 0);
    data.set(invViewProj as unknown as ArrayLike<number>, 16);
    data.set([cameraState.eye[0], cameraState.eye[1], cameraState.eye[2], 1.0], 32);
    data.set([width, height, 1.0, degToRad(cameraState.fov)], 36);
    data.set([colorModeValue, 0, 0, 0], 40);

    for (let i = 0; i < DEFAULT_LIGHTS.length; i++) {
      const light = DEFAULT_LIGHTS[i];
      const offset = 44 + i * 8;
      data[offset + 0] = light.direction[0];
      data[offset + 1] = light.direction[1];
      data[offset + 2] = light.direction[2];
      data[offset + 3] = light.intensity;
      data[offset + 4] = light.color[0];
      data[offset + 5] = light.color[1];
      data[offset + 6] = light.color[2];
      data[offset + 7] = 0;
    }

    this.bufferManager.writeDataToLabel('uniform', data);
  }

  private createBindGroup(): GPUBindGroup | null {
    if (!this.device || !this.atomPipeline) return null;

    const uniformBuf = this.bufferManager.get('uniform');
    const atomBuf = this.bufferManager.get('atomStorage');
    const idxBuf = this.bufferManager.get('visibleAtomIndex');
    const cntBuf = this.bufferManager.get('visibleAtomCount');

    if (!uniformBuf || !atomBuf || !idxBuf || !cntBuf) return null;

    return this.device.createBindGroup({
      layout: this.atomPipeline.getBindGroupLayout(0),
      entries: [
        { binding: 0, resource: { buffer: uniformBuf.buffer, offset: uniformBuf.offset, size: uniformBuf.size } },
        { binding: 1, resource: { buffer: atomBuf.buffer, offset: atomBuf.offset, size: atomBuf.size } },
        { binding: 2, resource: { buffer: idxBuf.buffer, offset: idxBuf.offset, size: idxBuf.size } },
        { binding: 3, resource: { buffer: cntBuf.buffer, offset: cntBuf.offset, size: cntBuf.size } },
      ],
    });
  }

  private createBondBindGroup(): GPUBindGroup | null {
    if (!this.device || !this.bondPipeline) return null;

    const uniformBuf = this.bufferManager.get('uniform');
    const atomBuf = this.bufferManager.get('atomStorage');
    const bondBuf = this.bufferManager.get('bondStorage');
    const idxBuf = this.bufferManager.get('visibleBondIndex');

    if (!uniformBuf || !atomBuf || !bondBuf || !idxBuf) return null;

    return this.device.createBindGroup({
      layout: this.bondPipeline.getBindGroupLayout(0),
      entries: [
        { binding: 0, resource: { buffer: uniformBuf.buffer, offset: uniformBuf.offset, size: uniformBuf.size } },
        { binding: 1, resource: { buffer: atomBuf.buffer, offset: atomBuf.offset, size: atomBuf.size } },
        { binding: 2, resource: { buffer: bondBuf.buffer, offset: bondBuf.offset, size: bondBuf.size } },
        { binding: 3, resource: { buffer: idxBuf.buffer, offset: idxBuf.offset, size: idxBuf.size } },
      ],
    });
  }

  private resizeTextures(width: number, height: number): void {
    if (this.depthTexture) this.depthTexture.destroy();
    if (this.normalTexture) this.normalTexture.destroy();
    if (this.ssaoTexture) this.ssaoTexture.destroy();

    if (!this.device) return;

    this.depthTexture = this.device.createTexture({
      size: [width, height],
      format: 'depth24plus',
      usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING,
    });

    this.normalTexture = this.device.createTexture({
      size: [width, height],
      format: 'rgba8unorm',
      usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING,
    });

    this.ssaoTexture = this.device.createTexture({
      size: [width, height],
      format: 'rgba8unorm',
      usage: GPUTextureUsage.STORAGE_BINDING | GPUTextureUsage.TEXTURE_BINDING,
    });
  }

  getDevice(): GPUDevice | null { return this.device; }
  getAtomCount(): number { return this.atomCount; }
  getBondCount(): number { return this.bondCount; }
  getOctree(): Octree | null { return this.octree; }
  getSSAOPipeline(): GPUComputePipeline | null { return this.ssaoPipeline; }
  getBindGroup(): GPUBindGroup | null { return this.bindGroupRef; }
  getSSAOBindGroup(): GPUBindGroup | null { return this.ssaoBindGroupRef; }

  getBufferManager(): BufferManager { return this.bufferManager; }
  getShaderRegistry(): ShaderRegistry { return this.shaderRegistry; }
  getDrawBatcher(): DrawBatcher { return this.drawBatcher; }

  destroy(): void {
    this.bufferManager.destroy();
    this.shaderRegistry.destroy();
    this.drawBatcher.destroy();
    this.device?.destroy();
  }
}
