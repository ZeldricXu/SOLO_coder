import { FlowDefinition, FlowNode, FlowConnection, ValidationResult, FlowExecutionContext, FlowTraceEntry } from '../../types/flow';
import { NodeValidator } from './NodeValidator';
import { ConnectionValidator } from './ConnectionValidator';
import { generateId, getCurrentTimestamp, deepClone } from '../../common/utils';
import { NotFoundError, ValidationError as AppValidationError, AppError } from '../../common/errors';

interface FlowSnapshot {
  flow: FlowDefinition;
  timestamp: string;
  operation: string;
}

export class FlowDesigner {
  private nodeValidator: NodeValidator;
  private connectionValidator: ConnectionValidator;
  private flows: Map<string, FlowDefinition>;
  private executionContexts: Map<string, FlowExecutionContext>;
  private snapshots: Map<string, FlowSnapshot[]>;
  private maxSnapshotsPerFlow: number;

  constructor(maxSnapshotsPerFlow: number = 10) {
    this.nodeValidator = new NodeValidator();
    this.connectionValidator = new ConnectionValidator();
    this.flows = new Map();
    this.executionContexts = new Map();
    this.snapshots = new Map();
    this.maxSnapshotsPerFlow = maxSnapshotsPerFlow;
  }

  private saveSnapshot(flowId: string, operation: string): void {
    const flow = this.flows.get(flowId);
    if (!flow) return;

    const snapshot: FlowSnapshot = {
      flow: deepClone(flow),
      timestamp: getCurrentTimestamp(),
      operation
    };

    if (!this.snapshots.has(flowId)) {
      this.snapshots.set(flowId, []);
    }

    const flowSnapshots = this.snapshots.get(flowId)!;
    flowSnapshots.unshift(snapshot);

    if (flowSnapshots.length > this.maxSnapshotsPerFlow) {
      flowSnapshots.pop();
    }
  }

  restoreSnapshot(flowId: string, snapshotIndex: number = 0): FlowDefinition {
    const flowSnapshots = this.snapshots.get(flowId);
    if (!flowSnapshots || flowSnapshots.length === 0) {
      throw new NotFoundError('没有可用的快照');
    }

    if (snapshotIndex < 0 || snapshotIndex >= flowSnapshots.length) {
      throw new AppValidationError('快照索引超出范围', {
        maxIndex: flowSnapshots.length - 1
      });
    }

    const snapshot = flowSnapshots[snapshotIndex];
    this.flows.set(flowId, deepClone(snapshot.flow));

    return this.getFlow(flowId);
  }

  getSnapshots(flowId: string): FlowSnapshot[] {
    return this.snapshots.get(flowId) || [];
  }

  createFlow(name: string, createdBy: string, description?: string): FlowDefinition {
    const now = getCurrentTimestamp();
    const flow: FlowDefinition = {
      id: generateId('flow'),
      name,
      description,
      version: 1,
      nodes: [],
      connections: [],
      status: 'draft',
      createdBy,
      createdAt: now,
      updatedAt: now
    };

    this.flows.set(flow.id, flow);
    this.saveSnapshot(flow.id, 'create');
    return flow;
  }

  getFlow(flowId: string): FlowDefinition {
    const flow = this.flows.get(flowId);
    if (!flow) {
      throw new NotFoundError(`流程不存在: ${flowId}`);
    }
    return flow;
  }

  getAllFlows(): FlowDefinition[] {
    return Array.from(this.flows.values());
  }

  updateFlow(flowId: string, updates: Partial<Omit<FlowDefinition, 'id' | 'createdAt' | 'createdBy'>>): FlowDefinition {
    const flow = this.getFlow(flowId);
    this.saveSnapshot(flowId, 'update');

    const updated = {
      ...flow,
      ...updates,
      version: flow.version + 1,
      updatedAt: getCurrentTimestamp()
    };

    this.flows.set(flowId, updated);
    return updated;
  }

  deleteFlow(flowId: string): void {
    if (!this.flows.has(flowId)) {
      throw new NotFoundError(`流程不存在: ${flowId}`);
    }
    this.flows.delete(flowId);
    this.snapshots.delete(flowId);
  }

  addNode(flowId: string, nodeData: Omit<FlowNode, 'id' | 'createdAt' | 'updatedAt' | 'inputs' | 'outputs'>): FlowNode {
    const flow = this.getFlow(flowId);
    this.saveSnapshot(flowId, 'add_node');

    try {
      const now = getCurrentTimestamp();
      const node: FlowNode = {
        ...nodeData,
        id: generateId('node'),
        inputs: [],
        outputs: [],
        createdAt: now,
        updatedAt: now
      };

      const validation = this.nodeValidator.validate(node);
      if (!validation.valid) {
        throw new AppValidationError('节点验证失败', { errors: validation.errors });
      }

      const flowCopy = deepClone(flow);
      flowCopy.nodes.push(node);
      flowCopy.updatedAt = now;
      flowCopy.version++;

      this.flows.set(flowId, flowCopy);
      return node;
    } catch (error) {
      this.restoreSnapshot(flowId, 0);
      throw error;
    }
  }

  updateNode(flowId: string, nodeId: string, updates: Partial<Omit<FlowNode, 'id' | 'createdAt'>>): FlowNode {
    const flow = this.getFlow(flowId);
    const nodeIndex = flow.nodes.findIndex(n => n.id === nodeId);
    if (nodeIndex === -1) {
      throw new NotFoundError(`节点不存在: ${nodeId}`);
    }

    this.saveSnapshot(flowId, 'update_node');

    try {
      const node = flow.nodes[nodeIndex];
      const updated = {
        ...node,
        ...updates,
        updatedAt: getCurrentTimestamp()
      };

      const validation = this.nodeValidator.validate(updated);
      if (!validation.valid) {
        throw new AppValidationError('节点验证失败', { errors: validation.errors });
      }

      const flowCopy = deepClone(flow);
      flowCopy.nodes[nodeIndex] = updated;
      flowCopy.updatedAt = getCurrentTimestamp();
      flowCopy.version++;

      this.flows.set(flowId, flowCopy);
      return updated;
    } catch (error) {
      this.restoreSnapshot(flowId, 0);
      throw error;
    }
  }

  removeNode(flowId: string, nodeId: string): void {
    const flow = this.getFlow(flowId);
    const nodeIndex = flow.nodes.findIndex(n => n.id === nodeId);
    if (nodeIndex === -1) {
      throw new NotFoundError(`节点不存在: ${nodeId}`);
    }

    this.saveSnapshot(flowId, 'remove_node');

    try {
      const flowCopy = deepClone(flow);
      flowCopy.nodes.splice(nodeIndex, 1);
      flowCopy.connections = flowCopy.connections.filter(
        c => c.sourceNodeId !== nodeId && c.targetNodeId !== nodeId
      );
      flowCopy.updatedAt = getCurrentTimestamp();
      flowCopy.version++;

      this.flows.set(flowId, flowCopy);
    } catch (error) {
      this.restoreSnapshot(flowId, 0);
      throw error;
    }
  }

  addConnection(
    flowId: string,
    connectionData: Omit<FlowConnection, 'id' | 'createdAt'>
  ): FlowConnection {
    const flow = this.getFlow(flowId);
    this.saveSnapshot(flowId, 'add_connection');

    try {
      const now = getCurrentTimestamp();
      const connection: FlowConnection = {
        ...connectionData,
        id: generateId('conn'),
        createdAt: now
      };

      const validation = this.connectionValidator.validate(connection, flow.nodes);
      if (!validation.valid) {
        throw new AppValidationError('连接验证失败', { errors: validation.errors });
      }

      const flowCopy = deepClone(flow);
      flowCopy.connections.push(connection);

      const sourceNode = flowCopy.nodes.find(n => n.id === connection.sourceNodeId);
      const targetNode = flowCopy.nodes.find(n => n.id === connection.targetNodeId);

      if (!sourceNode || !targetNode) {
        throw new AppValidationError('连接的节点不存在', {
          sourceNodeId: connection.sourceNodeId,
          targetNodeId: connection.targetNodeId
        });
      }

      if (sourceNode.outputs.includes(connection.targetNodeId)) {
        throw new AppValidationError('连接已存在', {
          sourceNodeId: connection.sourceNodeId,
          targetNodeId: connection.targetNodeId
        });
      }

      sourceNode.outputs.push(connection.targetNodeId);
      sourceNode.updatedAt = now;
      targetNode.inputs.push(connection.sourceNodeId);
      targetNode.updatedAt = now;

      flowCopy.updatedAt = now;
      flowCopy.version++;

      this.flows.set(flowId, flowCopy);
      return connection;
    } catch (error) {
      this.restoreSnapshot(flowId, 0);
      throw error;
    }
  }

  removeConnection(flowId: string, connectionId: string): void {
    const flow = this.getFlow(flowId);
    const connIndex = flow.connections.findIndex(c => c.id === connectionId);
    if (connIndex === -1) {
      throw new NotFoundError(`连接不存在: ${connectionId}`);
    }

    this.saveSnapshot(flowId, 'remove_connection');

    try {
      const flowCopy = deepClone(flow);
      const connection = flowCopy.connections[connIndex];

      const sourceNode = flowCopy.nodes.find(n => n.id === connection.sourceNodeId);
      const targetNode = flowCopy.nodes.find(n => n.id === connection.targetNodeId);

      if (sourceNode) {
        sourceNode.outputs = sourceNode.outputs.filter(id => id !== connection.targetNodeId);
      }
      if (targetNode) {
        targetNode.inputs = targetNode.inputs.filter(id => id !== connection.sourceNodeId);
      }

      flowCopy.connections.splice(connIndex, 1);
      flowCopy.updatedAt = getCurrentTimestamp();
      flowCopy.version++;

      this.flows.set(flowId, flowCopy);
    } catch (error) {
      this.restoreSnapshot(flowId, 0);
      throw error;
    }
  }

  batchUpdate(
    flowId: string,
    operations: Array<
      | { type: 'add_node'; data: Parameters<FlowDesigner['addNode']>[1] }
      | { type: 'update_node'; nodeId: string; data: Parameters<FlowDesigner['updateNode']>[2] }
      | { type: 'remove_node'; nodeId: string }
      | { type: 'add_connection'; data: Parameters<FlowDesigner['addConnection']>[1] }
      | { type: 'remove_connection'; connectionId: string }
    >
  ): FlowDefinition {
    const flow = this.getFlow(flowId);
    this.saveSnapshot(flowId, 'batch_update');

    try {
      let currentFlow = deepClone(flow);
      const now = getCurrentTimestamp();

      for (const op of operations) {
        switch (op.type) {
          case 'add_node': {
            const node: FlowNode = {
              ...op.data,
              id: generateId('node'),
              inputs: [],
              outputs: [],
              createdAt: now,
              updatedAt: now
            };
            const validation = this.nodeValidator.validate(node);
            if (!validation.valid) {
              throw new AppValidationError('节点验证失败', { errors: validation.errors });
            }
            currentFlow.nodes.push(node);
            break;
          }
          case 'update_node': {
            const nodeIndex = currentFlow.nodes.findIndex(n => n.id === op.nodeId);
            if (nodeIndex === -1) {
              throw new NotFoundError(`节点不存在: ${op.nodeId}`);
            }
            const updated = {
              ...currentFlow.nodes[nodeIndex],
              ...op.data,
              updatedAt: now
            };
            const validation = this.nodeValidator.validate(updated);
            if (!validation.valid) {
              throw new AppValidationError('节点验证失败', { errors: validation.errors });
            }
            currentFlow.nodes[nodeIndex] = updated;
            break;
          }
          case 'remove_node': {
            const nodeIndex = currentFlow.nodes.findIndex(n => n.id === op.nodeId);
            if (nodeIndex === -1) {
              throw new NotFoundError(`节点不存在: ${op.nodeId}`);
            }
            currentFlow.nodes.splice(nodeIndex, 1);
            currentFlow.connections = currentFlow.connections.filter(
              c => c.sourceNodeId !== op.nodeId && c.targetNodeId !== op.nodeId
            );
            break;
          }
          case 'add_connection': {
            const connection: FlowConnection = {
              ...op.data,
              id: generateId('conn'),
              createdAt: now
            };
            const validation = this.connectionValidator.validate(connection, currentFlow.nodes);
            if (!validation.valid) {
              throw new AppValidationError('连接验证失败', { errors: validation.errors });
            }

            const sourceNode = currentFlow.nodes.find(n => n.id === connection.sourceNodeId);
            const targetNode = currentFlow.nodes.find(n => n.id === connection.targetNodeId);

            if (!sourceNode || !targetNode) {
              throw new AppValidationError('连接的节点不存在');
            }

            currentFlow.connections.push(connection);
            sourceNode.outputs.push(connection.targetNodeId);
            sourceNode.updatedAt = now;
            targetNode.inputs.push(connection.sourceNodeId);
            targetNode.updatedAt = now;
            break;
          }
          case 'remove_connection': {
            const connIndex = currentFlow.connections.findIndex(c => c.id === op.connectionId);
            if (connIndex === -1) {
              throw new NotFoundError(`连接不存在: ${op.connectionId}`);
            }
            const connection = currentFlow.connections[connIndex];
            const sourceNode = currentFlow.nodes.find(n => n.id === connection.sourceNodeId);
            const targetNode = currentFlow.nodes.find(n => n.id === connection.targetNodeId);

            if (sourceNode) {
              sourceNode.outputs = sourceNode.outputs.filter(id => id !== connection.targetNodeId);
            }
            if (targetNode) {
              targetNode.inputs = targetNode.inputs.filter(id => id !== connection.sourceNodeId);
            }

            currentFlow.connections.splice(connIndex, 1);
            break;
          }
        }
      }

      currentFlow.updatedAt = now;
      currentFlow.version++;

      this.flows.set(flowId, currentFlow);
      return currentFlow;
    } catch (error) {
      this.restoreSnapshot(flowId, 0);
      throw error;
    }
  }

  validateFlow(flowId: string): ValidationResult {
    const flow = this.getFlow(flowId);

    const nodeValidation = this.nodeValidator.validateAll(flow.nodes);
    const connValidation = this.connectionValidator.validateAll(flow.connections, flow.nodes);

    return {
      valid: nodeValidation.valid && connValidation.valid,
      errors: [...nodeValidation.errors, ...connValidation.errors],
      warnings: [...nodeValidation.warnings, ...connValidation.warnings]
    };
  }

  publishFlow(flowId: string): FlowDefinition {
    const validation = this.validateFlow(flowId);
    if (!validation.valid) {
      throw new AppValidationError('流程验证失败，无法发布', { errors: validation.errors });
    }

    return this.updateFlow(flowId, { status: 'published' });
  }

  archiveFlow(flowId: string): FlowDefinition {
    return this.updateFlow(flowId, { status: 'archived' });
  }

  startExecution(flowId: string, initialVariables: Record<string, unknown> = {}): FlowExecutionContext {
    const flow = this.getFlow(flowId);
    if (flow.status !== 'published') {
      throw new AppValidationError('只能执行已发布的流程');
    }

    const startNode = flow.nodes.find(n => n.type === 'start');
    if (!startNode) {
      throw new AppValidationError('流程没有开始节点');
    }

    const now = getCurrentTimestamp();
    const context: FlowExecutionContext = {
      flowId,
      instanceId: generateId('exec'),
      currentNodeId: startNode.id,
      variables: { ...initialVariables },
      startTime: now,
      status: 'running',
      trace: [{
        nodeId: startNode.id,
        timestamp: now,
        status: 'entered'
      }]
    };

    this.executionContexts.set(context.instanceId, context);
    return context;
  }

  getExecution(instanceId: string): FlowExecutionContext {
    const context = this.executionContexts.get(instanceId);
    if (!context) {
      throw new NotFoundError(`执行实例不存在: ${instanceId}`);
    }
    return context;
  }

  executeStep(instanceId: string): FlowExecutionContext {
    const context = this.getExecution(instanceId);
    if (context.status !== 'running') {
      throw new AppValidationError(`流程实例状态为 ${context.status}，无法继续执行`);
    }

    const flow = this.getFlow(context.flowId);
    const currentNode = flow.nodes.find(n => n.id === context.currentNodeId);
    if (!currentNode) {
      throw new NotFoundError(`当前节点不存在: ${context.currentNodeId}`);
    }

    const now = getCurrentTimestamp();
    context.trace.push({
      nodeId: currentNode.id,
      timestamp: now,
      status: 'exited'
    });

    if (currentNode.type === 'end') {
      context.status = 'completed';
      context.currentNodeId = undefined;
      this.executionContexts.set(instanceId, context);
      return context;
    }

    const nextConnections = flow.connections.filter(c => c.sourceNodeId === currentNode.id);
    if (nextConnections.length === 0) {
      context.status = 'completed';
      context.currentNodeId = undefined;
      this.executionContexts.set(instanceId, context);
      return context;
    }

    if (currentNode.type === 'condition') {
      const nextConn = this.evaluateConditions(currentNode, nextConnections, context.variables);
      if (nextConn) {
        context.currentNodeId = nextConn.targetNodeId;
        context.trace.push({
          nodeId: nextConn.targetNodeId,
          timestamp: getCurrentTimestamp(),
          status: 'entered'
        });
      }
    } else {
      const nextConn = nextConnections[0];
      context.currentNodeId = nextConn.targetNodeId;
      context.trace.push({
        nodeId: nextConn.targetNodeId,
        timestamp: getCurrentTimestamp(),
        status: 'entered'
      });
    }

    this.executionContexts.set(instanceId, context);
    return context;
  }

  private evaluateConditions(
    node: FlowNode,
    connections: FlowConnection[],
    variables: Record<string, unknown>
  ): FlowConnection | undefined {
    if (!node.config.conditions) {
      return connections[0];
    }

    for (const conn of connections) {
      if (!conn.condition) {
        return conn;
      }

      const conditionMet = this.evaluateCondition(conn.condition, variables);
      if (conditionMet) {
        return conn;
      }
    }

    return connections[connections.length - 1];
  }

  private evaluateCondition(
    condition: { field: string; operator: string; value: unknown },
    variables: Record<string, unknown>
  ): boolean {
    const fieldValue = variables[condition.field];
    if (fieldValue === undefined) {
      return false;
    }

    switch (condition.operator) {
      case 'eq':
        return fieldValue === condition.value;
      case 'ne':
        return fieldValue !== condition.value;
      case 'gt':
        return Number(fieldValue) > Number(condition.value);
      case 'lt':
        return Number(fieldValue) < Number(condition.value);
      case 'gte':
        return Number(fieldValue) >= Number(condition.value);
      case 'lte':
        return Number(fieldValue) <= Number(condition.value);
      case 'contains':
        return String(fieldValue).includes(String(condition.value));
      case 'in':
        return Array.isArray(condition.value) && condition.value.includes(fieldValue);
      default:
        return false;
    }
  }

  getConnectionRules() {
    return this.connectionValidator.getConnectionRules();
  }

  getStats() {
    return {
      totalFlows: this.flows.size,
      totalExecutions: this.executionContexts.size,
      flowsByStatus: Array.from(this.flows.values()).reduce((acc, f) => {
        acc[f.status] = (acc[f.status] || 0) + 1;
        return acc;
      }, {} as Record<string, number>)
    };
  }
}
