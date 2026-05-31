import express, { Express, Request, Response, NextFunction } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import compression from 'compression';

import { apiGateway } from './gateway';
import { monitoring } from './monitoring';
import { logger } from './logging';
import { configManager } from './config';
import { coreProcessor } from './core';

import healthRoutes from './routes/health';
import authRoutes from './routes/auth';
import configRoutes from './routes/config';
import taskRoutes from './routes/tasks';
import documentRoutes from './routes/documents';
import evaluationRoutes from './routes/evaluation';
import promptRoutes from './routes/prompts';
import featureRoutes from './routes/features';
import loggingRoutes from './routes/logging';

const app: Express = express();
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;

app.use(helmet());
app.use(cors());
app.use(compression());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

app.use((req: Request, res: Response, next: NextFunction) => {
  const start = Date.now();
  const method = req.method;
  const path = req.path;
  const ip = req.ip;

  logger.info('Request started', { method, path, ip });

  res.on('finish', () => {
    const duration = Date.now() - start;
    const statusCode = res.statusCode;
    monitoring.recordLatency('latency', duration, { endpoint: path });
    if (statusCode >= 400) {
      monitoring.incrementCounter('error_rate', 1, { endpoint: path });
    }
    logger.info('Request completed', { method, path, statusCode, duration_ms: duration });
  });

  next();
});

const authMiddleware = async (req: Request, res: Response, next: NextFunction) => {
  const publicPaths = ['/api/v1/auth/login', '/api/v1/auth/users', '/health', '/metrics', '/api/v1/logging/level'];
  if (publicPaths.some(p => req.path.startsWith(p)) || req.path === '/') {
    next();
    return;
  }

  try {
    const authHeader = req.headers.authorization;
    if (!authHeader) {
      res.status(401).json({ code: 401, error: 'Unauthorized' });
      return;
    }

    const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : authHeader;
    const authResult = apiGateway.validateToken(token);

    if (!authResult) {
      res.status(401).json({ code: 401, error: 'Unauthorized' });
      return;
    }

    (req as any).auth = authResult;
    next();
  } catch (error) {
    logger.warn('Authentication failed', { path: req.path, error: (error as Error).message });
    res.status(401).json({ code: 401, error: 'Unauthorized' });
  }
};

const rateLimitMiddleware = (req: Request, res: Response, next: NextFunction) => {
  const auth = (req as any).auth;
  const key = auth?.user_id || auth?.api_key || req.ip || 'unknown';

  const result = apiGateway.checkRateLimit(key);
  if (result.allowed) {
    next();
  } else {
    res.status(429).json({ code: 429, error: 'Rate limit exceeded' });
  }
};

app.use(authMiddleware);
app.use(rateLimitMiddleware);

app.get('/', (req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      service: 'Model Evaluation & Automation Engine',
      version: '1.0.0',
      status: 'running',
      uptime: process.uptime(),
      modules: [
        'model_evaluation',
        'configuration_management',
        'api_gateway',
        'prompt_experiment',
        'monitoring',
        'logging',
        'core_processing',
        'document_pipeline',
        'feature_storage'
      ]
    }
  });
});

app.use('/api/v1/auth', authRoutes);
app.use('/api/v1/config', configRoutes);
app.use('/api/v1/tasks', taskRoutes);
app.use('/api/v1/documents', documentRoutes);
app.use('/api/v1/evaluation', evaluationRoutes);
app.use('/api/v1/prompts', promptRoutes);
app.use('/api/v1/features', featureRoutes);
app.use('/api/v1/logging', loggingRoutes);
app.use('/', healthRoutes);

app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
  logger.error('Unhandled error', { error: err.message, stack: err.stack, path: req.path });
  res.status(500).json({ code: 500, error: 'Internal server error' });
});

app.use((req: Request, res: Response) => {
  res.status(404).json({ code: 404, error: 'Not found' });
});

async function bootstrap() {
  try {
    logger.info('Starting application bootstrap...');

    configManager.createConfig('default', {
      'app.name': 'Model Evaluation Engine',
      'app.version': '1.0.0',
      'features.task_scheduling': true,
      'limits.max_concurrent_tasks': 100,
    });

    coreProcessor.registerHandler({
      name: 'document.parse',
      handler: async (payload) => ({ status: 'completed', result: payload }),
      timeout_ms: 30000,
    });

    coreProcessor.registerHandler({
      name: 'model.evaluate',
      handler: async () => ({ status: 'completed', metrics: { accuracy: 0.95 } }),
      timeout_ms: 60000,
    });

    app.listen(PORT, () => {
      logger.info(`Server running on port ${PORT}`, { port: PORT });
      console.log(`🚀 Server running at http://localhost:${PORT}`);
      console.log(`📊 Health check: http://localhost:${PORT}/health`);
      console.log(`📈 Metrics: http://localhost:${PORT}/metrics`);
      console.log(`📚 API docs: POST /api/v1/auth/login for authentication`);
    });

  } catch (error) {
    logger.fatal('Bootstrap failed', { error: (error as Error).message });
    process.exit(1);
  }
}

process.on('SIGTERM', async () => {
  logger.info('SIGTERM received, shutting down gracefully...');
  await coreProcessor.shutdown();
  process.exit(0);
});

process.on('SIGINT', async () => {
  logger.info('SIGINT received, shutting down gracefully...');
  await coreProcessor.shutdown();
  process.exit(0);
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled rejection', { reason: String(reason), promise: String(promise) });
});

process.on('uncaughtException', (err) => {
  logger.fatal('Uncaught exception', { error: err.message, stack: err.stack });
  process.exit(1);
});

bootstrap();

export default app;
