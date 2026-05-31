import { z } from 'zod';

export const CommandSchema = z.object({
  commandType: z.string().min(1),
  aggregateId: z.string().min(1),
  payload: z.record(z.unknown()),
  metadata: z.record(z.unknown()).optional(),
  actorId: z.string().optional(),
});

export const AuditLogSchema = z.object({
  action: z.string().min(1),
  actorId: z.string().min(1),
  resourceId: z.string().optional(),
  details: z.record(z.unknown()).optional(),
  commandId: z.string().optional(),
});

export const ComplianceReportSchema = z.object({
  startDate: z.string().datetime(),
  endDate: z.string().datetime(),
  actorIds: z.array(z.string()).optional(),
  actions: z.array(z.string()).optional(),
  resourceIds: z.array(z.string()).optional(),
  format: z.enum(['json', 'csv', 'pdf']).default('json'),
});

export type CreateCommandRequest = z.infer<typeof CommandSchema>;
export type CreateAuditLogRequest = z.infer<typeof AuditLogSchema>;
export type ComplianceReportRequest = z.infer<typeof ComplianceReportSchema>;

export interface Command {
  commandId: string;
  commandType: string;
  aggregateId: string;
  payload: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  actorId?: string;
  timestamp: Date;
}

export interface AuditLog {
  logId: string;
  action: string;
  actorId: string;
  resourceId?: string;
  details: Record<string, unknown>;
  commandId?: string;
  timestamp: Date;
}

export interface ComplianceReport {
  reportId: string;
  startDate: Date;
  endDate: Date;
  totalCommands: number;
  totalAuditLogs: number;
  summary: Record<string, number>;
  entries: Array<{
    timestamp: Date;
    action: string;
    actorId: string;
    resourceId?: string;
    commandId?: string;
  }>;
  generatedAt: Date;
}
