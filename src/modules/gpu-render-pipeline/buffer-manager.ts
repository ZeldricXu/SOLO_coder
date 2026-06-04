export interface BufferArena {
  buffer: GPUBuffer;
  capacity: number;
  used: number;
}

export interface AllocatedBuffer {
  buffer: GPUBuffer;
  offset: number;
  size: number;
  generation: number;
}

export type BufferUsage =
  | 'uniform'
  | 'storage'
  | 'vertex'
  | 'index'
  | 'copySrc'
  | 'copyDst';

export interface BufferAllocationRequest {
  size: number;
  usage: Set<BufferUsage>;
}

const ARENA_GROWTH_FACTOR = 2;
const MIN_ARENA_SIZE = 256 * 1024;

function usageToGPUBufferUsage(usage: Set<BufferUsage>): number {
  let result = 0;
  if (usage.has('uniform')) result |= GPUBufferUsage.UNIFORM;
  if (usage.has('storage')) result |= GPUBufferUsage.STORAGE;
  if (usage.has('vertex')) result |= GPUBufferUsage.VERTEX;
  if (usage.has('index')) result |= GPUBufferUsage.INDEX;
  if (usage.has('copySrc')) result |= GPUBufferUsage.COPY_SRC;
  if (usage.has('copyDst')) result |= GPUBufferUsage.COPY_DST;
  return result;
}

export class BufferManager {
  private device: GPUDevice | null = null;
  private arenas: Map<string, BufferArena[]> = new Map();
  private allocations: Map<string, AllocatedBuffer> = new Map();
  private generation = 0;
  private labelCounter = 0;

  init(device: GPUDevice): void {
    this.device = device;
  }

  allocate(request: BufferAllocationRequest, label?: string): AllocatedBuffer {
    if (!this.device) {
      throw new Error('BufferManager not initialized');
    }

    const usageKey = Array.from(request.usage).sort().join(',');
    const arenas = this.arenas.get(usageKey) || [];
    const alignedSize = this.alignTo(request.size, 256);

    for (const arena of arenas) {
      if (arena.capacity - arena.used >= alignedSize) {
        const offset = arena.used;
        arena.used += alignedSize;
        const allocation: AllocatedBuffer = {
          buffer: arena.buffer,
          offset,
          size: request.size,
          generation: this.generation,
        };
        const key = label || `alloc_${this.labelCounter++}`;
        this.allocations.set(key, allocation);
        return allocation;
      }
    }

    const newArenaSize = Math.max(
      alignedSize * ARENA_GROWTH_FACTOR,
      MIN_ARENA_SIZE
    );
    const newBuffer = this.device.createBuffer({
      size: newArenaSize,
      usage: usageToGPUBufferUsage(request.usage),
      label: label || `arena_${this.labelCounter++}`,
    });

    const newArena: BufferArena = {
      buffer: newBuffer,
      capacity: newArenaSize,
      used: alignedSize,
    };

    arenas.push(newArena);
    this.arenas.set(usageKey, arenas);

    const allocation: AllocatedBuffer = {
      buffer: newBuffer,
      offset: 0,
      size: request.size,
      generation: this.generation,
    };

    const key = label || `alloc_${this.labelCounter++}`;
    this.allocations.set(key, allocation);
    return allocation;
  }

  allocateOrReuse(
    label: string,
    request: BufferAllocationRequest
  ): AllocatedBuffer {
    const existing = this.allocations.get(label);
    if (existing && existing.size >= request.size) {
      return existing;
    }
    return this.allocate(request, label);
  }

  writeData(allocation: AllocatedBuffer, data: ArrayBufferView): void {
    if (!this.device) {
      throw new Error('BufferManager not initialized');
    }
    this.device.queue.writeBuffer(
      allocation.buffer,
      allocation.offset,
      data.buffer,
      data.byteOffset,
      data.byteLength
    );
  }

  writeDataToLabel(label: string, data: ArrayBufferView): boolean {
    const allocation = this.allocations.get(label);
    if (!allocation) return false;
    this.writeData(allocation, data);
    return true;
  }

  get(label: string): AllocatedBuffer | undefined {
    return this.allocations.get(label);
  }

  reset(): void {
    this.generation++;
    for (const arenas of this.arenas.values()) {
      for (const arena of arenas) {
        arena.used = 0;
      }
    }
    this.allocations.clear();
  }

  resetArenas(): void {
    for (const arenas of this.arenas.values()) {
      for (const arena of arenas) {
        arena.used = 0;
      }
    }
  }

  destroy(): void {
    for (const arenas of this.arenas.values()) {
      for (const arena of arenas) {
        arena.buffer.destroy();
      }
    }
    this.arenas.clear();
    this.allocations.clear();
    this.device = null;
  }

  getMemoryStats(): {
    totalAllocated: number;
    totalUsed: number;
    arenaCount: number;
    allocationCount: number;
  } {
    let totalAllocated = 0;
    let totalUsed = 0;
    let arenaCount = 0;

    for (const arenas of this.arenas.values()) {
      for (const arena of arenas) {
        totalAllocated += arena.capacity;
        totalUsed += arena.used;
        arenaCount++;
      }
    }

    return {
      totalAllocated,
      totalUsed,
      arenaCount,
      allocationCount: this.allocations.size,
    };
  }

  private alignTo(size: number, alignment: number): number {
    return Math.ceil(size / alignment) * alignment;
  }
}
