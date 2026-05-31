import { PreemptionStrategy, ScheduledTask, TaskPriority } from '../../../core/ports';

const PRIORITY_ORDER: Record<TaskPriority, number> = {
  low: 0,
  medium: 1,
  high: 2,
  critical: 3
};

export class PriorityBasedPreemption implements PreemptionStrategy {
  selectVictim(runningTasks: ScheduledTask[], incomingTask: ScheduledTask): ScheduledTask | null {
    const incomingPriority = PRIORITY_ORDER[incomingTask.priority];
    const eligibleVictims = runningTasks.filter(
      task => PRIORITY_ORDER[task.priority] < incomingPriority
    );

    if (eligibleVictims.length === 0) {
      return null;
    }

    eligibleVictims.sort((a, b) => {
      const priorityDiff = PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority];
      if (priorityDiff !== 0) return priorityDiff;

      const memoryDiff = a.requiredResources.gpuMemoryMb - b.requiredResources.gpuMemoryMb;
      if (memoryDiff !== 0) return -memoryDiff;

      return a.progress - b.progress;
    });

    return eligibleVictims[0];
  }
}

export class LowestProgressPreemption implements PreemptionStrategy {
  selectVictim(runningTasks: ScheduledTask[], incomingTask: ScheduledTask): ScheduledTask | null {
    const incomingPriority = PRIORITY_ORDER[incomingTask.priority];
    const eligibleVictims = runningTasks.filter(
      task => PRIORITY_ORDER[task.priority] < incomingPriority
    );

    if (eligibleVictims.length === 0) {
      return null;
    }

    eligibleVictims.sort((a, b) => a.progress - b.progress);
    return eligibleVictims[0];
  }
}

export class MemoryOptimizedPreemption implements PreemptionStrategy {
  selectVictim(runningTasks: ScheduledTask[], incomingTask: ScheduledTask): ScheduledTask | null {
    const incomingPriority = PRIORITY_ORDER[incomingTask.priority];
    const requiredMemory = incomingTask.requiredResources.gpuMemoryMb;

    const eligibleVictims = runningTasks.filter(
      task => PRIORITY_ORDER[task.priority] < incomingPriority &&
              task.requiredResources.gpuMemoryMb >= requiredMemory
    );

    if (eligibleVictims.length === 0) {
      return null;
    }

    eligibleVictims.sort((a, b) => {
      const memoryDiff = a.requiredResources.gpuMemoryMb - b.requiredResources.gpuMemoryMb;
      if (memoryDiff !== 0) return memoryDiff;
      return a.progress - b.progress;
    });

    return eligibleVictims[0];
  }
}

export const preemptionStrategies = {
  priority: new PriorityBasedPreemption(),
  lowestProgress: new LowestProgressPreemption(),
  memoryOptimized: new MemoryOptimizedPreemption()
};
