require('dotenv').config();
const messageQueueService = require('./services/messageQueueService');
const config = require('./config');

console.log('========================================');
console.log('EventHub Message Queue Worker');
console.log('========================================');
console.log(`Poll Interval: ${config.queue?.pollInterval || 5000}ms`);
console.log(`Batch Size: ${config.queue?.batchSize || 10}`);
console.log(`Max Retries: ${config.queue?.maxRetries || 3}`);
console.log('========================================');

messageQueueService.startWorker();

process.on('SIGTERM', () => {
  console.log('SIGTERM received, stopping worker...');
  messageQueueService.stopWorker();
  process.exit(0);
});

process.on('SIGINT', () => {
  console.log('SIGINT received, stopping worker...');
  messageQueueService.stopWorker();
  process.exit(0);
});

setInterval(async () => {
  try {
    const stats = await messageQueueService.getQueueStats();
    console.log(`[${new Date().toISOString()}] Queue Stats:`, stats);
  } catch (err) {
    console.error('Failed to get queue stats:', err);
  }
}, 60000);

module.exports = messageQueueService;
