import express from 'express';
import type { Express, Request, Response, NextFunction } from 'express';
import pinoHttp from 'pino-http';
import { createApiRouter } from './routes';
import type { AppContainer } from '@application/container';
import type { Logger } from '@shared/logger';

export interface ServerConfig {
  port: number;
  host: string;
  enableCors: boolean;
  enableRequestLogging: boolean;
}

export function createServer(container: AppContainer, logger: Logger, config: ServerConfig): Express {
  const app = express();

  if (config.enableCors) {
    app.use((req, res, next) => {
      res.header('Access-Control-Allow-Origin', '*');
      res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
      res.header('Access-Control-Allow-Headers', 'Content-Type, Authorization');
      if (req.method === 'OPTIONS') {
        return res.status(200).end();
      }
      next();
    });
  }

  if (config.enableRequestLogging) {
    app.use((req, res, next) => {
      const start = Date.now();
      res.on('finish', () => {
        const duration = Date.now() - start;
        const level = res.statusCode >= 500 ? 'error' : res.statusCode >= 400 ? 'warn' : 'info';
        logger[level](`${req.method} ${req.originalUrl} ${res.statusCode}`, {
          method: req.method,
          url: req.originalUrl,
          statusCode: res.statusCode,
          duration,
          ip: req.ip,
        });
      });
      next();
    });
  }

  app.use(express.json({ limit: '10mb' }));
  app.use(express.urlencoded({ extended: true }));

  app.use('/api/v1', createApiRouter(container));

  app.use((_req, res) => {
    res.status(404).json({
      code: 404,
      error: 'Not Found',
    });
  });

  app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
    logger.error('Unhandled error', { error: err.message, stack: err.stack });
    res.status(500).json({
      code: 500,
      error: 'Internal Server Error',
    });
  });

  return app;
}

export async function startServer(
  app: Express,
  config: ServerConfig,
  logger: Logger
): Promise<{ server: ReturnType<Express['listen']>; port: number }> {
  return new Promise((resolve, reject) => {
    const server = app.listen(config.port, config.host, () => {
      logger.info(`Server started`, {
        host: config.host,
        port: config.port,
        url: `http://${config.host}:${config.port}`,
      });
      resolve({ server, port: config.port });
    });

    server.on('error', (err) => {
      logger.error('Failed to start server', { error: err.message });
      reject(err);
    });
  });
}

export async function stopServer(
  server: ReturnType<Express['listen']>,
  logger: Logger
): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((err) => {
      if (err) {
        logger.error('Error stopping server', { error: err.message });
        reject(err);
        return;
      }
      logger.info('Server stopped successfully');
      resolve();
    });
  });
}
