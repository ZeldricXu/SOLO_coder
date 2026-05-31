import { getPrismaClient, withTransaction, tenantFilter } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { NotFoundError, ProcessValidationError, ConflictError } from '../../common/errors';
import { WorkflowProcessInput, PaginationParams, PaginatedResult, ProcessingContext, EdgeCondition } from '../../common/types';
import { verifyTenantAccess } from '../tenant';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();

const VALID_NODE_TYPES = ['start', 'end', 'task', 'decision', 'parallel', 'approval', 'notification'] as const;
const VALID_EDGE_RULES: Record<string, string[]> = {
  start: ['task', 'decision', 'parallel', 'approval', 'notification', 'end'],
  task: ['task', 'decision', 'parallel', 'approval', 'notification', 'end'],
  decision: ['task', 'decision', 'parallel', 'approval', 'notification', 'end'],
  parallel: ['task', 'decision', 'parallel', 'approval', 'notification', 'end'],
  approval: ['task', 'decision', 'parallel', 'approval', 'notification', 'end'],
  notification: ['task', 'decision', 'parallel', 'approval', 'notification', 'end'],
  end: []
};

export const createProcess = async (
  tenantId: string,
  data: WorkflowProcessInput,
  traceId?: string
) => {
  verifyTenantAccess(tenantId, tenantId, traceId);

  return withTransaction(async (tx) => {
    const existing = await tx.workflowProcess.findFirst({
      where: { tenantId, name: data.name, version: data.version }
    });

    if (existing) {
      throw new ConflictError('Process with this name and version already exists', { name: data.name, version: data.version }, traceId);
    }

    const { nodes, edges, ...processData } = data;

    const validation = validateProcessGraph(nodes, edges);
    if (!validation.valid) {
      throw new ProcessValidationError('Invalid process definition', { errors: validation.errors }, traceId);
    }

    const process = await tx.workflowProcess.create({
      data: {
        ...processData,
        tenantId
      }
    });

    for (const node of nodes) {
      await tx.processNode.create({
        data: { ...node, processId: process.id }
      });
    }

    for (const edge of edges) {
      await tx.processEdge.create({
        data: { ...edge, processId: process.id }
      });
    }

    await cache.del(generateCacheKey('processes', tenantId));
    eventBus.publish(EventTypes.PROCESS_STARTED, { processId: process.id, action: 'created' }, { traceId, tenantId });

    return getProcessById(tenantId, process.id, traceId);
  });
};

export const getProcessById = async (
  tenantId: string,
  processId: string,
  traceId?: string
) => {
  const cacheKey = generateCacheKey('process', processId);
  const cached = await cache.get(cacheKey);
  if (cached) return cached;

  const process = await prisma.workflowProcess.findUnique({
    where: { id: processId },
    include: {
      nodes: { orderBy: { positionY: 'asc' } },
      edges: true,
      instances: { take: 10, orderBy: { createdAt: 'desc' } }
    }
  });

  if (!process) {
    throw new NotFoundError('Process not found', { processId }, traceId);
  }

  verifyTenantAccess(process.tenantId, tenantId, traceId);

  await cache.set(cacheKey, process, TTL.MEDIUM);
  return process;
};

export const listProcesses = async (
  tenantId: string,
  params: PaginationParams & { status?: string }
): Promise<PaginatedResult<unknown>> => {
  const cacheKey = generateCacheKey('processes', tenantId, String(params.page), String(params.pageSize), params.status || 'all');
  const cached = await cache.get(cacheKey);
  if (cached) return cached as PaginatedResult<unknown>;

  const where: Record<string, unknown> = { tenantId };
  if (params.status) where.status = params.status;

  const [total, items] = await Promise.all([
    prisma.workflowProcess.count({ where }),
    prisma.workflowProcess.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
      include: { _count: { select: { nodes: true, edges: true, instances: true } } }
    })
  ]);

  const result: PaginatedResult<unknown> = {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };

  await cache.set(cacheKey, result, TTL.SHORT);
  return result;
};

export const updateProcess = async (
  tenantId: string,
  processId: string,
  data: Partial<WorkflowProcessInput>,
  traceId?: string
) => {
  const process = await prisma.workflowProcess.findUnique({ where: { id: processId } });
  if (!process) {
    throw new NotFoundError('Process not found', { processId }, traceId);
  }
  verifyTenantAccess(process.tenantId, tenantId, traceId);

  return withTransaction(async (tx) => {
    const { nodes, edges, ...updateData } = data;

    const updated = await tx.workflowProcess.update({
      where: { id: processId },
      data: updateData
    });

    if (nodes || edges) {
      const currentNodes = nodes || await tx.processNode.findMany({ where: { processId } });
      const currentEdges = edges || await tx.processEdge.findMany({ where: { processId } });

      const validation = validateProcessGraph(currentNodes, currentEdges);
      if (!validation.valid) {
        throw new ProcessValidationError('Invalid process definition', { errors: validation.errors }, traceId);
      }

      if (nodes) {
        await tx.processNode.deleteMany({ where: { processId } });
        for (const node of nodes) {
          await tx.processNode.create({ data: { ...node, processId } });
        }
      }

      if (edges) {
        await tx.processEdge.deleteMany({ where: { processId } });
        for (const edge of edges) {
          await tx.processEdge.create({ data: { ...edge, processId } });
        }
      }
    }

    await cache.del(generateCacheKey('process', processId));
    await cache.del(generateCacheKey('processes', tenantId));

    return getProcessById(tenantId, processId, traceId);
  });
};

export const deleteProcess = async (
  tenantId: string,
  processId: string,
  traceId?: string
) => {
  const process = await prisma.workflowProcess.findUnique({ where: { id: processId } });
  if (!process) {
    throw new NotFoundError('Process not found', { processId }, traceId);
  }
  verifyTenantAccess(process.tenantId, tenantId, traceId);

  await withTransaction(async (tx) => {
    await tx.processEdge.deleteMany({ where: { processId } });
    await tx.processNode.deleteMany({ where: { processId } });
    await tx.processInstance.deleteMany({ where: { processId } });
    await tx.workflowProcess.delete({ where: { id: processId } });
  });

  await cache.del(generateCacheKey('process', processId));
  await cache.del(generateCacheKey('processes', tenantId));
};

export const validateProcessGraph = (
  nodes: Array<{ id?: string; type: string; name: string }>,
  edges: Array<{ sourceNodeId: string; targetNodeId: string }>
): { valid: boolean; errors: Array<{ type: string; message: string; nodeId?: string }> } => {
  const errors: Array<{ type: string; message: string; nodeId?: string }> = [];
  const nodeIds = new Set(nodes.map(n => n.id || ''));

  if (nodes.length === 0) {
    errors.push({ type: 'structure', message: 'Process must have at least one node' });
    return { valid: false, errors };
  }

  const startNodes = nodes.filter(n => n.type === 'start');
  const endNodes = nodes.filter(n => n.type === 'end');

  if (startNodes.length === 0) {
    errors.push({ type: 'structure', message: 'Process must have at least one start node' });
  }
  if (startNodes.length > 1) {
    errors.push({ type: 'structure', message: 'Process must have exactly one start node' });
  }
  if (endNodes.length === 0) {
    errors.push({ type: 'structure', message: 'Process must have at least one end node' });
  }

  for (const node of nodes) {
    if (!VALID_NODE_TYPES.includes(node.type as typeof VALID_NODE_TYPES[number])) {
      errors.push({
        type: 'node_type',
        message: `Invalid node type: ${node.type}`,
        nodeId: node.id
      });
    }
  }

  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  for (const edge of edges) {
    const sourceNode = nodeMap.get(edge.sourceNodeId);
    const targetNode = nodeMap.get(edge.targetNodeId);

    if (!sourceNode) {
      errors.push({
        type: 'edge',
        message: `Source node ${edge.sourceNodeId} does not exist`,
        nodeId: edge.sourceNodeId
      });
      continue;
    }

    if (!targetNode) {
      errors.push({
        type: 'edge',
        message: `Target node ${edge.targetNodeId} does not exist`,
        nodeId: edge.targetNodeId
      });
      continue;
    }

    const validTargets = VALID_EDGE_RULES[sourceNode.type] || [];
    if (!validTargets.includes(targetNode.type)) {
      errors.push({
        type: 'edge_rule',
        message: `Cannot connect ${sourceNode.type} to ${targetNode.type}`,
        nodeId: edge.sourceNodeId
      });
    }

    if (sourceNode.type === 'end') {
      errors.push({
        type: 'edge_rule',
        message: 'End node cannot have outgoing edges',
        nodeId: edge.sourceNodeId
      });
    }
  }

  const nodesWithIncoming = new Set(edges.map((e: { targetNodeId: string }) => e.targetNodeId));
  const nodesWithOutgoing = new Set(edges.map((e: { sourceNodeId: string }) => e.sourceNodeId));

  for (const node of nodes) {
    if (node.type === 'start' && !nodesWithOutgoing.has(node.id || '')) {
      errors.push({
        type: 'connectivity',
        message: `Start node "${node.name}" must have at least one outgoing edge`,
        nodeId: node.id
      });
    }
    if (node.type === 'end' && !nodesWithIncoming.has(node.id || '')) {
      errors.push({
        type: 'connectivity',
        message: `End node "${node.name}" must have at least one incoming edge`,
        nodeId: node.id
      });
    }
    if (node.type !== 'start' && node.type !== 'end') {
      if (!nodesWithIncoming.has(node.id || '')) {
        errors.push({
          type: 'connectivity',
          message: `Node "${node.name}" must have at least one incoming edge`,
          nodeId: node.id
        });
      }
      if (!nodesWithOutgoing.has(node.id || '')) {
        errors.push({
          type: 'connectivity',
          message: `Node "${node.name}" must have at least one outgoing edge`,
          nodeId: node.id
        });
      }
    }
  }

  return { valid: errors.length === 0, errors };
};

export const startProcessInstance = async (
  tenantId: string,
  processId: string,
  entityId: string,
  variables: Record<string, unknown> = {},
  context?: Partial<ProcessingContext>
) => {
  const process = await getProcessById(tenantId, processId, context?.traceId);

  const startNode = process.nodes.find((n: { type: string }) => n.type === 'start');
  if (!startNode) {
    throw new ProcessValidationError('Process has no start node', { processId }, context?.traceId);
  }

  const instance = await prisma.processInstance.create({
    data: {
      processId,
      entityId,
      status: 'running',
      currentNodeId: startNode.id,
      phase: 'initializing',
      progress: 0,
      variables
    }
  });

  eventBus.publish(EventTypes.PROCESS_STARTED, {
    instanceId: instance.id,
    processId,
    entityId
  }, context);

  return advanceProcessInstance(tenantId, instance.id, variables, context);
};

export const advanceProcessInstance = async (
  tenantId: string,
  instanceId: string,
  variables: Record<string, unknown> = {},
  context?: Partial<ProcessingContext>
) => {
  return withTransaction(async (tx) => {
    const instance = await tx.processInstance.findUnique({
      where: { id: instanceId },
      include: { process: { include: { nodes: true, edges: true } } }
    });

    if (!instance) {
      throw new NotFoundError('Process instance not found', { instanceId }, context?.traceId);
    }
    verifyTenantAccess(instance.process.tenantId, tenantId, context?.traceId);

    if (instance.status !== 'running') {
      return instance;
    }

    const currentNode = instance.process.nodes.find((n: { id: string }) => n.id === instance.currentNodeId);
    if (!currentNode) {
      throw new ProcessValidationError('Current node not found', { instanceId, currentNodeId: instance.currentNodeId }, context?.traceId);
    }

    const outgoingEdges = instance.process.edges.filter(
      (e: { sourceNodeId: string }) => e.sourceNodeId === currentNode.id
    );

    let nextNodeId: string | null = null;

    if (currentNode.type === 'end') {
      await tx.processInstance.update({
        where: { id: instanceId },
        data: {
          status: 'completed',
          currentNodeId: currentNode.id,
          phase: 'completed',
          progress: 1,
          completedAt: new Date()
        }
      });

      eventBus.publish(EventTypes.PROCESS_COMPLETED, {
        instanceId,
        processId: instance.processId
      }, context);

      return { ...instance, status: 'completed', progress: 1 };
    }

    if (currentNode.type === 'decision') {
      for (const edge of outgoingEdges) {
        if (edge.condition && evaluateEdgeCondition(edge.condition as unknown as EdgeCondition, variables)) {
          nextNodeId = edge.targetNodeId;
          break;
        }
      }
      if (!nextNodeId && outgoingEdges.length > 0) {
        nextNodeId = outgoingEdges[0].targetNodeId;
      }
    } else {
      if (outgoingEdges.length > 0) {
        nextNodeId = outgoingEdges[0].targetNodeId;
      }
    }

    const totalNodes = instance.process.nodes.length;
    const currentIndex = instance.process.nodes.findIndex((n: { id: string }) => n.id === nextNodeId);
    const progress = totalNodes > 0 ? Math.min(1, (currentIndex + 1) / totalNodes) : 0;

    const updated = await tx.processInstance.update({
      where: { id: instanceId },
      data: {
        currentNodeId: nextNodeId,
        phase: `processing_${nextNodeId}`,
        progress,
        variables: { ...instance.variables, ...variables } as unknown as object
      }
    });

    return updated;
  });
};

export const evaluateEdgeCondition = (
  condition: EdgeCondition | Record<string, unknown>,
  variables: Record<string, unknown>
): boolean => {
  const cond = condition as EdgeCondition;
  if (!cond.field || !cond.operator) return true;

  const value = variables[cond.field];
  if (value === undefined) return false;

  switch (cond.operator) {
    case 'eq':
      return value === cond.value;
    case 'ne':
      return value !== cond.value;
    case 'gt':
      return Number(value) > Number(cond.value);
    case 'lt':
      return Number(value) < Number(cond.value);
    case 'gte':
      return Number(value) >= Number(cond.value);
    case 'lte':
      return Number(value) <= Number(cond.value);
    case 'contains':
      return String(value).includes(String(cond.value));
    case 'in':
      return Array.isArray(cond.value) && cond.value.includes(value);
    default:
      return true;
  }
};

export const getProcessInstance = async (
  tenantId: string,
  instanceId: string,
  traceId?: string
) => {
  const instance = await prisma.processInstance.findUnique({
    where: { id: instanceId },
    include: {
      process: { include: { nodes: true, edges: true } }
    }
  });

  if (!instance) {
    throw new NotFoundError('Process instance not found', { instanceId }, traceId);
  }
  verifyTenantAccess(instance.process.tenantId, tenantId, traceId);
  return instance;
};

export const listProcessInstances = async (
  tenantId: string,
  processId: string,
  params: PaginationParams
): Promise<PaginatedResult<unknown>> => {
  const where: Record<string, unknown> = { processId };

  const [total, items] = await Promise.all([
    prisma.processInstance.count({ where }),
    prisma.processInstance.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' }
    })
  ]);

  return {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };
};

export const getProcessDiagram = async (
  tenantId: string,
  processId: string,
  traceId?: string
) => {
  const process = await getProcessById(tenantId, processId, traceId);

  return {
    processId: process.id,
    name: process.name,
    version: process.version,
    nodes: process.nodes.map((n: { id: string; type: string; name: string; positionX: number; positionY: number; config: unknown }) => ({
      id: n.id,
      type: n.type,
      name: n.name,
      position: { x: n.positionX, y: n.positionY },
      config: n.config
    })),
    edges: process.edges.map((e: { id: string; sourceNodeId: string; targetNodeId: string; condition: unknown }) => ({
      id: e.id,
      source: e.sourceNodeId,
      target: e.targetNodeId,
      condition: e.condition
    })),
    validation: validateProcessGraph(process.nodes, process.edges)
  };
};

export const publishProcess = async (
  tenantId: string,
  processId: string,
  traceId?: string
) => {
  const process = await getProcessById(tenantId, processId, traceId);

  const validation = validateProcessGraph(process.nodes, process.edges);
  if (!validation.valid) {
    throw new ProcessValidationError('Cannot publish invalid process', { errors: validation.errors }, traceId);
  }

  const updated = await prisma.workflowProcess.update({
    where: { id: processId },
    data: { status: 'published' }
  });

  await cache.del(generateCacheKey('process', processId));
  await cache.del(generateCacheKey('processes', tenantId));

  return updated;
};
