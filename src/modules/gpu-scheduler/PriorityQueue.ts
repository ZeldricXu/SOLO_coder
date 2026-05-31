import { ScheduledTask, TaskPriority } from '../../core/ports';

const PRIORITY_SCORE: Record<TaskPriority, number> = {
  low: 0,
  medium: 1,
  high: 2,
  critical: 3
};

export class PriorityTaskQueue {
  private queues: Map<TaskPriority, ScheduledTask[]> = new Map();
  private taskIndex: Map<string, ScheduledTask> = new Map();

  constructor() {
    this.queues.set('low', []);
    this.queues.set('medium', []);
    this.queues.set('high', []);
    this.queues.set('critical', []);
  }

  enqueue(task: ScheduledTask): void {
    const queue = this.queues.get(task.priority)!;
    queue.push(task);
    this.taskIndex.set(task.id, task);
  }

  dequeue(): ScheduledTask | null {
    const priorities: TaskPriority[] = ['critical', 'high', 'medium', 'low'];

    for (const priority of priorities) {
      const queue = this.queues.get(priority)!;
      if (queue.length > 0) {
        const task = queue.shift()!;
        this.taskIndex.delete(task.id);
        return task;
      }
    }

    return null;
  }

  peek(): ScheduledTask | null {
    const priorities: TaskPriority[] = ['critical', 'high', 'medium', 'low'];

    for (const priority of priorities) {
      const queue = this.queues.get(priority)!;
      if (queue.length > 0) {
        return queue[0];
      }
    }

    return null;
  }

  remove(taskId: string): boolean {
    const task = this.taskIndex.get(taskId);
    if (!task) return false;

    const queue = this.queues.get(task.priority)!;
    const index = queue.findIndex(t => t.id === taskId);
    if (index > -1) {
      queue.splice(index, 1);
      this.taskIndex.delete(taskId);
      return true;
    }

    return false;
  }

  has(taskId: string): boolean {
    return this.taskIndex.has(taskId);
  }

  getLength(priority?: TaskPriority): number {
    if (priority) {
      return this.queues.get(priority)?.length || 0;
    }

    let total = 0;
    for (const queue of this.queues.values()) {
      total += queue.length;
    }
    return total;
  }

  getTaskById(taskId: string): ScheduledTask | null {
    return this.taskIndex.get(taskId) || null;
  }

  getAllTasks(): ScheduledTask[] {
    return Array.from(this.taskIndex.values());
  }

  clear(): void {
    for (const queue of this.queues.values()) {
      queue.length = 0;
    }
    this.taskIndex.clear();
  }

  isEmpty(): boolean {
    return this.taskIndex.size === 0;
  }
}
