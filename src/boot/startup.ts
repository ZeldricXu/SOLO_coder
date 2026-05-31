import 'reflect-metadata';
import { container, registerDependencies } from '../di/container';
import { App } from '../interface/http/app';
import { getConfig } from '../infrastructure/config/AppConfig';
import { createPrismaClient, disconnectPrisma } from '../infrastructure/persistence/prisma/client/PrismaClient';
import { IEventBusPort, EVENT_BUS_PORT } from '../application/shared/ports/IEventBusPort';
import { IMetricsPort, METRICS_PORT } from '../application/shared/ports/IMetricsPort';

export const startup = async (): Promise<void> => {
  console.log('🚀 Starting Ticket Routing System...');

  registerDependencies();

  const config = getConfig();

  console.log('📦 Initializing Prisma client...');
  createPrismaClient();

  console.log('🔌 Registering event handlers...');
  const eventBus = container.resolve<IEventBusPort>(EVENT_BUS_PORT);
  const metrics = container.resolve<IMetricsPort>(METRICS_PORT);

  eventBus.subscribe('TICKET_CREATED', async (event) => {
    console.log('📨 Event received:', event.type, event.data);
    metrics.increment('events_processed_total', 1, { eventType: event.type });
  });

  eventBus.subscribe('TICKET_ASSIGNED', async (event) => {
    console.log('📨 Event received:', event.type, event.data);
    metrics.increment('events_processed_total', 1, { eventType: event.type });
  });

  console.log('🔧 Registering graceful shutdown...');
  process.on('SIGTERM', async () => {
    console.log('🛑 SIGTERM received, shutting down gracefully...');
    await shutdown();
    process.exit(0);
  });

  process.on('SIGINT', async () => {
    console.log('🛑 SIGINT received, shutting down gracefully...');
    await shutdown();
    process.exit(0);
  });

  process.on('uncaughtException', (err) => {
    console.error('💥 Uncaught exception:', err);
    metrics.increment('errors_uncaught_total', 1);
  });

  process.on('unhandledRejection', (reason, promise) => {
    console.error('💥 Unhandled rejection at:', promise, 'reason:', reason);
    metrics.increment('errors_unhandled_total', 1);
  });

  console.log('🚀 Starting HTTP server...');
  const app = container.resolve(App);
  app.listen(config.port);

  console.log('✅ Startup complete!');
  console.log(`📊 Metrics snapshot:`, JSON.stringify(metrics.getSnapshot(), null, 2));
};

export const shutdown = async (): Promise<void> => {
  console.log('🛑 Shutting down Ticket Routing System...');

  try {
    console.log('📦 Disconnecting Prisma client...');
    await disconnectPrisma();
    console.log('✅ Prisma client disconnected');
  } catch (err) {
    console.error('❌ Error disconnecting Prisma:', err);
  }

  console.log('✅ Shutdown complete!');
};
