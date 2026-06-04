import fastify from 'fastify';
import cors from '@fastify/cors';
import { config } from './config';
import { logger } from './utils/logger';
import * as notificationController from './controllers/notificationController';
import * as templateController from './controllers/templateController';
import * as preferenceController from './controllers/preferenceController';
import * as webhookController from './controllers/webhookController';
import * as adminController from './controllers/adminController';
import * as orchestrationControllerV2 from './controllers/v2/orchestrationController';
import * as statisticsControllerV2 from './controllers/v2/statisticsController';
import * as templateControllerV2 from './controllers/v2/templateController';

const server = fastify({
  logger: false,
});

server.register(cors, {
  origin: true,
  credentials: true,
});

server.get('/health', async (request, reply) => {
  return { status: 'ok', timestamp: new Date().toISOString() };
});

server.post('/api/v1/notifications/send', notificationController.sendNotification);
server.get('/api/v1/notifications/:id/status', notificationController.getDeliveryStatus);
server.get('/api/v1/notifications/logs/search', notificationController.searchDeliveryLogs);
server.post('/api/v1/notifications/callbacks/:channel', notificationController.handleChannelCallback);

server.post('/api/v1/templates/preview', templateController.previewTemplate);
server.post('/api/v1/templates', templateController.createTemplate);
server.get('/api/v1/templates/:type/:locale', templateController.getTemplate);
server.put('/api/v1/templates/:id', templateController.updateTemplate);

server.get('/api/v1/preferences/:user_id', preferenceController.getUserPreferences);
server.post('/api/v1/preferences/:user_id/channel', preferenceController.updateChannelPreference);
server.post('/api/v1/preferences/:user_id/dnd', preferenceController.updateDoNotDisturb);

server.post('/api/v1/webhooks', webhookController.createWebhookEndpoint);
server.get('/api/v1/webhooks', webhookController.getWebhookEndpoints);
server.get('/api/v1/webhooks/:id', webhookController.getWebhookEndpoint);
server.put('/api/v1/webhooks/:id', webhookController.updateWebhookEndpoint);
server.delete('/api/v1/webhooks/:id', webhookController.deleteWebhookEndpoint);
server.get('/api/v1/webhooks/:id/logs', webhookController.getWebhookLogs);

server.get('/api/v1/admin/health', adminController.getHealthStatus);
server.get('/api/v1/admin/queue/stats', adminController.getQueueStats);
server.get('/api/v1/admin/queue/dlq', adminController.getDlqJobs);
server.post('/api/v1/admin/queue/dlq/:job_id/retry', adminController.retryDlqJob);

server.post('/api/v2/orchestration/sequences', orchestrationControllerV2.createSequence);
server.get('/api/v2/orchestration/sequences', orchestrationControllerV2.listSequences);
server.get('/api/v2/orchestration/sequences/:id', orchestrationControllerV2.getSequence);
server.put('/api/v2/orchestration/sequences/:id', orchestrationControllerV2.updateSequence);
server.delete('/api/v2/orchestration/sequences/:id', orchestrationControllerV2.deleteSequence);
server.post('/api/v2/orchestration/sequences/:id/start', orchestrationControllerV2.startSequence);

server.get('/api/v2/orchestration/instances/:id', orchestrationControllerV2.getInstance);
server.get('/api/v2/orchestration/instances/:id/executions', orchestrationControllerV2.getInstanceExecutions);
server.post('/api/v2/orchestration/instances/:id/cancel', orchestrationControllerV2.cancelInstance);

server.get('/api/v2/statistics/delivery', statisticsControllerV2.getDeliveryStatistics);
server.get('/api/v2/statistics/delivery/grouped', statisticsControllerV2.getGroupedStatistics);
server.get('/api/v2/statistics/delivery/latency', statisticsControllerV2.getLatencyPercentiles);
server.get('/api/v2/statistics/daily-trend', statisticsControllerV2.getDailyTrend);
server.get('/api/v2/statistics/overview', statisticsControllerV2.getDeliveryRateOverview);

server.get('/api/v2/templates', templateControllerV2.listTemplates);
server.get('/api/v2/templates/:notification_type/:locale', templateControllerV2.getTemplate);
server.post('/api/v2/templates', templateControllerV2.createTenantTemplate);
server.put('/api/v2/templates/:id', templateControllerV2.updateTenantTemplate);
server.delete('/api/v2/templates/:id', templateControllerV2.deleteTenantTemplate);
server.post('/api/v2/templates/:notification_type/:locale/reset', templateControllerV2.resetTemplateToDefault);
server.post('/api/v2/templates/system', templateControllerV2.createSystemTemplate);
server.post('/api/v2/templates/render', templateControllerV2.renderTemplate);
server.post('/api/v2/templates/preview', templateControllerV2.previewTemplate);
server.post('/api/v2/templates/cache/clear', templateControllerV2.clearCache);

const start = async () => {
  try {
    await server.listen({
      port: config.server.port,
      host: config.server.host,
    });
    
    logger.info(`Server running on http://${config.server.host}:${config.server.port}`);
    logger.info('API Documentation available at /docs');
  } catch (err) {
    logger.error('Failed to start server', err);
    process.exit(1);
  }
};

const shutdown = async () => {
  logger.info('Shutting down gracefully...');
  try {
    await server.close();
    logger.info('Server closed');
    process.exit(0);
  } catch (err) {
    logger.error('Error during shutdown', err);
    process.exit(1);
  }
};

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

start();
