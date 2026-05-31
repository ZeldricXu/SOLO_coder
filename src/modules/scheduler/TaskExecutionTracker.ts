import { RequestContext, generateId, logger, ContextLogger, eventBus } from '../../common';

export type TaskPhase =
  | 'pending'
  | 'queued'
  | 'scheduled'
  | 'running'
  | 'suspended'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'timed_out';

export interface TaskExecution {
  run_id: string;
  entity_id: string;
  task_type: string;
  phase: TaskPhase;
  progress: number;
  priority: 'low' | 'medium' | 'high' | 'critical';
  started_at: string;
  completed_at: string | null;
  error_detail: string | null;
  metadata: Record<string, unknown>;
  parent_id?: string;
  child_ids: string[];
  attempts: number;
  max_attempts: number;
}

export interface TaskExecutionQuery {
  entity_id?: string;
  task_type?: string;
  phase?: TaskPhase;
  priority?: string;
  started_after?: string;
  started_before?: string;
  limit?: number;
  offset?: number;
}

export interface TaskExecutionUpdate {
  phase?: TaskPhase;
  progress?: number;
  error_detail?: string | null;
  metadata?: Record<string, unknown>;
}

export interface TaskResult {
  run_id: string;
  success: boolean;
  output?: unknown;
  error?: string;
  completed_at: string;
  duration_ms: number;
}

export class TaskExecutionTracker {
  private executions: Map<string, TaskExecution> = new Map();
  private phaseTransitions: Map<string, Array<{ from: TaskPhase; to: TaskPhase; timestamp: string; reason?: string }>> = new Map();

  async createExecution(
    ctx: RequestContext,
    entity_id: string,
    task_type: string,
    options: {
      priority?: 'low' | 'medium' | 'high' | 'critical';
      max_attempts?: number;
      metadata?: Record<string, unknown>;
      parent_id?: string;
    } = {}
  ): Promise<string> {
    const log = new ContextLogger(ctx);
    const run_id = generateId('run');
    const now = new Date().toISOString();

    const execution: TaskExecution = {
      run_id,
      entity_id,
      task_type,
      phase: 'pending',
      progress: 0,
      priority: options.priority || 'medium',
      started_at: now,
      completed_at: null,
      error_detail: null,
      metadata: options.metadata || {},
      parent_id: options.parent_id,
      child_ids: [],
      attempts: 0,
      max_attempts: options.max_attempts || 3
    };

    this.executions.set(run_id, execution);
    this.phaseTransitions.set(run_id, []);

    if (options.parent_id) {
      const parent = this.executions.get(options.parent_id);
      if (parent) {
        parent.child_ids.push(run_id);
      }
    }

    eventBus.emit('task.created', { run_id, entity_id, task_type });
    log.info('Task execution created', { run_id, entity_id, task_type });

    return run_id;
  }

  async updateExecution(
    ctx: RequestContext,
    run_id: string,
    update: TaskExecutionUpdate
  ): Promise<boolean> {
    const log = new ContextLogger(ctx);
    const execution = this.executions.get(run_id);

    if (!execution) {
      log.warn('Attempted to update non-existent execution', { run_id });
      return false;
    }

    const previousPhase = execution.phase;

    if (update.phase !== undefined) {
      this.recordPhaseTransition(run_id, previousPhase, update.phase);

      if (this.isTerminalPhase(update.phase)) {
        execution.completed_at = new Date().toISOString();
        eventBus.emit(`task.${update.phase}`, { run_id, entity_id: execution.entity_id });
      }

      execution.phase = update.phase;
    }

    if (update.progress !== undefined) {
      execution.progress = Math.max(0, Math.min(100, update.progress));
    }

    if (update.error_detail !== undefined) {
      execution.error_detail = update.error_detail;
    }

    if (update.metadata !== undefined) {
      execution.metadata = { ...execution.metadata, ...update.metadata };
    }

    log.debug('Task execution updated', { run_id, phase: execution.phase, progress: execution.progress });
    return true;
  }

  private recordPhaseTransition(run_id: string, from: TaskPhase, to: TaskPhase, reason?: string): void {
    const transitions = this.phaseTransitions.get(run_id) || [];
    transitions.push({
      from,
      to,
      timestamp: new Date().toISOString(),
      reason
    });
    this.phaseTransitions.set(run_id, transitions);
  }

  private isTerminalPhase(phase: TaskPhase): boolean {
    return ['completed', 'failed', 'cancelled', 'timed_out'].includes(phase);
  }

  async getExecution(run_id: string): Promise<TaskExecution | null> {
    return this.executions.get(run_id) || null;
  }

  async getPhaseTransitions(run_id: string): Promise<Array<{ from: TaskPhase; to: TaskPhase; timestamp: string; reason?: string }>> {
    return this.phaseTransitions.get(run_id) || [];
  }

  async queryExecutions(query: TaskExecutionQuery): Promise<{
    executions: TaskExecution[];
    total: number;
  }> {
    let results = Array.from(this.executions.values());

    if (query.entity_id) {
      results = results.filter(e => e.entity_id === query.entity_id);
    }

    if (query.task_type) {
      results = results.filter(e => e.task_type === query.task_type);
    }

    if (query.phase) {
      results = results.filter(e => e.phase === query.phase);
    }

    if (query.priority) {
      results = results.filter(e => e.priority === query.priority);
    }

    if (query.started_after) {
      results = results.filter(e => e.started_at >= query.started_after!);
    }

    if (query.started_before) {
      results = results.filter(e => e.started_at <= query.started_before!);
    }

    results.sort((a, b) => new Date(b.started_at).getTime() - new Date(a.started_at).getTime());

    const total = results.length;

    if (query.offset) {
      results = results.slice(query.offset);
    }

    if (query.limit) {
      results = results.slice(0, query.limit);
    }

    return { executions: results, total };
  }

  async incrementAttempt(run_id: string): Promise<number> {
    const execution = this.executions.get(run_id);
    if (!execution) {
      return -1;
    }

    execution.attempts++;
    return execution.attempts;
  }

  async canRetry(run_id: string): Promise<boolean> {
    const execution = this.executions.get(run_id);
    if (!execution) {
      return false;
    }

    return execution.attempts < execution.max_attempts;
  }

  async markRunning(run_id: string): Promise<boolean> {
    const execution = this.executions.get(run_id);
    if (!execution) {
      return false;
    }

    this.recordPhaseTransition(run_id, execution.phase, 'running');
    execution.phase = 'running';
    execution.started_at = new Date().toISOString();
    eventBus.emit('task.started', { run_id, entity_id: execution.entity_id });

    return true;
  }

  async markCompleted(run_id: string, output?: unknown): Promise<TaskResult> {
    const execution = this.executions.get(run_id)!;
    const now = new Date().toISOString();

    this.recordPhaseTransition(run_id, execution.phase, 'completed');
    execution.phase = 'completed';
    execution.progress = 100;
    execution.completed_at = now;
    execution.metadata = { ...execution.metadata, output };

    eventBus.emit('task.completed', { run_id, entity_id: execution.entity_id });

    return {
      run_id,
      success: true,
      output,
      completed_at: now,
      duration_ms: new Date(now).getTime() - new Date(execution.started_at).getTime()
    };
  }

  async markFailed(run_id: string, error: string): Promise<TaskResult> {
    const execution = this.executions.get(run_id)!;
    const now = new Date().toISOString();

    this.recordPhaseTransition(run_id, execution.phase, 'failed', error);
    execution.phase = 'failed';
    execution.completed_at = now;
    execution.error_detail = error;

    eventBus.emit('task.failed', { run_id, entity_id: execution.entity_id, error });

    return {
      run_id,
      success: false,
      error,
      completed_at: now,
      duration_ms: new Date(now).getTime() - new Date(execution.started_at).getTime()
    };
  }

  async markCancelled(run_id: string, reason?: string): Promise<boolean> {
    const execution = this.executions.get(run_id);
    if (!execution) {
      return false;
    }

    this.recordPhaseTransition(run_id, execution.phase, 'cancelled', reason);
    execution.phase = 'cancelled';
    execution.completed_at = new Date().toISOString();
    execution.error_detail = reason || 'Cancelled';

    eventBus.emit('task.cancelled', { run_id, entity_id: execution.entity_id, reason });
    return true;
  }

  async getStatistics(): Promise<{
    total: number;
    byPhase: Record<TaskPhase, number>;
    byType: Record<string, number>;
    averageDuration: number;
  }> {
    const executions = Array.from(this.executions.values());
    const byPhase: Record<string, number> = {};
    const byType: Record<string, number> = {};
    let totalDuration = 0;
    let completedCount = 0;

    for (const execution of executions) {
      byPhase[execution.phase] = (byPhase[execution.phase] || 0) + 1;
      byType[execution.task_type] = (byType[execution.task_type] || 0) + 1;

      if (execution.completed_at) {
        totalDuration += new Date(execution.completed_at).getTime() - new Date(execution.started_at).getTime();
        completedCount++;
      }
    }

    return {
      total: executions.length,
      byPhase: byPhase as Record<TaskPhase, number>,
      byType,
      averageDuration: completedCount > 0 ? totalDuration / completedCount : 0
    };
  }

  async getChildExecutions(parent_id: string): Promise<TaskExecution[]> {
    const parent = this.executions.get(parent_id);
    if (!parent) {
      return [];
    }

    return parent.child_ids
      .map(id => this.executions.get(id))
      .filter((e): e is TaskExecution => e !== undefined);
  }

  async cleanup(olderThanDays: number = 30): Promise<number> {
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() - olderThanDays);
    const cutoffStr = cutoff.toISOString();

    let deleted = 0;
    for (const [run_id, execution] of this.executions.entries()) {
      if (execution.completed_at && execution.completed_at < cutoffStr) {
        this.executions.delete(run_id);
        this.phaseTransitions.delete(run_id);
        deleted++;
      }
    }

    logger.info('Cleaned up old task executions', { deleted, olderThanDays });
    return deleted;
  }
}
