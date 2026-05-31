import { z } from 'zod';

export const paginationSchema = z.object({
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20)
});

export const tenantIdSchema = z.string().uuid();

export const idSchema = z.string().uuid();

export const createResourceSchema = z.object({
  type: z.string().min(1),
  config: z.record(z.unknown()).default({}),
  labels: z.record(z.string()).default({})
});

export const batchOperationSchema = z.object({
  operations: z.array(
    z.object({
      action: z.enum(['start', 'stop', 'pause', 'resume', 'delete']),
      id: z.string().uuid()
    })
  )
});

export const tenantSchema = z.object({
  name: z.string().min(2).max(100),
  email: z.string().email(),
  status: z.enum(['active', 'inactive', 'suspended']).default('active'),
  attributes: z.record(z.unknown()).default({})
});

export const usageRecordSchema = z.object({
  resourceType: z.string().min(1),
  quantity: z.number().min(0),
  unit: z.string().min(1),
  eventSource: z.string().min(1),
  eventId: z.string().min(1),
  attributes: z.record(z.unknown()).default({})
});

export const ticketSchema = z.object({
  title: z.string().min(1).max(200),
  description: z.string().optional(),
  type: z.string().min(1),
  priority: z.enum(['low', 'medium', 'high', 'urgent']).default('medium'),
  requiredSkills: z.array(
    z.object({
      skillId: z.string().uuid(),
      minLevel: z.number().min(0).max(10)
    })
  ).default([])
});

export const skillSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().optional(),
  category: z.string().min(1),
  level: z.number().int().min(1).max(5).default(1),
  parentId: z.string().uuid().optional()
});

export const agentSchema = z.object({
  name: z.string().min(1).max(100),
  email: z.string().email(),
  status: z.enum(['available', 'busy', 'offline', 'on_leave']).default('available'),
  maxLoad: z.number().min(0).max(1000).default(100),
  skillIds: z.array(z.string().uuid()).default([])
});

export const skillAssessmentSchema = z.object({
  agentId: z.string().uuid(),
  skillId: z.string().uuid(),
  level: z.number().min(0).max(10),
  certification: z.string().optional(),
  verified: z.boolean().default(false),
  expiresAt: z.coerce.date().optional()
});

export const processNodeSchema = z.object({
  type: z.enum(['start', 'end', 'task', 'decision', 'parallel', 'approval', 'notification']),
  name: z.string().min(1),
  positionX: z.number(),
  positionY: z.number(),
  config: z.record(z.unknown()).default({})
});

export const processEdgeSchema = z.object({
  sourceNodeId: z.string().uuid(),
  targetNodeId: z.string().uuid(),
  condition: z.record(z.unknown()).optional()
});

export const workflowProcessSchema = z.object({
  name: z.string().min(1),
  version: z.number().int().min(1).default(1),
  status: z.enum(['draft', 'published', 'archived']).default('draft'),
  nodes: z.array(processNodeSchema).default([]),
  edges: z.array(processEdgeSchema).default([])
});

export const approvalRuleSchema = z.object({
  name: z.string().min(1),
  type: z.enum(['sequential', 'parallel', 'conditional']),
  config: z.record(z.unknown()).default({})
});

export const documentSchema = z.object({
  name: z.string().min(1),
  contentType: z.string().min(1),
  content: z.string().min(1),
  createdBy: z.string().uuid().optional()
});

export const slaPolicySchema = z.object({
  name: z.string().min(1),
  ticketType: z.string().min(1),
  priority: z.enum(['low', 'medium', 'high', 'urgent']),
  responseTime: z.number().int().min(1),
  resolutionTime: z.number().int().min(1),
  escalationLevels: z.array(
    z.object({
      level: z.number().int().min(1),
      threshold: z.number().min(0).max(1),
      action: z.string().min(1),
      targetRole: z.string().optional()
    })
  ).default([])
});

export type CreateResourceInput = z.infer<typeof createResourceSchema>;
export type BatchOperationInput = z.infer<typeof batchOperationSchema>;
export type TenantInput = z.infer<typeof tenantSchema>;
export type UsageRecordInput = z.infer<typeof usageRecordSchema>;
export type TicketInput = z.infer<typeof ticketSchema>;
export type SkillInput = z.infer<typeof skillSchema>;
export type AgentInput = z.infer<typeof agentSchema>;
export type SkillAssessmentInput = z.infer<typeof skillAssessmentSchema>;
export type WorkflowProcessInput = z.infer<typeof workflowProcessSchema>;
export type ApprovalRuleInput = z.infer<typeof approvalRuleSchema>;
export type DocumentInput = z.infer<typeof documentSchema>;
export type SLAPolicyInput = z.infer<typeof slaPolicySchema>;
