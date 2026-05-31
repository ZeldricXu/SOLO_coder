import { Router } from 'express';
import { inject, injectable } from 'tsyringe';
import { TicketController } from '../controllers/TicketController';

@injectable()
export class TicketRoutes {
  public readonly router: Router;

  constructor(@inject(TicketController) private readonly ticketController: TicketController) {
    this.router = Router();
    this.setupRoutes();
  }

  private setupRoutes(): void {
    this.router.post('/', this.ticketController.createTicket);
    this.router.get('/:ticketId', this.ticketController.getTicket);
    this.router.get('/', this.ticketController.listTickets);
    this.router.post('/:ticketId/assign', this.ticketController.assignTicket);
    this.router.get('/:ticketId/matching-agents', this.ticketController.findMatchingAgents);
  }
}
