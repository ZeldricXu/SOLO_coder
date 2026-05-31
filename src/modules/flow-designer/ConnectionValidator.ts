import { FlowNode, FlowConnection, ValidationResult, ValidationError, ValidationWarning, ConnectionRule } from '../../types/flow';
import { isEmpty } from '../../common/utils';

export class ConnectionValidator {
  private connectionRules: Record<string, ConnectionRule> = {
    'start->action': {
      allowedSourceTypes: ['start'],
      allowedTargetTypes: ['action', 'condition', 'delay', 'parallel', 'subflow'],
      maxConnections: 10,
      description: '开始节点可以连接到任意处理节点'
    },
    'action->any': {
      allowedSourceTypes: ['action'],
      allowedTargetTypes: ['action', 'condition', 'delay', 'parallel', 'subflow', 'end'],
      maxConnections: 10,
      description: '动作节点可以连接到任意节点'
    },
    'condition->branch': {
      allowedSourceTypes: ['condition'],
      allowedTargetTypes: ['action', 'condition', 'delay', 'parallel', 'subflow', 'end'],
      minConnections: 2,
      description: '条件节点至少需要两个输出分支'
    },
    'delay->next': {
      allowedSourceTypes: ['delay'],
      allowedTargetTypes: ['action', 'condition', 'delay', 'parallel', 'subflow', 'end'],
      maxConnections: 1,
      description: '延迟节点只能有一个输出'
    },
    'parallel->fork': {
      allowedSourceTypes: ['parallel'],
      allowedTargetTypes: ['action', 'condition', 'delay', 'subflow'],
      minConnections: 2,
      description: '并行节点至少需要两个分支'
    },
    'subflow->continue': {
      allowedSourceTypes: ['subflow'],
      allowedTargetTypes: ['action', 'condition', 'delay', 'parallel', 'subflow', 'end'],
      maxConnections: 10,
      description: '子流程节点可以连接到任意节点'
    }
  };

  validate(connection: FlowConnection, nodes: FlowNode[]): ValidationResult {
    const errors: ValidationError[] = [];
    const warnings: ValidationWarning[] = [];

    if (!connection.id || isEmpty(connection.id)) {
      errors.push({
        code: 'CONNECTION_ID_REQUIRED',
        message: '连线ID不能为空',
        connectionId: connection.id,
        severity: 'error'
      });
    }

    if (!connection.sourceNodeId) {
      errors.push({
        code: 'SOURCE_NODE_REQUIRED',
        message: '源节点不能为空',
        connectionId: connection.id,
        severity: 'error'
      });
    }

    if (!connection.targetNodeId) {
      errors.push({
        code: 'TARGET_NODE_REQUIRED',
        message: '目标节点不能为空',
        connectionId: connection.id,
        severity: 'error'
      });
    }

    if (connection.sourceNodeId === connection.targetNodeId) {
      errors.push({
        code: 'SELF_CONNECTION_NOT_ALLOWED',
        message: '节点不能连接到自身',
        connectionId: connection.id,
        nodeId: connection.sourceNodeId,
        severity: 'error'
      });
    }

    const sourceNode = nodes.find(n => n.id === connection.sourceNodeId);
    const targetNode = nodes.find(n => n.id === connection.targetNodeId);

    if (!sourceNode) {
      errors.push({
        code: 'SOURCE_NODE_NOT_FOUND',
        message: `源节点不存在: ${connection.sourceNodeId}`,
        connectionId: connection.id,
        severity: 'error'
      });
    }

    if (!targetNode) {
      errors.push({
        code: 'TARGET_NODE_NOT_FOUND',
        message: `目标节点不存在: ${connection.targetNodeId}`,
        connectionId: connection.id,
        severity: 'error'
      });
    }

    if (sourceNode && targetNode) {
      const ruleErrors = this.validateConnectionRules(sourceNode, targetNode, connection, nodes);
      errors.push(...ruleErrors);
    }

    if (targetNode && targetNode.type === 'start') {
      errors.push({
        code: 'CONNECT_TO_START_NOT_ALLOWED',
        message: '不能连接到开始节点',
        connectionId: connection.id,
        nodeId: connection.targetNodeId,
        severity: 'error'
      });
    }

    if (sourceNode && sourceNode.type === 'end') {
      errors.push({
        code: 'CONNECT_FROM_END_NOT_ALLOWED',
        message: '不能从结束节点连接出去',
        connectionId: connection.id,
        nodeId: connection.sourceNodeId,
        severity: 'error'
      });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings
    };
  }

  private validateConnectionRules(
    sourceNode: FlowNode,
    targetNode: FlowNode,
    connection: FlowConnection,
    allNodes: FlowNode[]
  ): ValidationError[] {
    const errors: ValidationError[] = [];

    const applicableRules = Object.values(this.connectionRules).filter(rule =>
      rule.allowedSourceTypes.includes(sourceNode.type) &&
      rule.allowedTargetTypes.includes(targetNode.type)
    );

    if (applicableRules.length === 0) {
      errors.push({
        code: 'INVALID_CONNECTION_TYPE',
        message: `不允许从${sourceNode.type}节点连接到${targetNode.type}节点`,
        connectionId: connection.id,
        nodeId: connection.sourceNodeId,
        severity: 'error'
      });
      return errors;
    }

    const outgoingConnections = allNodes.filter(n => n.id === sourceNode.id).length;
    for (const rule of applicableRules) {
      if (rule.maxConnections && outgoingConnections > rule.maxConnections) {
        errors.push({
          code: 'MAX_CONNECTIONS_EXCEEDED',
          message: `${sourceNode.type}节点最多只能有${rule.maxConnections}个输出连接`,
          connectionId: connection.id,
          nodeId: connection.sourceNodeId,
          severity: 'error'
        });
      }
    }

    return errors;
  }

  validateAll(connections: FlowConnection[], nodes: FlowNode[]): ValidationResult {
    const allErrors: ValidationError[] = [];
    const allWarnings: ValidationWarning[] = [];

    const connectionIds = new Set<string>();
    for (const connection of connections) {
      if (connectionIds.has(connection.id)) {
        allErrors.push({
          code: 'DUPLICATE_CONNECTION_ID',
          message: `重复的连线ID: ${connection.id}`,
          connectionId: connection.id,
          severity: 'error'
        });
      }
      connectionIds.add(connection.id);

      const result = this.validate(connection, nodes);
      allErrors.push(...result.errors);
      allWarnings.push(...result.warnings);
    }

    const duplicateConnections = this.findDuplicateConnections(connections);
    for (const dup of duplicateConnections) {
      allWarnings.push({
        code: 'DUPLICATE_CONNECTION_PATH',
        message: `存在重复的连接路径: ${dup.source} -> ${dup.target}`,
        nodeId: dup.source
      });
    }

    const cycleResult = this.detectCycles(connections, nodes);
    if (cycleResult.hasCycle) {
      allErrors.push({
        code: 'CYCLE_DETECTED',
        message: `检测到循环依赖: ${cycleResult.cyclePath?.join(' -> ')}`,
        severity: 'error'
      });
    }

    const orphanNodes = this.findOrphanNodes(nodes, connections);
    for (const nodeId of orphanNodes) {
      const node = nodes.find(n => n.id === nodeId);
      if (node && node.type !== 'start') {
        allWarnings.push({
          code: 'ORPHAN_NODE',
          message: `节点 "${node.name}" 没有入站连接`,
          nodeId
        });
      }
    }

    const deadEndNodes = this.findDeadEndNodes(nodes, connections);
    for (const nodeId of deadEndNodes) {
      const node = nodes.find(n => n.id === nodeId);
      if (node && node.type !== 'end') {
        allWarnings.push({
          code: 'DEAD_END_NODE',
          message: `节点 "${node.name}" 没有出站连接`,
          nodeId
        });
      }
    }

    return {
      valid: allErrors.length === 0,
      errors: allErrors,
      warnings: allWarnings
    };
  }

  private findDuplicateConnections(connections: FlowConnection[]): { source: string; target: string }[] {
    const seen = new Set<string>();
    const duplicates: { source: string; target: string }[] = [];

    for (const conn of connections) {
      const key = `${conn.sourceNodeId}:${conn.targetNodeId}`;
      if (seen.has(key)) {
        duplicates.push({ source: conn.sourceNodeId, target: conn.targetNodeId });
      }
      seen.add(key);
    }

    return duplicates;
  }

  private detectCycles(
    connections: FlowConnection[],
    nodes: FlowNode[]
  ): { hasCycle: boolean; cyclePath?: string[] } {
    const adjacencyList: Map<string, string[]> = new Map();
    for (const node of nodes) {
      adjacencyList.set(node.id, []);
    }
    for (const conn of connections) {
      adjacencyList.get(conn.sourceNodeId)?.push(conn.targetNodeId);
    }

    const visited = new Set<string>();
    const recursionStack = new Set<string>();
    const path: string[] = [];

    const dfs = (nodeId: string): { hasCycle: boolean; cyclePath?: string[] } => {
      visited.add(nodeId);
      recursionStack.add(nodeId);
      path.push(nodeId);

      const neighbors = adjacencyList.get(nodeId) || [];
      for (const neighbor of neighbors) {
        if (!visited.has(neighbor)) {
          const result = dfs(neighbor);
          if (result.hasCycle) {
            return result;
          }
        } else if (recursionStack.has(neighbor)) {
          const cycleStartIndex = path.indexOf(neighbor);
          return {
            hasCycle: true,
            cyclePath: path.slice(cycleStartIndex).concat(neighbor)
          };
        }
      }

      recursionStack.delete(nodeId);
      path.pop();
      return { hasCycle: false };
    };

    for (const node of nodes) {
      if (!visited.has(node.id)) {
        const result = dfs(node.id);
        if (result.hasCycle) {
          return result;
        }
      }
    }

    return { hasCycle: false };
  }

  private findOrphanNodes(nodes: FlowNode[], connections: FlowConnection[]): string[] {
    const targetNodeIds = new Set(connections.map(c => c.targetNodeId));
    return nodes.filter(n => !targetNodeIds.has(n.id)).map(n => n.id);
  }

  private findDeadEndNodes(nodes: FlowNode[], connections: FlowConnection[]): string[] {
    const sourceNodeIds = new Set(connections.map(c => c.sourceNodeId));
    return nodes.filter(n => !sourceNodeIds.has(n.id)).map(n => n.id);
  }

  getConnectionRules(): Record<string, ConnectionRule> {
    return { ...this.connectionRules };
  }
}
