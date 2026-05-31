import { Request, Response } from 'express';
import { inject, injectable } from 'tsyringe';
import { CREATE_TICKET_USE_CASE, ICreateTicketUseCase } from '../../../application/ticket/commands/CreateTicketCommand';
import { ASSIGN_TICKET_USE_CASE, IAssignTicketUseCase } from '../../../application/ticket/commands/AssignTicketCommand';
import { GET_TICKET_USE_CASE, IGetTicketUseCase } from '../../../application/ticket/queries/GetTicketQuery';
import { LIST_TICKETS_USE_CASE, IListTicketsUseCase } from '../../../application/ticket/queries/ListTicketsQuery';
import { FIND_MATCHING_AGENTS_USE_CASE, IFindMatchingAgentsUseCase } from '../../../application/ticket/queries/FindMatchingAgentsQuery';
import { createSuccessResponse, createCreatedResponse, createErrorResponse } from '../../../application/shared/dto/CommonDTO';
import { randomUUID } from 'crypto';

@injectable()
export class TicketController {
  constructor(
    @inject(CREATE_TICKET_USE_CASE) private readonly createTicketUseCase: ICreateTicketUseCase,
    @inject(ASSIGN_TICKET_USE_CASE) private readonly assignTicketUseCase: IAssignTicketUseCase,
    @inject(GET_TICKET_USE_CASE) private readonly getTicketUseCase: IGetTicketUseCase,
    @inject(LIST_TICKETS_USE_CASE) private readonly listTicketsUseCase: IListTicketsUseCase,
    @inject(FIND_MATCHING_AGENTS_USE_CASE) private readonly findMatchingAgentsUseCase: IFindMatchingAgentsUseCase
  ) {}

  createTicket = async (req: Request, res: Response): Promise<void> => {
    try {
      const traceId = req.headers['x-trace-id'] as string || randomUUID();
      const tenantId = req.headers['x-tenant-id'] as string;

      if (!tenantId) {
        res.status(400).json(createErrorResponse(400, 'Tenant ID is required', 'BAD_REQUEST', traceId));
        return;
      }

      const result = await this.createTicketUseCase.execute({
        tenantId,
        traceId,
        data: req.body
      });

      res.status(201).json(createCreatedResponse(result, traceId));
    } catch (error) {
      this.handleError(res, error);
    }
  };

  getTicket = async (req: Request, res: Response): Promise<void> => {
    try {
      const traceId = req.headers['x-trace-id'] as string || randomUUID();
      const tenantId = req.headers['x-tenant-id'] as string;
      const { ticketId } = req.params;

      if (!tenantId) {
        res.status(400).json(createErrorResponse(400, 'Tenant ID is required', 'BAD_REQUEST', traceId));
        return;
      }

      const result = await this.getTicketUseCase.execute({
        tenantId,
        traceId,
        ticketId
      });

      res.json(createSuccessResponse(result, traceId));
    } catch (error) {
      this.handleError(res, error);
    }
  };

  listTickets = async (req: Request, res: Response): Promise<void> => {
    try {
      const traceId = req.headers['x-trace-id'] as string || randomUUID();
      const tenantId = req.headers['x-tenant-id'] as string;

      if (!tenantId) {
        res.status(400).json(createErrorResponse(400, 'Tenant ID is required', 'BAD_REQUEST', traceId));
        return;
      }

      const page = parseInt(req.query.page as string) || 1;
      const pageSize = parseInt(req.query.pageSize as string) || 20;
      const { status, priority, agentId, type, createdAtFrom, createdAtTo } = req.query;

      const result = await this.listTicketsUseCase.execute({
        tenantId,
        traceId,
        pagination: { page, pageSize },
        filter: {
          status: status as any,
          priority: priority as any,
          agentId: agentId as string,
          type: type as string,
          createdAtFrom: createdAtFrom ? new Date(createdAtFrom as string) : undefined,
          createdAtTo: createdAtTo ? new Date(createdAtTo as string) : undefined
        }
      });

      res.json(createSuccessResponse(result, traceId));
    } catch (error) {
      this.handleError(res, error);
    }
  };

  assignTicket = async (req: Request, res: Response): Promise<void> => {
    try {
      const traceId = req.headers['x-trace-id'] as string || randomUUID();
      const tenantId = req.headers['x-tenant-id'] as string;
      const { ticketId } = req.params;
      const { strategy, threshold } = req.query;

      if (!tenantId) {
        res.status(400).json(createErrorResponse(400, 'Tenant ID is required', 'BAD_REQUEST', traceId));
        return;
      }

      const result = await this.assignTicketUseCase.execute({
        tenantId,
        traceId,
        ticketId,
        strategy: strategy as any,
        threshold: threshold ? parseFloat(threshold as string) : undefined,
        data: req.body
      });

      res.json(createSuccessResponse(result, traceId));
    } catch (error) {
      this.handleError(res, error);
    }
  };

  findMatchingAgents = async (req: Request, res: Response): Promise<void> => {
    try {
      const traceId = req.headers['x-trace-id'] as string || randomUUID();
      const tenantId = req.headers['x-tenant-id'] as string;
      const { ticketId } = req.params;
      const { strategy, threshold, limit } = req.query;

      if (!tenantId) {
        res.status(400).json(createErrorResponse(400, 'Tenant ID is required', 'BAD_REQUEST', traceId));
        return;
      }

      const result = await this.findMatchingAgentsUseCase.execute({
        tenantId,
        traceId,
        ticketId,
        strategy: strategy as any,
        threshold: threshold ? parseFloat(threshold as string) : undefined,
        limit: limit ? parseInt(limit as string) : undefined
      });

      res.json(createSuccessResponse(result, traceId));
    } catch (error) {
      this.handleError(res, error);
    }
  };

  private handleError(res: Response, error: unknown): void {
    const traceId = res.getHeader('x-trace-id') as string || randomUUID();
    const err = error as Error;

    if (err.name === 'NotFoundError') {
      res.status(404).json(createErrorResponse(404, err.message, 'NOT_FOUND', traceId));
      return;
    }

    if (err.name === 'ValidationError' || err.name === 'BusinessRuleViolationError') {
      res.status(422).json(createErrorResponse(422, err.message, 'VALIDATION_ERROR', traceId));
      return;
    }

    if (err.name === 'TenantIsolationError') {
      res.status(403).json(createErrorResponse(403, err.message, 'FORBIDDEN', traceId));
      return;
    }

    if (err.name === 'NoMatchingAgentError') {
      res.status(422).json(createErrorResponse(422, err.message, 'NO_MATCHING_AGENT', traceId));
      return;
    }

    console.error('Controller error:', err);
    res.status(500).json(createErrorResponse(500, 'Internal server error', 'INTERNAL_ERROR', traceId));
  }
}
