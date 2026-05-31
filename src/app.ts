import express, { Request, Response } from 'express';
import { errorMiddleware, notFoundMiddleware } from './middleware/errorHandler';
import { requestLogger, traceIdMiddleware } from './middleware/requestLogger';
import resourcesRouter from './routes/resources';
import chaosInjectionRouter from './routes/chaosInjection';
import commandAuditRouter from './routes/commandAudit';
import trafficControlRouter from './routes/trafficControl';
import dnsProxyRouter from './routes/dnsProxy';
import mtlsCertRouter from './routes/mtlsCert';
import eventStoreRouter from './routes/eventStore';
import imageDistributionRouter from './routes/imageDistribution';
import sidecarLifecycleRouter from './routes/sidecarLifecycle';
import logger from './utils/logger';

const app = express();

app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(traceIdMiddleware);
app.use(requestLogger);

app.get('/health', (_req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      status: 'healthy',
      timestamp: new Date().toISOString(),
      service: 'chaoslab',
      version: '1.0.0',
    },
  });
});

app.get('/', (_req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      name: 'ChaosLab - 混沌工程实验编排平台',
      version: '1.0.0',
      description: '为开发者打造的混沌工程实验编排服务',
      endpoints: {
        resources: '/api/v1/resources',
        chaos: '/api/v1/chaos',
        audit: '/api/v1/audit',
        traffic: '/api/v1/traffic',
        dns: '/api/v1/dns',
        mtls: '/api/v1/mtls',
        events: '/api/v1/events',
        images: '/api/v1/images',
        sidecar: '/api/v1/sidecar',
        health: '/health',
      },
    },
  });
});

app.use('/api/v1/resources', resourcesRouter);
app.use('/api/v1/chaos', chaosInjectionRouter);
app.use('/api/v1/audit', commandAuditRouter);
app.use('/api/v1/traffic', trafficControlRouter);
app.use('/api/v1/dns', dnsProxyRouter);
app.use('/api/v1/mtls', mtlsCertRouter);
app.use('/api/v1/events', eventStoreRouter);
app.use('/api/v1/images', imageDistributionRouter);
app.use('/api/v1/sidecar', sidecarLifecycleRouter);

app.use(notFoundMiddleware);
app.use(errorMiddleware);

export default app;
