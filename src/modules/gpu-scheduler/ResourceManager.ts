import {
  ResourceManager,
  TaskResourceRequirement,
  TaskResourceAssignment,
  ClusterResourceStatus,
  GpuNodeStatus,
  GpuDeviceStatus
} from '../../core/ports';
import { logger } from '../../common';

interface GpuDeviceAllocation {
  id: number;
  totalMemoryMb: number;
  allocatedMemoryMb: number;
  utilization: number;
}

interface GpuNodeAllocation {
  nodeId: string;
  devices: GpuDeviceAllocation[];
}

export class DefaultResourceManager implements ResourceManager {
  private nodes: Map<string, GpuNodeAllocation> = new Map();

  constructor(nodeConfigs: Array<{
    nodeId: string;
    gpus: Array<{ id: number; totalMemoryMb: number }>;
  }>) {
    for (const config of nodeConfigs) {
      this.nodes.set(config.nodeId, {
        nodeId: config.nodeId,
        devices: config.gpus.map(gpu => ({
          id: gpu.id,
          totalMemoryMb: gpu.totalMemoryMb,
          allocatedMemoryMb: 0,
          utilization: 0
        }))
      });
    }
  }

  getAvailableResources(): ClusterResourceStatus {
    const nodes: GpuNodeStatus[] = [];
    let totalGpuMemoryMb = 0;
    let availableGpuMemoryMb = 0;
    let totalGpus = 0;
    let availableGpus = 0;

    for (const node of this.nodes.values()) {
      let nodeTotalMemory = 0;
      let nodeAvailableMemory = 0;
      const gpus: GpuDeviceStatus[] = [];

      for (const device of node.devices) {
        nodeTotalMemory += device.totalMemoryMb;
        nodeAvailableMemory += (device.totalMemoryMb - device.allocatedMemoryMb);
        totalGpus++;
        if (device.allocatedMemoryMb === 0) {
          availableGpus++;
        }

        gpus.push({
          id: device.id,
          totalMemoryMb: device.totalMemoryMb,
          availableMemoryMb: device.totalMemoryMb - device.allocatedMemoryMb,
          utilization: device.utilization
        });
      }

      totalGpuMemoryMb += nodeTotalMemory;
      availableGpuMemoryMb += nodeAvailableMemory;

      nodes.push({
        nodeId: node.nodeId,
        gpus,
        totalMemoryMb: nodeTotalMemory,
        availableMemoryMb: nodeAvailableMemory
      });
    }

    return {
      totalGpuMemoryMb,
      availableGpuMemoryMb,
      totalGpus,
      availableGpus,
      nodes
    };
  }

  canAllocate(requirement: TaskResourceRequirement): boolean {
    const requiredMemory = requirement.gpuMemoryMb;

    for (const node of this.nodes.values()) {
      for (const device of node.devices) {
        const availableMemory = device.totalMemoryMb - device.allocatedMemoryMb;
        if (availableMemory >= requiredMemory) {
          return true;
        }
      }
    }

    return false;
  }

  async allocate(requirement: TaskResourceRequirement): Promise<TaskResourceAssignment | null> {
    return this.allocateSync(requirement);
  }

  allocateSync(requirement: TaskResourceRequirement): TaskResourceAssignment | null {
    const requiredMemory = requirement.gpuMemoryMb;

    for (const node of this.nodes.values()) {
      for (const device of node.devices) {
        const availableMemory = device.totalMemoryMb - device.allocatedMemoryMb;
        if (availableMemory >= requiredMemory) {
          device.allocatedMemoryMb += requiredMemory;

          logger.info('GPU resource allocated', {
            nodeId: node.nodeId,
            gpuId: device.id,
            allocatedMemory: requiredMemory,
            availableMemory: device.totalMemoryMb - device.allocatedMemoryMb
          });

          return {
            nodeId: node.nodeId,
            gpuIds: [device.id],
            gpuMemoryAllocationMb: requiredMemory
          };
        }
      }
    }

    return null;
  }

  async release(assignment: TaskResourceAssignment): Promise<void> {
    this.releaseSync(assignment);
  }

  releaseSync(assignment: TaskResourceAssignment): void {
    const node = this.nodes.get(assignment.nodeId);
    if (!node) {
      logger.warn('Attempted to release resources from unknown node', { nodeId: assignment.nodeId });
      return;
    }

    for (const gpuId of assignment.gpuIds) {
      const device = node.devices.find(d => d.id === gpuId);
      if (device) {
        device.allocatedMemoryMb = Math.max(0, device.allocatedMemoryMb - assignment.gpuMemoryAllocationMb);
        logger.info('GPU resource released', {
          nodeId: assignment.nodeId,
          gpuId,
          releasedMemory: assignment.gpuMemoryAllocationMb,
          availableMemory: device.totalMemoryMb - device.allocatedMemoryMb
        });
      }
    }
  }

  async updateGpuUtilization(nodeId: string, gpuId: number, utilization: number): Promise<void> {
    const node = this.nodes.get(nodeId);
    if (!node) return;

    const device = node.devices.find(d => d.id === gpuId);
    if (device) {
      device.utilization = Math.max(0, Math.min(100, utilization));
    }
  }

  addNode(nodeConfig: { nodeId: string; gpus: Array<{ id: number; totalMemoryMb: number }> }): void {
    this.nodes.set(nodeConfig.nodeId, {
      nodeId: nodeConfig.nodeId,
      devices: nodeConfig.gpus.map(gpu => ({
        id: gpu.id,
        totalMemoryMb: gpu.totalMemoryMb,
        allocatedMemoryMb: 0,
        utilization: 0
      }))
    });
  }

  removeNode(nodeId: string): boolean {
    return this.nodes.delete(nodeId);
  }
}
