import { createApp } from './app';
import { logger } from './common';

const app = createApp({
  port: parseInt(process.env.PORT || '3000', 10)
});

async function main() {
  try {
    await app.start();
    logger.info('Application started successfully');
  } catch (error) {
    logger.error('Failed to start application', { error: (error as Error).message });
    process.exit(1);
  }
}

process.on('SIGINT', async () => {
  logger.info('Received SIGINT, shutting down...');
  await app.stop();
  process.exit(0);
});

process.on('SIGTERM', async () => {
  logger.info('Received SIGTERM, shutting down...');
  await app.stop();
  process.exit(0);
});

process.on('uncaughtException', (error) => {
  logger.error('Uncaught exception', { error: error.message, stack: error.stack });
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled rejection', { reason, promise });
});

main();

export { app };
