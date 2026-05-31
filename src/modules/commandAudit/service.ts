import { PrismaClient, Command as DbCommand, AuditLog as DbAuditLog } from '@prisma/client';
import { generateCommandId, generateAuditLogId } from '../../utils/idGenerator';
import { NotFoundError } from '../../utils/errors';
import type { CreateCommandRequest, CreateAuditLogRequest, Command, AuditLog, ComplianceReport, ComplianceReportRequest } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';

const prisma = new PrismaClient();

const toCommand = (db: DbCommand): Command => ({
  commandId: db.commandId,
  commandType: db.commandType,
  aggregateId: db.aggregateId,
  payload: db.payload as Record<string, unknown>,
  metadata: db.metadata as Record<string, unknown> | undefined,
  actorId: db.actorId ?? undefined,
  timestamp: db.timestamp,
});

const toAuditLog = (db: DbAuditLog): AuditLog => ({
  logId: db.logId,
  action: db.action,
  actorId: db.actorId,
  resourceId: db.resourceId ?? undefined,
  details: db.details as Record<string, unknown>,
  commandId: db.commandId ?? undefined,
  timestamp: db.timestamp,
});

export const persistCommand = async (data: CreateCommandRequest): Promise<Command> => {
  const command = await prisma.command.create({
    data: {
      commandId: generateCommandId(),
      commandType: data.commandType,
      aggregateId: data.aggregateId,
      payload: data.payload,
      metadata: data.metadata,
      actorId: data.actorId,
    },
  });
  logger.info({ commandId: command.commandId, commandType: data.commandType }, 'Command persisted');
  return toCommand(command);
};

export const getCommand = async (commandId: string): Promise<Command> => {
  const command = await prisma.command.findUnique({ where: { commandId } });
  if (!command) throw new NotFoundError(`Command ${commandId} not found`);
  return toCommand(command);
};

export const listCommands = async (params: PaginationParams, aggregateId?: string, commandType?: string): Promise<PaginatedResult<Command>> => {
  const where: Record<string, unknown> = {};
  if (aggregateId) where.aggregateId = aggregateId;
  if (commandType) where.commandType = commandType;

  const [items, total] = await Promise.all([
    prisma.command.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { timestamp: 'desc' },
    }),
    prisma.command.count({ where }),
  ]);
  return {
    items: items.map(toCommand),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const getCommandsByAggregate = async (aggregateId: string): Promise<Command[]> => {
  const commands = await prisma.command.findMany({
    where: { aggregateId },
    orderBy: { timestamp: 'asc' },
  });
  return commands.map(toCommand);
};

export const createAuditLog = async (data: CreateAuditLogRequest): Promise<AuditLog> => {
  const auditLog = await prisma.auditLog.create({
    data: {
      logId: generateAuditLogId(),
      action: data.action,
      actorId: data.actorId,
      resourceId: data.resourceId,
      details: data.details || {},
      commandId: data.commandId,
    },
  });
  logger.info({ logId: auditLog.logId, action: data.action }, 'Audit log created');
  return toAuditLog(auditLog);
};

export const getAuditLog = async (logId: string): Promise<AuditLog> => {
  const log = await prisma.auditLog.findUnique({ where: { logId } });
  if (!log) throw new NotFoundError(`Audit log ${logId} not found`);
  return toAuditLog(log);
};

export const listAuditLogs = async (params: PaginationParams, actorId?: string, action?: string, resourceId?: string): Promise<PaginatedResult<AuditLog>> => {
  const where: Record<string, unknown> = {};
  if (actorId) where.actorId = actorId;
  if (action) where.action = action;
  if (resourceId) where.resourceId = resourceId;

  const [items, total] = await Promise.all([
    prisma.auditLog.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { timestamp: 'desc' },
    }),
    prisma.auditLog.count({ where }),
  ]);
  return {
    items: items.map(toAuditLog),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const getAuditLogsByCommand = async (commandId: string): Promise<AuditLog[]> => {
  const logs = await prisma.auditLog.findMany({
    where: { commandId },
    orderBy: { timestamp: 'asc' },
  });
  return logs.map(toAuditLog);
};

export const generateComplianceReport = async (params: ComplianceReportRequest): Promise<ComplianceReport> => {
  const startDate = new Date(params.startDate);
  const endDate = new Date(params.endDate);

  const where: Record<string, unknown> = {
    timestamp: {
      gte: startDate,
      lte: endDate,
    },
  };
  if (params.actorIds && params.actorIds.length > 0) {
    where.actorId = { in: params.actorIds };
  }
  if (params.actions && params.actions.length > 0) {
    where.action = { in: params.actions };
  }
  if (params.resourceIds && params.resourceIds.length > 0) {
    where.resourceId = { in: params.resourceIds };
  }

  const [totalCommands, totalLogs, auditLogs] = await Promise.all([
    prisma.command.count({
      where: {
        timestamp: { gte: startDate, lte: endDate },
      },
    }),
    prisma.auditLog.count({ where }),
    prisma.auditLog.findMany({
      where,
      orderBy: { timestamp: 'asc' },
      take: 10000,
    }),
  ]);

  const summary: Record<string, number> = {};
  for (const log of auditLogs) {
    summary[log.action] = (summary[log.action] || 0) + 1;
  }

  const report: ComplianceReport = {
    reportId: generateAuditLogId(),
    startDate,
    endDate,
    totalCommands,
    totalAuditLogs: totalLogs,
    summary,
    entries: auditLogs.map(log => ({
      timestamp: log.timestamp,
      action: log.action,
      actorId: log.actorId,
      resourceId: log.resourceId ?? undefined,
      commandId: log.commandId ?? undefined,
    })),
    generatedAt: new Date(),
  };

  logger.info({ reportId: report.reportId, totalEntries: report.entries.length }, 'Compliance report generated');
  return report;
};

export default {
  persistCommand,
  getCommand,
  listCommands,
  getCommandsByAggregate,
  createAuditLog,
  getAuditLog,
  listAuditLogs,
  getAuditLogsByCommand,
  generateComplianceReport,
};
