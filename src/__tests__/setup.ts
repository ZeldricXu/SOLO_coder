import { logger } from '../common';

logger.transports.forEach(transport => {
  transport.silent = true;
});

jest.setTimeout(30000);

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});
