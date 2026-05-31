import express, { Application, Request, Response, NextFunction } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import morgan from 'morgan';
import { config } from './config';
import { apiRoutes } from './routes';
import { ResponseUtils } from './utils/response';
import { AppError, ValidationError } from './utils/errors';

const app: Application = express();

app.use(helmet());
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));

if (config.server.isDevelopment) {
  app.use(morgan('dev'));
} else {
  app.use(morgan('combined'));
}

app.get('/health', (req: Request, res: Response) => {
  ResponseUtils.success(res, {
    status: 'ok',
    timestamp: new Date().toISOString(),
    version: process.env.npm_package_version || '1.0.0',
    environment: config.server.nodeEnv,
  });
});

app.get('/api/v1/health', (req: Request, res: Response) => {
  ResponseUtils.success(res, {
    status: 'ok',
    service: 'DIDAuth Service',
    timestamp: new Date().toISOString(),
  });
});

app.use('/api/v1', apiRoutes);

app.use((req: Request, res: Response) => {
  ResponseUtils.notFound(res, 'Endpoint not found');
});

app.use((error: Error, req: Request, res: Response, next: NextFunction) => {
  console.error('Error:', error);

  if (error instanceof AppError) {
    return ResponseUtils.error(res, error);
  }

  if (error instanceof SyntaxError && 'body' in error) {
    return ResponseUtils.badRequest(res, 'Invalid JSON payload');
  }

  ResponseUtils.error(res, new AppError('Internal Server Error', 500, 'INTERNAL_ERROR'));
});

process.on('uncaughtException', (error: Error) => {
  console.error('Uncaught Exception:', error);
  process.exit(1);
});

process.on('unhandledRejection', (reason: any, promise: Promise<any>) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
  process.exit(1);
});

export default app;
