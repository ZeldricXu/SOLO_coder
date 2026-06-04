import { v4 as uuidv4 } from 'uuid';
import {
  OrchestrationSequence,
  OrchestrationInstance,
  OrchestrationStep,
  OrchestrationStepExecution,
  OrchestrationStatus,
  OrchestrationStepStatus,
  OrchestrationCondition,
  NotificationRequest,
  ChannelResult,
  Recipient,
  DeliveryStatus,
} from '../types';
import { db } from '../db';
import { logger } from '../utils/logger';
import { NotificationQueue } from '../queue/NotificationQueue';
import { DeliveryTracker } from '../tracking/DeliveryTracker';
import { TemplateEngine } from '../templates/TemplateEngine';
import { NotificationRouter } from '../router/NotificationRouter';

export class OrchestrationEngine {
  private static instance: OrchestrationEngine;
  private queue: NotificationQueue;
  private tracker: DeliveryTracker;
  private templateEngine: TemplateEngine;
  private router: NotificationRouter;

  private constructor() {
    this.queue = NotificationQueue.getInstance();
    this.tracker = DeliveryTracker.getInstance();
    this.templateEngine = TemplateEngine.getInstance();
    this.router = NotificationRouter.getInstance();
  }

  static getInstance(): OrchestrationEngine {
    if (!OrchestrationEngine.instance) {
      OrchestrationEngine.instance = new OrchestrationEngine();
    }
    return OrchestrationEngine.instance;
  }

  async createSequence(
    tenantId: string,
    name: string,
    steps: OrchestrationStep[],
    createdBy: string,
    description?: string,
    triggerType: 'manual' | 'event' | 'scheduled' = 'manual',
    triggerEvent?: string
  ): Promise<OrchestrationSequence> {
    const sequence: OrchestrationSequence = {
      id: uuidv4(),
      tenant_id: tenantId,
      name,
      description,
      steps: steps.sort((a, b) => a.order - b.order),
      trigger_type: triggerType,
      trigger_event: triggerEvent,
      enabled: true,
      created_by: createdBy,
      created_at: new Date(),
      updated_at: new Date(),
    };

    await db.query(
      `INSERT INTO orchestration_sequences 
       (id, tenant_id, name, description, steps, trigger_type, trigger_event, enabled, created_by, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
      [
        sequence.id,
        sequence.tenant_id,
        sequence.name,
        sequence.description,
        JSON.stringify(sequence.steps),
        sequence.trigger_type,
        sequence.trigger_event,
        sequence.enabled,
        sequence.created_by,
        sequence.created_at,
        sequence.updated_at,
      ]
    );

    logger.info('Orchestration sequence created', { sequenceId: sequence.id, name });
    return sequence;
  }

  async getSequence(tenantId: string, sequenceId: string): Promise<OrchestrationSequence | null> {
    const result = await db.query(
      `SELECT * FROM orchestration_sequences WHERE id = $1 AND tenant_id = $2`,
      [sequenceId, tenantId]
    );
    if (result.rows.length === 0) return null;
    return this.deserializeSequence(result.rows[0]);
  }

  async listSequences(tenantId: string, includeDisabled: boolean = false): Promise<OrchestrationSequence[]> {
    const query = includeDisabled
      ? `SELECT * FROM orchestration_sequences WHERE tenant_id = $1 ORDER BY created_at DESC`
      : `SELECT * FROM orchestration_sequences WHERE tenant_id = $1 AND enabled = true ORDER BY created_at DESC`;
    const result = await db.query(query, [tenantId]);
    return result.rows.map(this.deserializeSequence);
  }

  async updateSequence(
    tenantId: string,
    sequenceId: string,
    updates: Partial<OrchestrationSequence>
  ): Promise<OrchestrationSequence | null> {
    const existing = await this.getSequence(tenantId, sequenceId);
    if (!existing) return null;

    const merged: OrchestrationSequence = {
      ...existing,
      ...updates,
      updated_at: new Date(),
      steps: updates.steps ? updates.steps.sort((a, b) => a.order - b.order) : existing.steps,
    };

    await db.query(
      `UPDATE orchestration_sequences 
       SET name = $1, description = $2, steps = $3, trigger_type = $4, trigger_event = $5, enabled = $6, updated_at = $7
       WHERE id = $8 AND tenant_id = $9`,
      [
        merged.name,
        merged.description,
        JSON.stringify(merged.steps),
        merged.trigger_type,
        merged.trigger_event,
        merged.enabled,
        merged.updated_at,
        sequenceId,
        tenantId,
      ]
    );

    return merged;
  }

  async deleteSequence(tenantId: string, sequenceId: string): Promise<boolean> {
    const result = await db.query(
      `DELETE FROM orchestration_sequences WHERE id = $1 AND tenant_id = $2`,
      [sequenceId, tenantId]
    );
    return result.rowCount > 0;
  }

  async startSequence(
    tenantId: string,
    sequenceId: string,
    recipient: Recipient,
    templateVariables?: Record<string, any>,
    metadata?: Record<string, any>
  ): Promise<OrchestrationInstance> {
    const sequence = await this.getSequence(tenantId, sequenceId);
    if (!sequence || !sequence.enabled) {
      throw new Error('Sequence not found or disabled');
    }

    const instance: OrchestrationInstance = {
      id: uuidv4(),
      sequence_id: sequenceId,
      tenant_id: tenantId,
      recipient,
      status: 'running',
      current_step: 0,
      template_variables: templateVariables,
      started_at: new Date(),
      metadata,
    };

    await db.query(
      `INSERT INTO orchestration_instances 
       (id, sequence_id, tenant_id, recipient, status, current_step, template_variables, started_at, metadata)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
      [
        instance.id,
        instance.sequence_id,
        instance.tenant_id,
        JSON.stringify(instance.recipient),
        instance.status,
        instance.current_step,
        JSON.stringify(instance.template_variables),
        instance.started_at,
        JSON.stringify(instance.metadata),
      ]
    );

    await this.executeStep(instance, sequence.steps[0], sequence);

    logger.info('Orchestration sequence started', {
      instanceId: instance.id,
      sequenceId,
      recipient,
    });

    return instance;
  }

  private async executeStep(
    instance: OrchestrationInstance,
    step: OrchestrationStep,
    sequence: OrchestrationSequence
  ): Promise<OrchestrationStepExecution> {
    const execution: OrchestrationStepExecution = {
      id: uuidv4(),
      instance_id: instance.id,
      sequence_id: sequence.id,
      step_id: step.id,
      status: 'scheduled',
      scheduled_at: new Date(),
      metadata: { step_order: step.order },
    };

    await db.query(
      `INSERT INTO orchestration_step_executions 
       (id, instance_id, sequence_id, step_id, status, scheduled_at, metadata)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [
        execution.id,
        execution.instance_id,
        execution.sequence_id,
        execution.step_id,
        execution.status,
        execution.scheduled_at,
        JSON.stringify(execution.metadata),
      ]
    );

    if (step.delay_seconds && step.delay_seconds > 0) {
      setTimeout(() => {
        this.processDelayedStep(instance, step, sequence, execution.id);
      }, step.delay_seconds * 1000);
    } else {
      await this.processStep(instance, step, sequence, execution.id);
    }

    return execution;
  }

  private async processDelayedStep(
    instance: OrchestrationInstance,
    step: OrchestrationStep,
    sequence: OrchestrationSequence,
    executionId: string
  ) {
    try {
      const currentInstance = await this.getInstance(instance.tenant_id, instance.id);
      if (!currentInstance || currentInstance.status !== 'running') {
        logger.info('Orchestration instance no longer running, skipping step', {
          instanceId: instance.id,
          status: currentInstance?.status,
        });
        return;
      }
      await this.processStep(currentInstance, step, sequence, executionId);
    } catch (err) {
      logger.error('Error processing delayed step', {
        instanceId: instance.id,
        stepId: step.id,
        error: err,
      });
    }
  }

  private async processStep(
    instance: OrchestrationInstance,
    step: OrchestrationStep,
    sequence: OrchestrationSequence,
    executionId: string
  ): Promise<void> {
    await db.query(
      `UPDATE orchestration_step_executions SET status = 'running', started_at = NOW() WHERE id = $1`,
      [executionId]
    );

    try {
      const conditionsMet = await this.evaluateConditions(instance, step.conditions, step.id);
      if (!conditionsMet) {
        logger.info('Step conditions not met, skipping', {
          instanceId: instance.id,
          stepId: step.id,
          stepName: step.name,
        });
        await this.completeStepExecution(executionId, 'skipped', undefined, 'Conditions not met');
        await this.advanceToNextStep(instance, sequence, step);
        return;
      }

      const notification = await this.buildNotification(instance, step);
      const deliveryId = uuidv4();
      await this.tracker.createDeliveryLog(
        deliveryId,
        instance.tenant_id,
        step.notification_type,
        step.channel,
        'smtp',
        this.getRecipientIdentifier(instance.recipient),
        notification.priority || 'medium',
        { orchestration_instance_id: instance.id, step_id: step.id }
      );

      await this.queue.enqueue(notification, deliveryId, step.channel);

      await this.completeStepExecution(executionId, 'running');
      await db.query(
        `UPDATE orchestration_step_executions SET delivery_id = $1 WHERE id = $2`,
        [deliveryId, executionId]
      );

      if (step.terminate_on_success) {
        this.monitorForTermination(instance, sequence, step, executionId, deliveryId);
      } else {
        this.monitorStepCompletion(instance, sequence, step, executionId, deliveryId);
      }
    } catch (err: any) {
      logger.error('Error executing step', {
        instanceId: instance.id,
        stepId: step.id,
        error: err.message,
      });
      await this.completeStepExecution(executionId, 'failed', undefined, err.message);
      await this.updateInstanceStatus(instance.id, 'failed');
    }
  }

  private async evaluateConditions(
    instance: OrchestrationInstance,
    conditions: OrchestrationCondition[] | undefined,
    currentStepId: string
  ): Promise<boolean> {
    if (!conditions || conditions.length === 0) return true;

    for (const condition of conditions) {
      const result = await this.evaluateSingleCondition(instance, condition, currentStepId);
      if (!result) return false;
    }
    return true;
  }

  private async evaluateSingleCondition(
    instance: OrchestrationInstance,
    condition: OrchestrationCondition,
    currentStepId: string
  ): Promise<boolean> {
    switch (condition.type) {
      case 'delivery_status': {
        const stepId = condition.step_id;
        if (!stepId) return true;
        const execution = await this.getStepExecution(instance.id, stepId);
        if (!execution || !execution.result) return false;
        return this.compareValues(execution.result.status, condition.operator, condition.value);
      }
      case 'user_behavior': {
        const stepId = condition.step_id || currentStepId;
        const execution = await this.getStepExecution(instance.id, stepId);
        if (!execution || !execution.delivery_id) return false;
        const logs = await this.tracker.getByDeliveryId(instance.tenant_id, execution.delivery_id);
        const latestLog = logs[0];
        if (!latestLog) return false;
        const fieldValue = condition.field === 'status' ? latestLog.status : latestLog.metadata?.[condition.field];
        return this.compareValues(fieldValue, condition.operator, condition.value);
      }
      case 'time_window': {
        const now = new Date();
        const execution = await this.getStepExecution(instance.id, currentStepId);
        if (!execution || !execution.scheduled_at) return false;
        const elapsed = (now.getTime() - execution.scheduled_at.getTime()) / 1000;
        return this.compareValues(elapsed, condition.operator, condition.value);
      }
      default:
        return true;
    }
  }

  private compareValues(actual: any, operator: string, expected: any): boolean {
    switch (operator) {
      case 'eq':
        return actual === expected;
      case 'ne':
        return actual !== expected;
      case 'gt':
        return actual > expected;
      case 'lt':
        return actual < expected;
      case 'gte':
        return actual >= expected;
      case 'lte':
        return actual <= expected;
      case 'in':
        return Array.isArray(expected) && expected.includes(actual);
      case 'not_in':
        return Array.isArray(expected) && !expected.includes(actual);
      default:
        return true;
    }
  }

  private async buildNotification(instance: OrchestrationInstance, step: OrchestrationStep): Promise<NotificationRequest> {
    let content = step.content;

    if (step.template_id && instance.template_variables) {
      const renderedContent = await this.templateEngine.renderTemplateById(
        instance.tenant_id,
        step.notification_type,
        step.template_id,
        instance.template_variables,
        'en'
      );
      if (renderedContent) {
        content = renderedContent;
      }
    }

    if (!content) {
      throw new Error(`No content or template for step ${step.id}`);
    }

    return {
      tenant_id: instance.tenant_id,
      notification_type: step.notification_type,
      recipient: instance.recipient,
      content,
      channel_preference: [step.channel],
      priority: 'medium',
      metadata: {
        orchestration_instance_id: instance.id,
        orchestration_step_id: step.id,
        ...step.metadata,
        ...instance.metadata,
      },
    };
  }

  private getRecipientIdentifier(recipient: Recipient): string {
    return (
      recipient.email ||
      recipient.phone ||
      recipient.user_id ||
      recipient.push_token ||
      'unknown'
    );
  }

  private async completeStepExecution(
    executionId: string,
    status: OrchestrationStepStatus,
    result?: ChannelResult,
    errorMessage?: string
  ): Promise<void> {
    await db.query(
      `UPDATE orchestration_step_executions 
       SET status = $1, completed_at = NOW(), result = $2, error_message = $3
       WHERE id = $4`,
      [status, result ? JSON.stringify(result) : null, errorMessage, executionId]
    );
  }

  private async updateInstanceStatus(instanceId: string, status: OrchestrationStatus): Promise<void> {
    const updates: any = { status };
    if (status === 'completed' || status === 'failed' || status === 'cancelled') {
      updates.completed_at = new Date();
    }
    await db.query(
      `UPDATE orchestration_instances SET status = $1, completed_at = $2 WHERE id = $3`,
      [status, updates.completed_at || null, instanceId]
    );
  }

  private async advanceToNextStep(
    instance: OrchestrationInstance,
    sequence: OrchestrationSequence,
    currentStep: OrchestrationStep
  ): Promise<void> {
    const nextStepIndex = sequence.steps.findIndex((s) => s.id === currentStep.id) + 1;
    if (nextStepIndex >= sequence.steps.length) {
      await this.updateInstanceStatus(instance.id, 'completed');
      logger.info('Orchestration sequence completed', { instanceId: instance.id });
      return;
    }

    await db.query(
      `UPDATE orchestration_instances SET current_step = $1 WHERE id = $2`,
      [nextStepIndex, instance.id]
    );

    const nextStep = sequence.steps[nextStepIndex];
    const updatedInstance = { ...instance, current_step: nextStepIndex };
    await this.executeStep(updatedInstance, nextStep, sequence);
  }

  private async monitorForTermination(
    instance: OrchestrationInstance,
    sequence: OrchestrationSequence,
    step: OrchestrationStep,
    executionId: string,
    deliveryId: string
  ) {
    const maxWaitTime = 72 * 60 * 60 * 1000;
    const pollInterval = 60 * 1000;
    const startTime = Date.now();

    const checkStatus = async () => {
      if (Date.now() - startTime > maxWaitTime) {
        logger.info('Termination monitor timeout, advancing to next step', {
          instanceId: instance.id,
          stepId: step.id,
        });
        const updatedInstance = await this.getInstance(instance.tenant_id, instance.id);
        if (updatedInstance && updatedInstance.status === 'running') {
          await this.advanceToNextStep(updatedInstance, sequence, step);
        }
        return;
      }

      const logs = await this.tracker.getByDeliveryId(instance.tenant_id, deliveryId);
      const latestLog = logs[0];
      if (latestLog && ['delivered', 'read', 'clicked'].includes(latestLog.status)) {
        logger.info('Step success detected, terminating orchestration', {
          instanceId: instance.id,
          stepId: step.id,
          status: latestLog.status,
        });
        await this.completeStepExecution(executionId, 'completed', {
          channel: step.channel,
          status: latestLog.status as DeliveryStatus,
        });
        await this.updateInstanceStatus(instance.id, 'completed');
        return;
      }

      setTimeout(checkStatus, pollInterval);
    };

    setTimeout(checkStatus, pollInterval);
  }

  private async monitorStepCompletion(
    instance: OrchestrationInstance,
    sequence: OrchestrationSequence,
    step: OrchestrationStep,
    executionId: string,
    deliveryId: string
  ) {
    const maxWaitTime = step.delay_seconds ? (step.delay_seconds + 3600) * 1000 : 24 * 60 * 60 * 1000;
    const pollInterval = 5 * 60 * 1000;
    const startTime = Date.now();

    const checkStatus = async () => {
      if (Date.now() - startTime > maxWaitTime) {
        logger.info('Step completion monitor timeout', {
          instanceId: instance.id,
          stepId: step.id,
        });
        const updatedInstance = await this.getInstance(instance.tenant_id, instance.id);
        if (updatedInstance && updatedInstance.status === 'running') {
          await this.completeStepExecution(executionId, 'failed', undefined, 'Timeout waiting for delivery');
          await this.advanceToNextStep(updatedInstance, sequence, step);
        }
        return;
      }

      const logs = await this.tracker.getByDeliveryId(instance.tenant_id, deliveryId);
      const latestLog = logs[0];
      if (latestLog && ['delivered', 'failed', 'read', 'clicked'].includes(latestLog.status)) {
        const isSuccess = ['delivered', 'read', 'clicked'].includes(latestLog.status);
        const result: ChannelResult = {
          channel: step.channel,
          status: latestLog.status as DeliveryStatus,
          message_id: latestLog.message_id,
        };
        await this.completeStepExecution(executionId, isSuccess ? 'completed' : 'failed', result);
        const updatedInstance = await this.getInstance(instance.tenant_id, instance.id);
        if (updatedInstance && updatedInstance.status === 'running') {
          await this.advanceToNextStep(updatedInstance, sequence, step);
        }
        return;
      }

      setTimeout(checkStatus, pollInterval);
    };

    setTimeout(checkStatus, pollInterval);
  }

  async getInstance(tenantId: string, instanceId: string): Promise<OrchestrationInstance | null> {
    const result = await db.query(
      `SELECT * FROM orchestration_instances WHERE id = $1 AND tenant_id = $2`,
      [instanceId, tenantId]
    );
    if (result.rows.length === 0) return null;
    return this.deserializeInstance(result.rows[0]);
  }

  async getInstanceExecutions(instanceId: string): Promise<OrchestrationStepExecution[]> {
    const result = await db.query(
      `SELECT * FROM orchestration_step_executions 
       WHERE instance_id = $1 
       ORDER BY (metadata->>'step_order')::int ASC, created_at ASC`,
      [instanceId]
    );
    return result.rows.map(this.deserializeStepExecution);
  }

  async cancelInstance(tenantId: string, instanceId: string): Promise<boolean> {
    const instance = await this.getInstance(tenantId, instanceId);
    if (!instance) return false;
    if (instance.status !== 'running') return false;

    await this.updateInstanceStatus(instanceId, 'cancelled');
    return true;
  }

  private getStepExecution(instanceId: string, stepId: string): Promise<OrchestrationStepExecution | null> {
    return db
      .query(
        `SELECT * FROM orchestration_step_executions WHERE instance_id = $1 AND step_id = $2`,
        [instanceId, stepId]
      )
      .then((r) => (r.rows.length > 0 ? this.deserializeStepExecution(r.rows[0]) : null));
  }

  private deserializeSequence(row: any): OrchestrationSequence {
    return {
      ...row,
      steps: typeof row.steps === 'string' ? JSON.parse(row.steps) : row.steps,
      created_at: new Date(row.created_at),
      updated_at: new Date(row.updated_at),
    };
  }

  private deserializeInstance(row: any): OrchestrationInstance {
    return {
      ...row,
      recipient: typeof row.recipient === 'string' ? JSON.parse(row.recipient) : row.recipient,
      template_variables:
        typeof row.template_variables === 'string'
          ? JSON.parse(row.template_variables)
          : row.template_variables,
      metadata: typeof row.metadata === 'string' ? JSON.parse(row.metadata) : row.metadata,
      started_at: new Date(row.started_at),
      completed_at: row.completed_at ? new Date(row.completed_at) : undefined,
    };
  }

  private deserializeStepExecution(row: any): OrchestrationStepExecution {
    return {
      ...row,
      result: typeof row.result === 'string' ? JSON.parse(row.result) : row.result,
      metadata: typeof row.metadata === 'string' ? JSON.parse(row.metadata) : row.metadata,
      scheduled_at: row.scheduled_at ? new Date(row.scheduled_at) : undefined,
      started_at: row.started_at ? new Date(row.started_at) : undefined,
      completed_at: row.completed_at ? new Date(row.completed_at) : undefined,
    };
  }
}
