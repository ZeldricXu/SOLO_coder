import pino from 'pino';

const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  base: {
    service: 'ai-edge-platform'
  },
  timestamp: pino.stdTimeFunctions.isoTime
});

export default logger;
