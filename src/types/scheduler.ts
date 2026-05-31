export interface Task {
  id: string;
  name: string;
  type: 'cron' | 'interval' | 'one_time';
  handler: string;
  payload?: Record<string, unknown>;
  schedule?: string;
  intervalMs?: number;
  runAt?: string;
  priority: 'low' | 'medium' | 'high' | 'critical';
  status: TaskStatus;
  retryPolicy: RetryPolicy;
  timeoutMs?: number;
  createdAt: string;
  updatedAt: string;
}

export type TaskStatus =
  | 'pending'
  | 'scheduled'
  | 'running'
  | 'completed'
  | 'failed'
  | 'paused'
  | 'cancelled'
  | 'timeout';

export interface RetryPolicy {
  maxAttempts: number;
  backoffMs: number;
  backoffMultiplier: number;
}

export interface TaskExecution {
  id: string;
  taskId: string;
  attempt: number;
  status: TaskStatus;
  startedAt: string;
  endedAt?: string;
  durationMs?: number;
  result?: Record<string, unknown>;
  error?: ErrorDetail;
  workerId?: string;
}

export interface ScheduledTask {
  taskId: string;
  scheduledAt: string;
  job?: unknown;
}

export interface TaskFilter {
  status?: TaskStatus[];
  type?: string[];
  priority?: string[];
  createdAfter?: string;
  createdBefore?: string;
}

export interface TaskStats {
  total: number;
  byStatus: Record<TaskStatus, number>;
  byType: Record<string, number>;
  avgDurationMs: number;
  successRate: number;
}

export interface TaskEvent {
  taskId: string;
  executionId?: string;
  event: 'created' | 'started' | 'completed' | 'failed' | 'timeout' | 'cancelled';
  timestamp: string;
  data?: Record<string, unknown>;
}

export type TaskEventListener = (event: TaskEvent) => void | Promise<void>;
