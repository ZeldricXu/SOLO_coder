import { TaskPriority } from '../../core/ports';
import { generateId } from '../../common';

export interface GpuTask {
  id: string;
  name: string;
  priority: TaskPriority;
  requiredMemoryMb: number;
  requiredGpus: number;
  estimatedDurationMs: number;
  payload: unknown;
  userId: string;
}

export class GpuTaskBuilder {
  private taskId: string = generateId('task');
  private name: string = 'test-gpu-task';
  private priority: TaskPriority = 'medium';
  private requiredMemoryMb: number = 1024;
  private requiredGpus: number = 1;
  private estimatedDurationMs: number = 5000;
  private payload: unknown = { model: 'bert-base', batchSize: 32 };
  private userId: string = 'user_test';

  withTaskId(taskId: string): GpuTaskBuilder {
    this.taskId = taskId;
    return this;
  }

  withName(name: string): GpuTaskBuilder {
    this.name = name;
    return this;
  }

  withPriority(priority: TaskPriority): GpuTaskBuilder {
    this.priority = priority;
    return this;
  }

  withHighPriority(): GpuTaskBuilder {
    this.priority = 'high';
    return this;
  }

  withLowPriority(): GpuTaskBuilder {
    this.priority = 'low';
    return this;
  }

  withCriticalPriority(): GpuTaskBuilder {
    this.priority = 'critical';
    return this;
  }

  withRequiredMemoryMb(memoryMb: number): GpuTaskBuilder {
    this.requiredMemoryMb = memoryMb;
    return this;
  }

  withRequiredGpus(count: number): GpuTaskBuilder {
    this.requiredGpus = count;
    return this;
  }

  withEstimatedDurationMs(durationMs: number): GpuTaskBuilder {
    this.estimatedDurationMs = durationMs;
    return this;
  }

  withPayload(payload: unknown): GpuTaskBuilder {
    this.payload = payload;
    return this;
  }

  withUserId(userId: string): GpuTaskBuilder {
    this.userId = userId;
    return this;
  }

  build(): GpuTask {
    return {
      id: this.taskId,
      name: this.name,
      priority: this.priority,
      requiredMemoryMb: this.requiredMemoryMb,
      requiredGpus: this.requiredGpus,
      estimatedDurationMs: this.estimatedDurationMs,
      payload: this.payload,
      userId: this.userId
    };
  }

  static create(): GpuTaskBuilder {
    return new GpuTaskBuilder();
  }

  static createInferenceTask(): GpuTask {
    return new GpuTaskBuilder()
      .withName('model-inference')
      .withLowPriority()
      .withRequiredMemoryMb(512)
      .withPayload({ model: 'bert-inference', batchSize: 16 })
      .build();
  }

  static createTrainingTask(): GpuTask {
    return new GpuTaskBuilder()
      .withName('model-training')
      .withHighPriority()
      .withRequiredMemoryMb(4096)
      .withRequiredGpus(2)
      .withEstimatedDurationMs(60000)
      .withPayload({ model: 'bert-training', epochs: 10 })
      .build();
  }

  static createCriticalTask(): GpuTask {
    return new GpuTaskBuilder()
      .withName('critical-real-time')
      .withCriticalPriority()
      .withRequiredMemoryMb(2048)
      .withEstimatedDurationMs(1000)
      .withPayload({ model: 'realtime-prediction' })
      .build();
  }

  static createMemoryIntensiveTask(): GpuTask {
    return new GpuTaskBuilder()
      .withName('large-model-inference')
      .withHighPriority()
      .withRequiredMemoryMb(8192)
      .withRequiredGpus(4)
      .withPayload({ model: 'llama-70b' })
      .build();
  }

  static createBatch(count: number, priority: TaskPriority = 'medium'): GpuTask[] {
    return Array.from({ length: count }, (_, i) =>
      new GpuTaskBuilder()
        .withTaskId(`batch_task_${i}`)
        .withName(`task-${i}`)
        .withPriority(priority)
        .withRequiredMemoryMb(256)
        .build()
    );
  }
}
