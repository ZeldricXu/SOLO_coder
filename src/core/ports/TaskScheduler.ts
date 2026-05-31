import { RequestContext } from '../../common/types';

export type TaskPriority = 'low' | 'medium' | 'high' | 'critical';

export type TaskStatus = 'pending' | 'queued' | 'running' | 'paused' | 'completed' | 'failed' | 'preempted';

export interface Task {
  id: string;
  priority: TaskPriority;
  status: TaskStatus;
  progress: number;
  requiredResources: TaskResourceRequirement;
  assignedResources?: TaskResourceAssignment;
  created_at: string;
  started_at?: string;
  completed_at?: string;
  error_detail?: string;
}

export interface TaskResourceRequirement {
  gpuMemoryMb: number;
  gpuComputeUnits?: number;
  cpuCores?: number;
  memoryMb?: number;
}

export interface TaskResourceAssignment {
  nodeId: string;
  gpuIds: number[];
  gpuMemoryAllocationMb: number;
}

export interface ScheduledTask extends Task {
  execute(ctx: RequestContext): Promise<void>;
  onPreempt?(): Promise<void>;
  onComplete?(): Promise<void>;
  onError?(error: Error): Promise<void>;
}

export interface TaskScheduler {
  submit(task: ScheduledTask): Promise<string>;
  cancel(taskId: string): Promise<boolean>;
  getStatus(taskId: string): Promise<Task | null>;
  getQueueLength(priority?: TaskPriority): number;
  start(): Promise<void>;
  stop(): Promise<void>;
}

export interface ResourceManager {
  getAvailableResources(): ClusterResourceStatus;
  allocate(requirement: TaskResourceRequirement): Promise<TaskResourceAssignment | null>;
  release(assignment: TaskResourceAssignment): Promise<void>;
  canAllocate(requirement: TaskResourceRequirement): boolean;
}

export interface ClusterResourceStatus {
  totalGpuMemoryMb: number;
  availableGpuMemoryMb: number;
  totalGpus: number;
  availableGpus: number;
  nodes: GpuNodeStatus[];
}

export interface GpuNodeStatus {
  nodeId: string;
  gpus: GpuDeviceStatus[];
  totalMemoryMb: number;
  availableMemoryMb: number;
}

export interface GpuDeviceStatus {
  id: number;
  totalMemoryMb: number;
  availableMemoryMb: number;
  utilization: number;
}

export interface PreemptionStrategy {
  selectVictim(
    runningTasks: ScheduledTask[],
    incomingTask: ScheduledTask
  ): ScheduledTask | null;
}
