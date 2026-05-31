import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/commandAudit/service';
import { CommandSchema, AuditLogSchema, ComplianceReportSchema } from '../modules/commandAudit/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/commands', asyncHandler(async (req: Request, res: Response) => {
  const data = CommandSchema.parse(req.body);
  const result = await service.persistCommand(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/commands', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const aggregateId = req.query.aggregateId as string | undefined;
  const commandType = req.query.commandType as string | undefined;
  const result = await service.listCommands(params, aggregateId, commandType);
  res.json({ code: 200, data: result });
}));

router.get('/commands/:commandId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getCommand(req.params.commandId);
  res.json({ code: 200, data: result });
}));

router.get('/aggregates/:aggregateId/commands', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getCommandsByAggregate(req.params.aggregateId);
  res.json({ code: 200, data: result });
}));

router.post('/audit-logs', asyncHandler(async (req: Request, res: Response) => {
  const data = AuditLogSchema.parse(req.body);
  const result = await service.createAuditLog(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/audit-logs', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const actorId = req.query.actorId as string | undefined;
  const action = req.query.action as string | undefined;
  const resourceId = req.query.resourceId as string | undefined;
  const result = await service.listAuditLogs(params, actorId, action, resourceId);
  res.json({ code: 200, data: result });
}));

router.get('/audit-logs/:logId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getAuditLog(req.params.logId);
  res.json({ code: 200, data: result });
}));

router.get('/commands/:commandId/audit-logs', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getAuditLogsByCommand(req.params.commandId);
  res.json({ code: 200, data: result });
}));

router.post('/compliance-report', asyncHandler(async (req: Request, res: Response) => {
  const data = ComplianceReportSchema.parse(req.body);
  const result = await service.generateComplianceReport(data);
  res.json({ code: 200, data: result });
}));

export default router;
