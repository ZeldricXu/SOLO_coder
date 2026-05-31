import express, { Express } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { inject, injectable } from 'tsyringe';
import { TicketRoutes } from './routes/ticketRoutes';
import { tenantMiddleware } from './middleware/tenantMiddleware';
import { errorMiddleware } from './middleware/errorMiddleware';
import { getConfig } from '../../infrastructure/config/AppConfig';

@injectable()
export class App {
  public readonly app: Express;
  private readonly config = getConfig();

  constructor(@inject(TicketRoutes) private readonly ticketRoutes: TicketRoutes) {
    this.app = express();
    this.setupMiddleware();
    this.setupRoutes();
    this.setupErrorHandling();
  }

  private setupMiddleware(): void {
    this.app.use(helmet());
    this.app.use(cors());
    this.app.use(express.json({ limit: '10mb' }));
    this.app.use(express.urlencoded({ extended: true }));
    this.app.use(tenantMiddleware);
  }

  private setupRoutes(): void {
    this.app.get('/health', (req, res) => {
      res.json({
        status: 'ok',
        timestamp: new Date().toISOString(),
        uptime: process.uptime(),
        environment: this.config.env
      });
    });

    this.app.use('/api/v1/tickets', this.ticketRoutes.router);

    this.app.get('/api/v1', (req, res) => {
      res.json({
        name: 'Ticket Routing System API',
        version: '1.0.0',
        endpoints: {
          tickets: '/api/v1/tickets',
          health: '/health'
        }
      });
    });
  }

  private setupErrorHandling(): void {
    this.app.use(errorMiddleware);
  }

  listen(port: number = this.config.port): void {
    this.app.listen(port, () => {
      console.log(`🚀 Server running on port ${port}`);
      console.log(`📊 Environment: ${this.config.env}`);
      console.log(`📚 API Documentation: /api/v1`);
    });
  }
}
